import 'dotenv/config'
import fs from 'node:fs/promises'
import { and, eq } from 'drizzle-orm'
import { db } from '../db/db.js'
import { auditLogs, claimReceipts, claims, receiptCategories, receipts, reportFiles, reports, users } from '../db/schema.js'
import { readBuffer, saveBuffer, createStorageKey, deleteStorageObject } from './storageService.js'

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

function formatOptionalDate(value) {
  return value ? formatDate(value) : '—'
}

async function claimRows(userId, claimId) {
  const [claim] = await db.select({ id: claims.id, userId: claims.userId, title: claims.title, startDate: claims.startDate, endDate: claims.endDate, status: claims.status, totalAmount: claims.totalAmount, totalSgdEquivalent: claims.totalSgdEquivalent, submittedAt: claims.submittedAt, createdAt: claims.createdAt, updatedAt: claims.updatedAt, displayName: users.displayName }).from(claims).innerJoin(users, eq(claims.userId, users.id)).where(and(eq(claims.id, claimId), eq(claims.userId, userId))).limit(1)
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

function replaceTemplateSampleIdentity(sheet, displayName) {
  const replacement = String(displayName || '').trim()
  sheet.eachRow((row) => row.eachCell({ includeEmpty: false }, (cell) => {
    if (typeof cell.value !== 'string') return
    if (/vadivelan/i.test(cell.value)) cell.value = cell.value.replace(/vadivelan/gi, replacement)
    // The distributed workbook contains a second sample identity in the approver
    // block. Never leak that sample person into another claimant's export.
    if (/firdaus\s+j/i.test(cell.value)) cell.value = cell.value.replace(/firdaus\s+j/gi, '')
  }))
}

function copyRowStyle(source, target) {
  target.height = source.height
  target.hidden = source.hidden
  source.eachCell({ includeEmpty: true }, (sourceCell, columnNumber) => {
    const targetCell = target.getCell(columnNumber)
    targetCell.style = { ...sourceCell.style }
    targetCell.numFmt = sourceCell.numFmt
  })
}

function populateTemplate(sheet, template, rows, claim) {
  let { dataStart, dataEnd, totalRow, columns } = template
  const originalDataEnd = dataEnd
  const originalTotalRow = totalRow
  const capacity = Math.max(0, dataEnd - dataStart + 1)
  if (rows.length > capacity) {
    const extraRows = rows.length - capacity
    const styleSource = sheet.getRow(Math.max(dataStart, dataEnd))
    sheet.spliceRows(totalRow, 0, ...Array.from({ length: extraRows }, () => []))
    for (let offset = 0; offset < extraRows; offset += 1) copyRowStyle(styleSource, sheet.getRow(totalRow + offset))
    dataEnd += extraRows
    totalRow += extraRows
    sheet.eachRow((row) => row.eachCell({ includeEmpty: false }, (cell) => {
      if (!cell.value || typeof cell.value !== 'object' || typeof cell.value.formula !== 'string') return
      // `spliceRows` moves the total and every row below it but ExcelJS does not
      // rewrite formula references. Shift references at/after the old total row,
      // and extend ranges that ended at the old final data row. Dropping a cached
      // `result` forces Excel/LibreOffice to recalculate the changed formula.
      const formula = cell.value.formula.replace(/(\$?[A-Z]+)(\$?)(\d+)/g, (match, column, absoluteRow, rowNumber) => {
        const row = Number(rowNumber)
        if (row >= originalTotalRow) return `${column}${absoluteRow}${row + extraRows}`
        if (row === originalDataEnd) return `${column}${absoluteRow}${dataEnd}`
        return match
      })
      cell.value = { formula }
    }))
  }
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
  replaceTemplateSampleIdentity(sheet, claim.displayName)
}

async function buildExcel(rows, claim) {
  const ExcelJS = (await import('exceljs')).default
  const workbook = new ExcelJS.Workbook()
  const templatePath = process.env.EXCEL_TEMPLATE_PATH
  const templateStorageKey = process.env.EXCEL_TEMPLATE_STORAGE_KEY
  let sheet
  let template
  if (templateStorageKey || templatePath) {
    try {
      if (templateStorageKey) await workbook.xlsx.load(await readBuffer(templateStorageKey))
      else {
        await fs.access(templatePath)
        await workbook.xlsx.readFile(templatePath)
      }
      sheet = workbook.worksheets[0]
      template = locateTemplate(sheet)
    } catch (error) {
      error.code = 'REPORT_TEMPLATE_UNAVAILABLE'
      throw error
    }
  }
  if ((templateStorageKey || templatePath) && sheet && !template) {
    const error = new Error('Configured Excel template does not contain a recognizable claim table.')
    error.code = 'REPORT_TEMPLATE_INVALID'
    throw error
  }
  if (sheet && template) {
    populateTemplate(sheet, template, rows, claim)
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
    children.push(new Paragraph({ children: [new TextRun({ text: `${index + 1}. ${receipt.description || receipt.note || 'Receipt'}`, bold: true })] }))
    if (receipt.storageKey) {
      try {
        const image = await readBuffer(receipt.storageKey)
        const type = detectImageKind(image, receipt.mimeType)
        if (!type) throw new Error('Unsupported receipt image format')
        children.push(new Paragraph({ children: [new ImageRun({ data: image, type, transformation: { width: 450, height: 320 } })] }))
      } catch { children.push(new Paragraph('Receipt image unavailable')) }
    }
    children.push(new Paragraph(`Amount: ${receipt.currency || ''} ${Number(receipt.amount || 0).toFixed(2)} | S$ equivalent: ${Number(receipt.sgdEquivalent || 0).toFixed(2)}`))
  }
  return Packer.toBuffer(new Document({ sections: [{ children }] }))
}

function detectImageKind(bytes, mimeType = '') {
  const buffer = Buffer.from(bytes)
  const isPng = buffer.length >= 8 && buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4e && buffer[3] === 0x47
  const isJpeg = buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff
  if (isPng || /^image\/png$/i.test(mimeType)) return 'png'
  if (isJpeg || /^image\/(jpe?g)$/i.test(mimeType)) return 'jpg'
  return null
}

function wrapText(value, maxChars = 48) {
  const text = String(value ?? '')
  if (!text) return ['']
  const words = text.split(/\s+/)
  const lines = []
  let line = ''
  for (const word of words) {
    const next = line ? `${line} ${word}` : word
    if (next.length > maxChars && line) { lines.push(line); line = word } else line = next
  }
  if (line) lines.push(line)
  return lines.length ? lines : ['']
}

async function buildPdf(rows, claim) {
  const { PDFDocument, StandardFonts, rgb } = await import('pdf-lib')
  const pdf = await PDFDocument.create()
  const font = await pdf.embedFont(StandardFonts.Helvetica)
  const bold = await pdf.embedFont(StandardFonts.HelveticaBold)
  const width = 595
  const height = 842
  const margin = 42
  const totalSgd = rows.reduce((sum, row) => sum + Number(row.sgdEquivalent || 0), 0)
  const currencyTotals = rows.reduce((totals, row) => {
    const code = String(row.currency || 'SGD').toUpperCase()
    totals[code] = (totals[code] || 0) + Number(row.amount || 0)
    return totals
  }, {})
  const summaryPages = []
  let page = pdf.addPage([width, height])
  summaryPages.push(page)
  let y = height - 52
  const newSummaryPage = () => { page = pdf.addPage([width, height]); summaryPages.push(page); y = height - 52 }
  const write = (text, size = 10, useBold = false) => {
    if (y < 55) newSummaryPage()
    page.drawText(String(text), { x: margin, y, size, font: useBold ? bold : font, color: rgb(0.1, 0.1, 0.1) })
    y -= size + 8
  }
  write(`Expense claim: ${claim.title}`, 18, true)
  write(`Claimant: ${claim.displayName || 'Unknown'} | Status: ${claim.status || 'DRAFT'}`, 10)
  write(`Period: ${formatOptionalDate(claim.startDate)} to ${formatOptionalDate(claim.endDate)}`, 10)
  write(`Total S$ equivalent: ${totalSgd.toFixed(2)}`, 11, true)
  write(`Original currency totals: ${Object.entries(currencyTotals).map(([currency, amount]) => `${currency} ${amount.toFixed(2)}`).join(' | ') || 'None'}`, 9)
  y -= 4
  const columns = [margin, 62, 105, 245, 330, 390, 445, 510]
  const headers = ['No.', 'Date', 'Description', 'Category', 'Currency', 'Amount', 'Rate', 'SGD']
  headers.forEach((header, index) => page.drawText(header, { x: columns[index], y, size: 8, font: bold, color: rgb(0.05, 0.05, 0.05) }))
  y -= 18
  for (const [index, row] of rows.entries()) {
    const values = [String(index + 1), formatDate(row.receiptDate), row.description || row.note || '', row.categoryName || '', row.currency || '', Number(row.amount || 0).toFixed(2), Number(row.exchangeRate || 0).toFixed(6), Number(row.sgdEquivalent || 0).toFixed(2)]
    const lineCount = Math.max(...[values[2], values[3]].map((value) => wrapText(value, 22).length), 1)
    const rowHeight = lineCount * 10 + 7
    if (y - rowHeight < 50) { newSummaryPage(); headers.forEach((header, headerIndex) => page.drawText(header, { x: columns[headerIndex], y, size: 8, font: bold, color: rgb(0.05, 0.05, 0.05) })); y -= 18 }
    page.drawText(values[0], { x: columns[0], y, size: 8, font })
    page.drawText(values[1], { x: columns[1], y, size: 8, font })
    wrapText(values[2], 22).forEach((line, lineIndex) => page.drawText(line, { x: columns[2], y: y - lineIndex * 10, size: 8, font }))
    wrapText(values[3], 15).forEach((line, lineIndex) => page.drawText(line, { x: columns[3], y: y - lineIndex * 10, size: 8, font }))
    page.drawText(values[4], { x: columns[4], y, size: 8, font })
    page.drawText(values[5], { x: columns[5], y, size: 8, font })
    page.drawText(values[6], { x: columns[6], y, size: 7, font })
    page.drawText(values[7], { x: columns[7], y, size: 8, font })
    y -= rowHeight
  }
  write('Receipt appendix follows on the next pages.', 9, true)

  // Each receipt gets a dedicated page so images cannot overlap or disappear after pagination.
  for (const [index, receipt] of rows.entries()) {
    page = pdf.addPage([width, height])
    let receiptY = height - 52
    const appendWrite = (text, size = 11, useBold = false) => {
      page.drawText(String(text), { x: margin, y: receiptY, size, font: useBold ? bold : font, color: rgb(0.1, 0.1, 0.1) })
      receiptY -= size + 9
    }
    appendWrite(`Receipt ${index + 1}: ${receipt.description || receipt.note || 'Receipt'}`, 15, true)
    appendWrite(`Date: ${formatDate(receipt.receiptDate)} | Category: ${receipt.categoryName || ''}`, 10)
    appendWrite(`Amount: ${receipt.currency || ''} ${Number(receipt.amount || 0).toFixed(2)} | Exchange rate: ${Number(receipt.exchangeRate || 0).toFixed(6)} | S$ ${Number(receipt.sgdEquivalent || 0).toFixed(2)}`, 10)
    if (receipt.storageKey) {
      try {
        const imageBytes = await readBuffer(receipt.storageKey)
        const kind = detectImageKind(imageBytes, receipt.mimeType)
        if (!kind) throw new Error('Unsupported receipt image format')
        const image = kind === 'png' ? await pdf.embedPng(imageBytes) : await pdf.embedJpg(imageBytes)
        const scale = Math.min((width - margin * 2) / image.width, 590 / image.height, 1)
        const imageHeight = image.height * scale
        page.drawImage(image, { x: margin, y: Math.max(60, receiptY - imageHeight - 10), width: image.width * scale, height: imageHeight })
      } catch { appendWrite('Receipt image unavailable or unsupported.', 10) }
    } else appendWrite('Receipt image unavailable.', 10)
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
  const files = []
  try {
    const [xlsx, docx, pdf] = await Promise.all([buildExcel(rows, claim), buildDocx(rows, claim), buildPdf(rows, claim)])
    files.push(await saveReportFile(report.id, userId, claimId, 'EXCEL', 'xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', xlsx))
    files.push(await saveReportFile(report.id, userId, claimId, 'WORD', 'docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', docx))
    files.push(await saveReportFile(report.id, userId, claimId, 'PDF', 'pdf', 'application/pdf', pdf))
    const [updated] = await db.update(reports).set({ status: 'COMPLETED', completedAt: new Date() }).where(eq(reports.id, report.id)).returning({ id: reports.id, userId: reports.userId, claimId: reports.claimId, status: reports.status, createdAt: reports.createdAt, completedAt: reports.completedAt, errorMessage: reports.errorMessage })
    await db.insert(auditLogs).values({ actorUserId: userId, action: 'REPORT_GENERATED', resourceType: 'claim', resourceId: claimId, metadata: JSON.stringify({ reportId: report.id }) })
    return { report: updated, files }
  } catch (error) {
    console.error(error)
    await Promise.allSettled(files.map((file) => deleteStorageObject(file.storageKey)))
    await db.delete(reportFiles).where(eq(reportFiles.reportId, report.id))
    await db.update(reports).set({ status: 'FAILED', errorMessage: 'Report generation failed.' }).where(eq(reports.id, report.id))
    const publicError = new Error('REPORT_GENERATION_FAILED')
    publicError.statusCode = 500
    throw publicError
  }
}

export async function getReport(userId, reportId) {
  const [report] = await db.select({ id: reports.id, userId: reports.userId, claimId: reports.claimId, status: reports.status, createdAt: reports.createdAt, completedAt: reports.completedAt, errorMessage: reports.errorMessage }).from(reports).where(and(eq(reports.id, reportId), eq(reports.userId, userId))).limit(1)
  if (!report) return null
  const files = await db.select({ id: reportFiles.id, reportId: reportFiles.reportId, fileType: reportFiles.fileType, storageKey: reportFiles.storageKey, fileName: reportFiles.fileName, mimeType: reportFiles.mimeType, createdAt: reportFiles.createdAt }).from(reportFiles).where(eq(reportFiles.reportId, reportId))
  return { ...report, files }
}

export const reportService = { generateClaimReport, getReport }
export const reportInternals = { buildExcel, buildPdf, detectImageKind, locateTemplate, populateTemplate, claimRows }
