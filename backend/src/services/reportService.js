import 'dotenv/config'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import 'regenerator-runtime/runtime.js'
import { and, desc, eq, gte } from 'drizzle-orm'
import { db } from '../db/db.js'
import { auditLogs, claimReceipts, claims, receiptCategories, receipts, reportFiles, reports, users } from '../db/schema.js'
import { readBuffer, saveBuffer, createStorageKey, deleteStorageObject } from './storageService.js'

const EXCEL_HEADERS = [
  'S/N', 'Date', 'Receipt Number', 'Description', 'Category',
  'Receipt Amount (as paid)', 'Currency', 'Exchange Rate', 'S$ Equivalent',
]
const activeGenerations = new Map()
const ACTIVE_GENERATION_WINDOW_MS = 20 * 60 * 1000
const MAX_REPORT_IMAGE_BYTES = 50 * 1024 * 1024

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
    else if (header.includes('amount excluding gst') || header.includes('receipt amount')) columns.amount = columnNumber
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
    if (headers.includes('no') && headers.some((header) => header.includes('amount excluding gst') || header.includes('receipt amount'))) { headerRow = rowNumber; break }
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
    // The workbook also carries sample dates beside signer DATE labels.
    if (cell.value.trim().toUpperCase() === 'DATE:') row.getCell(cell.col + 1).value = null
  }))
  sheet.eachRow((row) => {
    const values = []
    row.eachCell({ includeEmpty: false }, (cell) => { if (typeof cell.value === 'string') values.push({ cell, value: cell.value }) })
    const profileLabels = values.filter(({ value }) => /^\s*(department|position)\s*:/i.test(value))
    if (!profileLabels.length) return
    const labels = new Set(profileLabels.map(({ value }) => value.trim().split(':')[0].toLowerCase()))
    for (const { cell, value } of values) {
      const label = value.trim().split(':')[0].toLowerCase()
      if (labels.has(label) && value.includes(':')) cell.value = `${value.slice(0, value.indexOf(':') + 1)}`
      else if (value.trim()) cell.value = null
    }
  })
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
  // The app captures the total amount paid and has no GST breakdown. Make the
  // template say what the stored value means, and leave its GST field empty.
  const currencyHeader = columns.currency && sheet.getRow(template.headerRow).getCell(columns.currency)
  if (currencyHeader) currencyHeader.value = 'Currency'
  if (columns.amount) sheet.getRow(template.headerRow).getCell(columns.amount).value = 'Receipt Amount (as paid)'
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
      error.statusCode = 503
      throw error
    }
  }
  if ((templateStorageKey || templatePath) && sheet && !template) {
    const error = new Error('Configured Excel template does not contain a recognizable claim table.')
    error.code = 'REPORT_TEMPLATE_INVALID'
    error.statusCode = 422
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
async function loadReceiptImage(receipt) {
  if (!receipt.storageKey) throw reportServiceError('REPORT_RECEIPT_IMAGE_INVALID', 422, 'A receipt image is missing. Review the claim receipts and try again.')
  let bytes
  try { bytes = await readBuffer(receipt.storageKey) } catch {
    throw reportServiceError('REPORT_RECEIPT_IMAGE_UNAVAILABLE', 503, 'A receipt image is temporarily unavailable. Try generating the report again later.')
  }
  const type = detectImageKind(bytes, receipt.mimeType)
  if (!type) throw reportServiceError('REPORT_RECEIPT_IMAGE_INVALID', 422, 'A receipt image is not a supported PNG or JPEG. Replace it and try again.')
  try {
    const { PDFDocument } = await import('pdf-lib')
    const validator = await PDFDocument.create()
    if (type === 'png') await validator.embedPng(bytes)
    else await validator.embedJpg(bytes)
  } catch {
    throw reportServiceError('REPORT_RECEIPT_IMAGE_INVALID', 422, 'A receipt image is damaged. Replace it and try again.')
  }
  return { bytes, type }
}

function assertReportImageBudget(currentBytes, nextBytes, maxBytes = MAX_REPORT_IMAGE_BYTES) {
  if (currentBytes + nextBytes > maxBytes) {
    throw reportServiceError('REPORT_CLAIM_TOO_LARGE', 422, 'This claim contains too much receipt image data for one report. Split it into smaller claims and try again.')
  }
}

async function collectReceiptImages(rows, maxBytes = MAX_REPORT_IMAGE_BYTES) {
  const images = new Map()
  let totalBytes = 0
  for (const receipt of rows) {
    const image = await loadReceiptImage(receipt)
    assertReportImageBudget(totalBytes, image.bytes.length, maxBytes)
    totalBytes += image.bytes.length
    images.set(receipt.id, image)
  }
  return images
}

async function buildDocx(rows, claim, receiptImages) {
  const { Document, HeadingLevel, ImageRun, Packer, Paragraph, TextRun } = await import('docx')
  const children = [
    new Paragraph({ text: `Expense claim: ${claim.title}`, heading: HeadingLevel.HEADING_1 }),
    new Paragraph(`Generated ${new Date().toLocaleDateString('en-SG')}`),
  ]
  for (const [index, receipt] of rows.entries()) {
    children.push(new Paragraph({ children: [new TextRun({ text: `${index + 1}. ${receipt.description || receipt.note || 'Receipt'}`, bold: true })] }))
    const image = receiptImages?.get(receipt.id) || await loadReceiptImage(receipt)
    children.push(new Paragraph({ children: [new ImageRun({ data: image.bytes, type: image.type, transformation: fitImageDimensions(image.bytes, image.type) })] }))
    children.push(new Paragraph(`Amount: ${receipt.currency || ''} ${Number(receipt.amount || 0).toFixed(2)} | S$ equivalent: ${Number(receipt.sgdEquivalent || 0).toFixed(2)}`))
  }
  return Packer.toBuffer(new Document({ sections: [{ children }] }))
}

function imageDimensions(bytes, type) {
  const buffer = Buffer.from(bytes)
  if (type === 'png' && buffer.length >= 24) return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) }
  if (type === 'jpg') {
    let offset = 2
    while (offset + 9 < buffer.length) {
      if (buffer[offset] !== 0xff) { offset += 1; continue }
      const marker = buffer[offset + 1]
      const length = buffer.readUInt16BE(offset + 2)
      if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
        return { height: buffer.readUInt16BE(offset + 5), width: buffer.readUInt16BE(offset + 7) }
      }
      if (length < 2) break
      offset += 2 + length
    }
  }
  throw reportServiceError('REPORT_RECEIPT_IMAGE_INVALID', 422, 'A receipt image is damaged. Replace it and try again.')
}

function fitImageDimensions(bytes, type, maxWidth = 450, maxHeight = 520) {
  const dimensions = imageDimensions(bytes, type)
  const scale = Math.min(maxWidth / dimensions.width, maxHeight / dimensions.height, 1)
  return { width: Math.round(dimensions.width * scale), height: Math.round(dimensions.height * scale) }
}

function detectImageKind(bytes, mimeType = '') {
  const buffer = Buffer.from(bytes)
  const isPng = buffer.length >= 8 && buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4e && buffer[3] === 0x47
  const isJpeg = buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff
  if (isPng) return 'png'
  if (isJpeg) return 'jpg'
  return null
}

function normalizePdfText(value, fontSets) {
  return Array.from(String(value ?? ''), (character) => {
    const codePoint = character.codePointAt(0)
    if (fontSets.latin.has(codePoint) || fontSets.tamil.has(codePoint) || fontSets.cjk.has(codePoint)) return character
    return `[U+${codePoint.toString(16).toUpperCase().padStart(4, '0')}]`
  }).join('')
}

function wrapText(value, maxChars = 48, fontSets = null) {
  const text = normalizePdfText(value, fontSets || { latin: new Set(), tamil: new Set(), cjk: new Set() })
  if (!text) return ['']
  const words = text.split(/\s+/).flatMap((word) => {
    if (word.length <= maxChars) return [word]
    const chunks = []
    for (let offset = 0; offset < word.length; offset += maxChars) chunks.push(word.slice(offset, offset + maxChars))
    return chunks
  })
  const lines = []
  let line = ''
  for (const word of words) {
    const next = line ? `${line} ${word}` : word
    if (next.length > maxChars && line) { lines.push(line); line = word } else line = next
  }
  if (line) lines.push(line)
  return lines.length ? lines : ['']
}

function wrapPdfText(value, maxWidth, fontSets, fonts, size) {
  const words = normalizePdfText(value, fontSets).split(/\s+/)
  const lines = []
  let line = ''
  const widthOf = (text) => textRuns(text, fontSets, fonts).reduce((total, run) => total + run.font.widthOfTextAtSize(run.text, size), 0)
  for (const word of words) {
    const units = Array.from(word)
    let part = ''
    const pieces = []
    for (const unit of units) {
      if (part && widthOf(part + unit) > maxWidth) { pieces.push(part); part = unit } else part += unit
    }
    if (part) pieces.push(part)
    for (const piece of pieces) {
      const next = line ? `${line} ${piece}` : piece
      if (line && widthOf(next) > maxWidth) { lines.push(line); line = piece } else line = next
    }
  }
  if (line || !lines.length) lines.push(line)
  return lines
}

function textRuns(value, fontSets, fonts) {
  const normalized = normalizePdfText(value, fontSets)
  const runs = []
  let text = ''
  let font = null
  for (const character of normalized) {
    const codePoint = character.codePointAt(0)
    const nextFont = codePoint >= 0x0b80 && codePoint <= 0x0bff
      ? fonts.tamil
      : (codePoint >= 0x2e80 && codePoint <= 0x9fff) || (codePoint >= 0xf900 && codePoint <= 0xfaff)
        ? fonts.cjk
        : fonts.latin
    if (font && font !== nextFont) { runs.push({ text, font }); text = '' }
    font = nextFont
    text += character
  }
  if (text) runs.push({ text, font })
  return runs
}

function drawPdfText(page, value, { x, y, size, fontSets, fonts, color }) {
  let cursor = x
  for (const run of textRuns(value, fontSets, fonts)) {
    page.drawText(run.text, { x: cursor, y, size, font: run.font, color })
    cursor += run.font.widthOfTextAtSize(run.text, size)
  }
  return cursor
}

async function buildPdf(rows, claim, receiptImages) {
  const { PDFDocument, rgb } = await import('pdf-lib')
  const fontkitModule = await import('@pdf-lib/fontkit')
  const fontkit = fontkitModule.default || fontkitModule
  const pdf = await PDFDocument.create()
  pdf.registerFontkit(fontkit)
  const fontDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../assets/fonts')
  const searchableText = [claim.title, claim.displayName, claim.status, ...rows.flatMap((row) => [row.description, row.note, row.categoryName, row.currency])].join(' ')
  const needsTamil = Array.from(searchableText).some((character) => character.codePointAt(0) >= 0x0b80 && character.codePointAt(0) <= 0x0bff)
  const needsCjk = Array.from(searchableText).some((character) => (character.codePointAt(0) >= 0x2e80 && character.codePointAt(0) <= 0x9fff) || (character.codePointAt(0) >= 0xf900 && character.codePointAt(0) <= 0xfaff))
  const [latinBytes, tamilBytes, cjkBytes] = await Promise.all([
    fs.readFile(path.join(fontDirectory, 'NotoSans-Regular.ttf')),
    needsTamil ? fs.readFile(path.join(fontDirectory, 'NotoSansTamil-Regular.ttf')) : null,
    needsCjk ? fs.readFile(path.join(fontDirectory, 'NotoSansSC-Regular.ttf')) : null,
  ])
  const latinSource = fontkit.create(latinBytes)
  const tamilSource = tamilBytes ? fontkit.create(tamilBytes) : null
  const cjkSource = cjkBytes ? fontkit.create(cjkBytes) : null
  const fontSets = { latin: new Set(latinSource.characterSet), tamil: new Set(tamilSource?.characterSet || []), cjk: new Set(cjkSource?.characterSet || []) }
  const latinFont = await pdf.embedFont(latinBytes, { subset: false })
  const fonts = {
    latin: latinFont,
    tamil: tamilBytes ? await pdf.embedFont(tamilBytes, { subset: true }) : latinFont,
    cjk: cjkBytes ? await pdf.embedFont(cjkBytes, { subset: false }) : latinFont,
  }
  const width = 842
  const height = 595
  const margin = 30
  const totalSgd = rows.reduce((sum, row) => sum + Number(row.sgdEquivalent || 0), 0)
  const currencyTotals = rows.reduce((totals, row) => {
    const code = String(row.currency || 'SGD').toUpperCase()
    totals[code] = (totals[code] || 0) + Number(row.amount || 0)
    return totals
  }, {})
  const summaryPages = []
  let page = pdf.addPage([width, height])
  summaryPages.push(page)
  let y = height - 38
  const newSummaryPage = () => { page = pdf.addPage([width, height]); summaryPages.push(page); y = height - 38 }
  const write = (text, size = 10, useBold = false, maxChars = 88) => {
    for (const line of wrapPdfText(text, width - margin * 2, fontSets, fonts, size)) {
      if (y < 55) newSummaryPage()
      drawPdfText(page, line, { x: margin, y, size, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) })
      y -= size + 8
    }
  }
  write(`Expense claim: ${claim.title}`, 18, true, 48)
  write(`Claimant: ${claim.displayName || 'Unknown'} | Status: ${claim.status || 'DRAFT'}`, 10)
  write(`Period: ${formatOptionalDate(claim.startDate)} to ${formatOptionalDate(claim.endDate)}`, 10)
  write(`Total S$ equivalent: ${totalSgd.toFixed(2)}`, 11, true)
  write(`Original currency totals: ${Object.entries(currencyTotals).map(([currency, amount]) => `${currency} ${amount.toFixed(2)}`).join(' | ') || 'None'}`, 9, false, 95)
  y -= 4
  const columns = [margin, 78, 130, 360, 485, 555, 635, 735]
  const headers = ['No.', 'Date', 'Description', 'Category', 'Currency', 'Amount', 'Rate', 'SGD']
  headers.forEach((header, index) => drawPdfText(page, header, { x: columns[index], y, size: 8, fontSets, fonts, color: rgb(0.05, 0.05, 0.05) }))
  y -= 18
  for (const [index, row] of rows.entries()) {
    const values = [String(index + 1), formatDate(row.receiptDate), row.description || row.note || '', row.categoryName || '', row.currency || '', Number(row.amount || 0).toFixed(2), Number(row.exchangeRate || 0).toFixed(6), Number(row.sgdEquivalent || 0).toFixed(2)]
    const descriptionLines = wrapPdfText(values[2], 220, fontSets, fonts, 8)
    const categoryLines = wrapPdfText(values[3], 118, fontSets, fonts, 8)
    const lineCount = Math.max(descriptionLines.length, categoryLines.length, 1)
    const rowHeight = lineCount * 10 + 7
    if (y - rowHeight < 42) { newSummaryPage(); headers.forEach((header, headerIndex) => drawPdfText(page, header, { x: columns[headerIndex], y, size: 8, fontSets, fonts, color: rgb(0.05, 0.05, 0.05) })); y -= 18 }
    drawPdfText(page, values[0], { x: columns[0], y, size: 8, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) })
    drawPdfText(page, values[1], { x: columns[1], y, size: 8, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) })
    descriptionLines.forEach((line, lineIndex) => drawPdfText(page, line, { x: columns[2], y: y - lineIndex * 10, size: 8, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) }))
    categoryLines.forEach((line, lineIndex) => drawPdfText(page, line, { x: columns[3], y: y - lineIndex * 10, size: 8, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) }))
    drawPdfText(page, values[4], { x: columns[4], y, size: 8, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) })
    drawPdfText(page, values[5], { x: columns[5], y, size: 8, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) })
    drawPdfText(page, values[6], { x: columns[6], y, size: 7, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) })
    drawPdfText(page, values[7], { x: columns[7], y, size: 8, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) })
    y -= rowHeight
  }
  write('Receipt appendix follows on the next pages.', 9, true)

  // Each receipt gets a dedicated page so images cannot overlap or disappear after pagination.
  for (const [index, receipt] of rows.entries()) {
    const appendixWidth = 595
    const appendixHeight = 842
    page = pdf.addPage([appendixWidth, appendixHeight])
    let receiptY = appendixHeight - 52
    const appendWrite = (text, size = 11, useBold = false, maxChars = 95) => {
      for (const line of wrapPdfText(text, appendixWidth - margin * 2, fontSets, fonts, size)) {
        drawPdfText(page, line, { x: margin, y: receiptY, size, fontSets, fonts, color: rgb(0.1, 0.1, 0.1) })
        receiptY -= size + 9
      }
    }
    appendWrite(`Receipt ${index + 1}: ${receipt.description || receipt.note || 'Receipt'}`, 15, true, 58)
    appendWrite(`Date: ${formatDate(receipt.receiptDate)} | Category: ${receipt.categoryName || ''}`, 10, false, 95)
    appendWrite(`Amount: ${receipt.currency || ''} ${Number(receipt.amount || 0).toFixed(2)} | Exchange rate: ${Number(receipt.exchangeRate || 0).toFixed(6)} | S$ ${Number(receipt.sgdEquivalent || 0).toFixed(2)}`, 10, false, 95)
    const imageData = receiptImages?.get(receipt.id) || await loadReceiptImage(receipt)
    const image = imageData.type === 'png' ? await pdf.embedPng(imageData.bytes) : await pdf.embedJpg(imageData.bytes)
    if (receiptY < 180) {
      page = pdf.addPage([appendixWidth, appendixHeight])
      receiptY = appendixHeight - 52
      appendWrite(`Receipt ${index + 1} image (continued)`, 12, true)
    }
    const availableImageHeight = Math.max(0, receiptY - 70)
    const scale = Math.min((appendixWidth - margin * 2) / image.width, availableImageHeight / image.height, 1)
    const imageHeight = image.height * scale
    page.drawImage(image, { x: margin, y: Math.max(60, receiptY - imageHeight - 10), width: image.width * scale, height: imageHeight })
  }
  return pdf.save()
}
async function saveReportFile(reportId, userId, claimId, fileType, extension, mimeType, data) {
  const storage = await saveBuffer(Buffer.from(data), { key: createStorageKey(`reports/${userId}/${claimId}`, extension), contentType: mimeType })
  const [file] = await db.insert(reportFiles).values({ reportId, fileType, storageKey: storage.key, fileName: `${fileType.toLowerCase()}-${claimId}.${extension}`, mimeType }).returning({ id: reportFiles.id, reportId: reportFiles.reportId, fileType: reportFiles.fileType, storageKey: reportFiles.storageKey, fileName: reportFiles.fileName, mimeType: reportFiles.mimeType, createdAt: reportFiles.createdAt })
  return file
}

function reportServiceError(code, statusCode, message) {
  const error = new Error(message)
  error.code = code
  error.statusCode = statusCode
  return error
}

async function generateClaimReportOnce({ userId, claimId }) {
  const { claim, rows } = await claimRows(userId, claimId)
  const activeSince = new Date(Date.now() - ACTIVE_GENERATION_WINDOW_MS)
  const [activeReport] = await db.select({ id: reports.id }).from(reports)
    .where(and(eq(reports.userId, userId), eq(reports.claimId, claimId), eq(reports.status, 'GENERATING'), gte(reports.createdAt, activeSince)))
    .limit(1)
  if (activeReport) throw reportServiceError('REPORT_GENERATION_IN_PROGRESS', 503, 'Report generation is already in progress.')
  const [report] = await db.insert(reports).values({ userId, claimId, status: 'GENERATING' }).returning({ id: reports.id, userId: reports.userId, claimId: reports.claimId, status: reports.status, createdAt: reports.createdAt, completedAt: reports.completedAt, errorMessage: reports.errorMessage })
  const files = []
  try {
    const receiptImages = await collectReceiptImages(rows)
    const [xlsx, docx, pdf] = await Promise.all([buildExcel(rows, claim), buildDocx(rows, claim, receiptImages), buildPdf(rows, claim, receiptImages)])
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
    const publicMessage = error.code === 'REPORT_TEMPLATE_INVALID'
      ? 'The configured reimbursement template is invalid. Please contact support.'
      : error.code === 'REPORT_TEMPLATE_UNAVAILABLE'
        ? 'The configured reimbursement template is unavailable. Please try again later.'
        : error.code === 'REPORT_RECEIPT_IMAGE_INVALID'
          ? error.message
          : error.code === 'REPORT_RECEIPT_IMAGE_UNAVAILABLE'
            ? error.message
          : error.code === 'REPORT_CLAIM_TOO_LARGE'
            ? error.message
        : 'Report generation failed.'
    await db.update(reports).set({ status: 'FAILED', errorMessage: publicMessage }).where(eq(reports.id, report.id))
    if (['REPORT_TEMPLATE_INVALID', 'REPORT_TEMPLATE_UNAVAILABLE', 'REPORT_RECEIPT_IMAGE_INVALID', 'REPORT_RECEIPT_IMAGE_UNAVAILABLE', 'REPORT_CLAIM_TOO_LARGE'].includes(error.code)) {
      throw reportServiceError(error.code, error.statusCode || (['REPORT_TEMPLATE_INVALID', 'REPORT_RECEIPT_IMAGE_INVALID'].includes(error.code) ? 422 : 503), publicMessage)
    }
    throw reportServiceError('REPORT_GENERATION_FAILED', 500, 'Report generation failed.')
  }
}

export function generateClaimReport({ userId, claimId }) {
  const key = `${userId}:${claimId}`
  const active = activeGenerations.get(key)
  if (active) return active
  const generation = generateClaimReportOnce({ userId, claimId })
  activeGenerations.set(key, generation)
  generation.finally(() => { if (activeGenerations.get(key) === generation) activeGenerations.delete(key) }).catch(() => {})
  return generation
}

async function reportDto(report) {
  if (!report) return null
  const files = await db.select({ id: reportFiles.id, reportId: reportFiles.reportId, fileType: reportFiles.fileType, storageKey: reportFiles.storageKey, fileName: reportFiles.fileName, mimeType: reportFiles.mimeType, createdAt: reportFiles.createdAt }).from(reportFiles).where(eq(reportFiles.reportId, report.id))
  return { report, files }
}

export async function getReport(userId, reportId) {
  const [report] = await db.select({ id: reports.id, userId: reports.userId, claimId: reports.claimId, status: reports.status, createdAt: reports.createdAt, completedAt: reports.completedAt, errorMessage: reports.errorMessage }).from(reports).where(and(eq(reports.id, reportId), eq(reports.userId, userId))).limit(1)
  return reportDto(report)
}

export async function getLatestClaimReport(userId, claimId) {
  const [report] = await db.select({ id: reports.id, userId: reports.userId, claimId: reports.claimId, status: reports.status, createdAt: reports.createdAt, completedAt: reports.completedAt, errorMessage: reports.errorMessage }).from(reports)
    .where(and(eq(reports.userId, userId), eq(reports.claimId, claimId)))
    .orderBy(desc(reports.createdAt), desc(reports.id)).limit(1)
  return reportDto(report)
}

export const reportService = { generateClaimReport, getReport, getLatestClaimReport }
export const reportInternals = { buildExcel, buildPdf, buildDocx, imageDimensions, fitImageDimensions, detectImageKind, locateTemplate, populateTemplate, claimRows, assertReportImageBudget }
