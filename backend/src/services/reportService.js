import 'dotenv/config'
import fs from 'node:fs/promises'
import { and, eq } from 'drizzle-orm'
import { db } from '../db/db.js'
import { auditLogs, claimReceipts, claims, receiptCategories, receipts, reportFiles, reports } from '../db/schema.js'
import { readBuffer, saveBuffer, createStorageKey } from './storageService.js'

const EXCEL_HEADERS = [
  'S/N', 'Date', 'Receipt Number', 'Description', 'Category',
  'Amount Excluding GST', 'Currency', 'Exchange Rate', 'S$ Equivalent',
]

function asDate(value) {
  const date = value ? new Date(value) : new Date()
  return Number.isNaN(date.getTime()) ? new Date() : date
}

function formatDate(value) {
  return asDate(value).toISOString().slice(0, 10)
}

async function claimRows(userId, claimId) {
  const [claim] = await db.select({ id: claims.id, userId: claims.userId, title: claims.title, startDate: claims.startDate, endDate: claims.endDate, status: claims.status, totalAmount: claims.totalAmount, totalSgdEquivalent: claims.totalSgdEquivalent, submittedAt: claims.submittedAt, createdAt: claims.createdAt, updatedAt: claims.updatedAt }).from(claims).where(and(eq(claims.id, claimId), eq(claims.userId, userId))).limit(1)
  if (!claim) {
    const error = new Error('Claim not found')
    error.statusCode = 404
    throw error
  }
  const rows = await db.select({
    receipt: receipts,
    category: receiptCategories,
  }).from(claimReceipts)
    .innerJoin(receipts, eq(claimReceipts.receiptId, receipts.id))
    .innerJoin(receiptCategories, eq(receipts.categoryId, receiptCategories.id))
    .where(eq(claimReceipts.claimId, claimId))
  return { claim, rows: rows.map(({ receipt, category }) => ({ ...receipt, categoryName: category.name })) }
}

function normalizeHeader(value) {
  return String(value || '').trim().toLowerCase().replace(/\\s+/g, ' ')
}

function receiptValues(receipt, index) {
  return {
    no: index + 1,
    date: asDate(receipt.receiptDate),
    description: receipt.description || receipt.note || '',
    accountCode: receipt.accountCode || '',
    amount: Number(receipt.amount),
    currency: receipt.currency,
    exchangeRate: Number(receipt.exchangeRate),
    sgdEquivalent: Number(receipt.sgdEquivalent),
    category: receipt.categoryName || '',
  }
}

function findTemplateColumns(sheet, headerRow) {
  const columns = {}
  sheet.getRow(headerRow).eachCell({ includeEmpty: false }, (cell, columnNumber) => {
    const header = normalizeHeader(cell.value)
    if (['no', 's/n', 'serial no'].includes(header)) columns.no = columnNumber
    else if (header === 'date' || header === 'receipt date') columns.date = columnNumber
    else if (header.includes('accounts code')) columns.accountCode = columnNumber
    else if (header === 'description' || header === 'particulars') columns.description = columnNumber
    else if (header.includes('amount excluding gst')) columns.amount = columnNumber
    else if (header === 'currency' || ['inr', 'usd', 'sgd', 'eur', 'gbp'].includes(header)) columns.currency = columnNumber
    else if (header === 'exchange rate') columns.exchangeRate = columnNumber
    else if (header.includes('s$ equivalent') || header.includes('sgd equivalent')) columns.sgdEquivalent = columnNumber
    else if (header.includes('cost centre 2') || header.includes('cost center 2')) columns.category = columnNumber
  })
  if (columns.amount && !columns.currency) columns.currency = columns.amount + 2
  if (columns.currency && !columns.exchangeRate) columns.exchangeRate = columns.currency + 1
  if (columns.exchangeRate && !columns.sgdEquivalent) columns.sgdEquivalent = columns.exchangeRate + 1
  return columns
}

function locateTemplate(sheet) {
  let headerRow = 0
  for (let rowNumber = 1; rowNumber <= sheet.rowCount; rowNumber += 1) {
    const headers = sheet.getRow(rowNumber).values.map(normalizeHeader)
    if (headers.includes('no') && headers.some((header) => header.includes('amount excluding gst'))) { headerRow = rowNumber; break }
  }
  if (!headerRow) return null
  const columns = findTemplateColumns(sheet, headerRow)
  let totalRow = sheet.rowCount + 1
  for (let rowNumber = headerRow + 1; rowNumber <= sheet.rowCount; rowNumber += 1) {
    const values = sheet.getRow(rowNumber).values.map(normalizeHeader)
    if (values.includes('total')) { totalRow = rowNumber; break }
  }
  return { headerRow, dataStart: headerRow + 1, dataEnd: totalRow - 1, totalRow, columns }
}

function populateTemplate(sheet, template, rows) {
  const { dataStart, dataEnd, totalRow, columns } = template
  // Clear sample receipt values while leaving all template styles and formulas outside the data area intact.
  for (let rowNumber = dataStart; rowNumber <= dataEnd; rowNumber += 1) {
    sheet.getRow(rowNumber).eachCell({ includeEmpty: true }, (cell) => { cell.value = null })
  }
  rows.forEach((receipt, index) => {
    const values = receiptValues(receipt, index)
    const row = sheet.getRow(dataStart + index)
    const write = (key, value) => { if (columns[key]) row.getCell(columns[key]).value = value }
    write('no', values.no)
    write('date', values.date)
    write('accountCode', values.accountCode)
    write('description', values.description)
    write('amount', values.amount)
    write('currency', values.currency)
    write('exchangeRate', values.exchangeRate)
    write('sgdEquivalent', values.sgdEquivalent)
    write('category', values.category)
  })
  if (columns.amount) sheet.getRow(totalRow).getCell(columns.amount).value = { formula: `SUM(${sheet.getColumn(columns.amount).letter}${dataStart}:${sheet.getColumn(columns.amount).letter}${dataEnd})` }
  if (columns.sgdEquivalent) sheet.getRow(totalRow).getCell(columns.sgdEquivalent).value = { formula: `SUM(${sheet.getColumn(columns.sgdEquivalent).letter}${dataStart}:${sheet.getColumn(columns.sgdEquivalent).letter}${dataEnd})` }
}

async function buildExcel(rows, claim) {
  const ExcelJS = (await import('exceljs')).default
  const workbook = new ExcelJS.Workbook()
  const templatePath = process.env.EXCEL_TEMPLATE_PATH
  let sheet
  let template
  if (templatePath) {
    try {
      await fs.access(templatePath)
      await workbook.xlsx.readFile(templatePath)
      sheet = workbook.worksheets[0]
      template = locateTemplate(sheet)
    } catch { /* fall through to generated workbook */ }
  }
  if (sheet && template) {
    populateTemplate(sheet, template, rows)
    return workbook.xlsx.writeBuffer()
  }

  sheet = workbook.addWorksheet('Expense Claim')
  sheet.addRow([`Claim: ${claim.title}`])
  sheet.getRow(1).font = { bold: true, size: 14 }
  sheet.mergeCells(1, 1, 1, EXCEL_HEADERS.length)
  sheet.addRow(EXCEL_HEADERS)
  sheet.getRow(2).font = { bold: true }
  rows.forEach((receipt, index) => {
    const values = receiptValues(receipt, index)
    sheet.addRow([values.no, formatDate(values.date), receipt.id, values.description, values.category, values.amount, values.currency, values.exchangeRate, values.sgdEquivalent])
  })
  sheet.addRow([])
  sheet.addRow(['', '', '', '', 'Total', '', '', '', rows.reduce((sum, row) => sum + Number(row.sgdEquivalent || 0), 0)])
  sheet.columns.forEach((column, index) => { column.width = [8, 14, 16, 28, 22, 20, 12, 16, 18][index] || 16 })
  sheet.getColumn(6).numFmt = '#,##0.00'
  sheet.getColumn(8).numFmt = '0.000000'
  sheet.getColumn(9).numFmt = '#,##0.00'
  return workbook.xlsx.writeBuffer()
}
async function buildDocx(rows, claim) {
  const { Document, HeadingLevel, ImageRun, Packer, Paragraph, TextRun } = await import('docx')
  const children = [
    new Paragraph({ text: `Expense claim: ${claim.title}`, heading: HeadingLevel.HEADING_1 }),
    new Paragraph(`Generated ${new Date().toLocaleDateString('en-SG')}`),
  ]
  for (const [index, receipt] of rows.entries()) {
    children.push(new Paragraph({ children: [new TextRun({ text: `${index + 1}. ${receipt.description || receipt.note || 'Receipt'} — ${receipt.currency} ${Number(receipt.amount).toFixed(2)}`, bold: true })] }))
    if (receipt.storageKey) {
      try {
        const image = await readBuffer(receipt.storageKey)
        const type = receipt.mimeType === 'image/png' ? 'png' : 'jpg'
        children.push(new Paragraph({ children: [new ImageRun({ data: image, type, transformation: { width: 450, height: 320 } })] }))
      } catch { children.push(new Paragraph('Receipt image unavailable')) }
    }
    children.push(new Paragraph(`S$ equivalent: ${Number(receipt.sgdEquivalent).toFixed(2)}`))
  }
  return Packer.toBuffer(new Document({ sections: [{ children }] }))
}

async function buildPdf(rows, claim) {
  const { PDFDocument, StandardFonts, rgb } = await import('pdf-lib')
  const pdf = await PDFDocument.create()
  const font = await pdf.embedFont(StandardFonts.Helvetica)
  let page = pdf.addPage([595, 842])
  let y = 790
  const write = (text, size = 12) => { page.drawText(String(text), { x: 48, y, size, font, color: rgb(0.1, 0.1, 0.1) }); y -= size + 10 }
  write(`Expense claim: ${claim.title}`, 18)
  write(`Generated ${new Date().toLocaleDateString('en-SG')}`, 10)
  write(`Total S$ equivalent: ${rows.reduce((sum, row) => sum + Number(row.sgdEquivalent || 0), 0).toFixed(2)}`, 12)
  for (const [index, receipt] of rows.entries()) {
    if (y < 150) { page = pdf.addPage([595, 842]); y = 790 }
    write(`${index + 1}. ${receipt.description || receipt.note || 'Receipt'}`, 12)
    write(`${receipt.currency} ${Number(receipt.amount).toFixed(2)} | S$ ${Number(receipt.sgdEquivalent).toFixed(2)} | ${receipt.categoryName}`, 10)
    if (receipt.storageKey) {
      try {
        const imageBytes = await readBuffer(receipt.storageKey)
        const image = receipt.mimeType === 'image/png' ? await pdf.embedPng(imageBytes) : await pdf.embedJpg(imageBytes)
        const scale = Math.min(480 / image.width, 270 / image.height, 1)
        page.drawImage(image, { x: 48, y: y - image.height * scale, width: image.width * scale, height: image.height * scale })
        y -= image.height * scale + 20
      } catch { write('Receipt image unavailable', 10) }
    }
  }
  return pdf.save()
}

async function saveReportFile(reportId, userId, claimId, fileType, extension, mimeType, data) {
  const storage = await saveBuffer(Buffer.from(data), { key: createStorageKey(`reports/${userId}/${claimId}`, extension), contentType: mimeType })
  const [file] = await db.insert(reportFiles).values({ reportId, fileType, storageKey: storage.key, fileName: `${fileType.toLowerCase()}-${claimId}.${extension}`, mimeType }).returning({ id: reportFiles.id, reportId: reportFiles.reportId, fileType: reportFiles.fileType, storageKey: reportFiles.storageKey, fileName: reportFiles.fileName, mimeType: reportFiles.mimeType, createdAt: reportFiles.createdAt })
  return file
}

export async function generateClaimReport({ userId, claimId }) {
  const { claim, rows } = await claimRows(userId, claimId)
  const [report] = await db.insert(reports).values({ userId, claimId, status: 'GENERATING' }).returning({ id: reports.id, userId: reports.userId, claimId: reports.claimId, status: reports.status, createdAt: reports.createdAt, completedAt: reports.completedAt, errorMessage: reports.errorMessage })
  try {
    const [xlsx, docx, pdf] = await Promise.all([buildExcel(rows, claim), buildDocx(rows, claim), buildPdf(rows, claim)])
    const files = []
    files.push(await saveReportFile(report.id, userId, claimId, 'EXCEL', 'xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', xlsx))
    files.push(await saveReportFile(report.id, userId, claimId, 'WORD', 'docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', docx))
    files.push(await saveReportFile(report.id, userId, claimId, 'PDF', 'pdf', 'application/pdf', pdf))
    const [updated] = await db.update(reports).set({ status: 'COMPLETED', completedAt: new Date() }).where(eq(reports.id, report.id)).returning({ id: reports.id, userId: reports.userId, claimId: reports.claimId, status: reports.status, createdAt: reports.createdAt, completedAt: reports.completedAt, errorMessage: reports.errorMessage })
    await db.insert(auditLogs).values({ actorUserId: userId, action: 'REPORT_GENERATED', resourceType: 'claim', resourceId: claimId, metadata: JSON.stringify({ reportId: report.id }) })
    return { report: updated, files }
  } catch (error) {
    await db.update(reports).set({ status: 'FAILED', errorMessage: error.message }).where(eq(reports.id, report.id))
    throw error
  }
}

export async function getReport(userId, reportId) {
  const [report] = await db.select({ id: reports.id, userId: reports.userId, claimId: reports.claimId, status: reports.status, createdAt: reports.createdAt, completedAt: reports.completedAt, errorMessage: reports.errorMessage }).from(reports).where(and(eq(reports.id, reportId), eq(reports.userId, userId))).limit(1)
  if (!report) return null
  const files = await db.select({ id: reportFiles.id, reportId: reportFiles.reportId, fileType: reportFiles.fileType, storageKey: reportFiles.storageKey, fileName: reportFiles.fileName, mimeType: reportFiles.mimeType, createdAt: reportFiles.createdAt }).from(reportFiles).where(eq(reportFiles.reportId, reportId))
  return { ...report, files }
}

export const reportService = { generateClaimReport, getReport }

