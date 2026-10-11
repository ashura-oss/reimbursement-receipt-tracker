import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { afterAll, afterEach, beforeEach, describe, expect, it } from 'vitest'
import { PDFDocument } from 'pdf-lib'
import { buildTestPng, buildTestJpeg } from './testImageFixtures.js'
import { reportInternals } from './reportService.js'
import { getStorageProvider, getStorageRoot, removeFile, saveBuffer } from './storageService.js'

const baseReceipt = (id, storageKey, mimeType) => ({ id, receiptDate: new Date('2026-10-11T00:00:00Z'), amount: 10 + id, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 10 + id, description: `Receipt ${id} with a long description that wraps`, categoryName: 'IT', storageKey, mimeType })
const claim = { title: 'Test claim', status: 'DRAFT', displayName: 'Alice Example', startDate: new Date('2026-10-01T00:00:00Z'), endDate: new Date('2026-10-11T00:00:00Z') }
let originalTemplatePath
let originalTemplateStorageKey
const cleanupKeys = []
const testRunId = randomUUID()
const temporaryTemplateFiles = new Set()
const temporaryTemplatePath = (name) => {
  const file = path.join(os.tmpdir(), `${name}-${testRunId}.xlsx`)
  temporaryTemplateFiles.add(file)
  return file
}

async function makeTemplate() {
  const ExcelJS = (await import('exceljs')).default
  const workbook = new ExcelJS.Workbook()
  const sheet = workbook.addWorksheet('sheet 1')
  sheet.getCell('A5').value = 'Name of Staff : Vadivelan'
  sheet.getCell('A6').value = 'Approved by: Firdaus J'
  sheet.getCell('A4').value = 'Department : Service'
  sheet.getCell('A7').value = 'Position :'
  sheet.getCell('B7').value = 'Service Engineer'
  sheet.getRow(20).values = ['No', 'Date', 'Accounts Code', '', '', '', '', 'Description', '', '', 'Amount Excluding GST', 'GST (if applicable)', 'INR', 'Exchange Rate', 'S$ Equivalent', '', '', '', 'Cost centre 2']
  sheet.getCell('M55').value = 'DATE:'
  sheet.getCell('N55').value = new Date('2026-10-02T00:00:00Z')
  sheet.getCell('K10').value = { formula: 'SUM(K21:K49)' }
  sheet.getCell('A50').value = 'Total'
  sheet.getCell('O52').value = { formula: 'O50+O51' }
  return { workbook, sheet }
}

beforeEach(() => {
  originalTemplatePath = process.env.EXCEL_TEMPLATE_PATH
  originalTemplateStorageKey = process.env.EXCEL_TEMPLATE_STORAGE_KEY
  delete process.env.EXCEL_TEMPLATE_PATH
  delete process.env.EXCEL_TEMPLATE_STORAGE_KEY
})
afterEach(async () => {
  if (originalTemplatePath === undefined) delete process.env.EXCEL_TEMPLATE_PATH; else process.env.EXCEL_TEMPLATE_PATH = originalTemplatePath
  if (originalTemplateStorageKey === undefined) delete process.env.EXCEL_TEMPLATE_STORAGE_KEY; else process.env.EXCEL_TEMPLATE_STORAGE_KEY = originalTemplateStorageKey
  await Promise.all(cleanupKeys.splice(0).map(removeFile))
})

describe('report internals', () => {
  it('embeds valid PNG and JPG receipt images in a summary plus appendix PDF', async () => {
    const png = await saveBuffer(buildTestPng(), { key: `test-report/${testRunId}/png.png`, contentType: 'image/png' })
    const jpg = await saveBuffer(buildTestJpeg(), { key: `test-report/${testRunId}/jpg.jpg`, contentType: 'image/jpeg' })
    cleanupKeys.push(png.key, jpg.key)
    const bytes = await reportInternals.buildPdf([baseReceipt(1, png.key, 'image/png'), baseReceipt(2, jpg.key, 'image/jpeg')], claim)
    const pdf = await PDFDocument.load(bytes)
    expect(pdf.getPageCount()).toBe(3)
    expect(pdf.getPage(0).getSize()).toMatchObject({ width: 842, height: 595 })
    expect(pdf.getPage(1).getSize()).toMatchObject({ width: 595, height: 842 })
    expect(bytes.length).toBeGreaterThan(1500)
    expect(reportInternals.detectImageKind(buildTestPng(), 'image/octet-stream')).toBe('png')
    expect(reportInternals.detectImageKind(buildTestJpeg(), 'image/octet-stream')).toBe('jpg')
  })

  it('keeps multilingual text readable, handles large amounts, and wraps long categories without collisions', async () => {
    const png = await saveBuffer(buildTestPng(), { key: `test-report/${testRunId}/unicode.png`, contentType: 'image/png' })
    cleanupKeys.push(png.key)
    const rows = [{
      ...baseReceipt(9, png.key, 'image/png'),
      description: 'செலவு 收据 😀 international travel claim with an unusually long description that should wrap within the summary table',
      categoryName: 'பொழுதுபோக்கு 娱乐 category with a long category label that wraps within its own column',
      currency: 'INR',
      amount: 1000000000,
    }]
    const bytes = await reportInternals.buildPdf(rows, { ...claim, title: 'செலவு claim 收据 😀' })
    const pdf = await PDFDocument.load(bytes)
    expect(pdf.getPageCount()).toBe(2)
    expect(pdf.getPage(0).getSize()).toMatchObject({ width: 842, height: 595 })
    expect(bytes.length).toBeGreaterThan(10000)
  })

  it('preserves portrait and landscape receipt image aspect ratios for the Word export', async () => {
    const sharp = (await import('sharp')).default
    for (const { width, height } of [{ width: 600, height: 1200 }, { width: 1200, height: 600 }]) {
      const bytes = await sharp({ create: { width, height, channels: 3, background: '#ffffff' } }).png().toBuffer()
      const fitted = reportInternals.fitImageDimensions(bytes, 'png')
      expect(fitted.width / fitted.height).toBeCloseTo(width / height)
      expect(fitted.width).toBeLessThanOrEqual(450)
      expect(fitted.height).toBeLessThanOrEqual(520)
    }
  })

  it('rejects receipt image aggregates above the report memory budget', () => {
    expect(() => reportInternals.assertReportImageBudget(40, 11, 50)).toThrowError(expect.objectContaining({ code: 'REPORT_CLAIM_TOO_LARGE', statusCode: 422 }))
    expect(() => reportInternals.assertReportImageBudget(40, 10, 50)).not.toThrow()
  })

  it('expands the template data region for claims larger than the sample capacity and removes sample identity', async () => {
    const { workbook } = await makeTemplate()
    const templatePath = temporaryTemplatePath('receipt-template')
    await workbook.xlsx.writeFile(templatePath)
    process.env.EXCEL_TEMPLATE_PATH = templatePath
    const rows = Array.from({ length: 30 }, (_, index) => baseReceipt(index + 1, null, null))
    const bytes = await reportInternals.buildExcel(rows, claim)
    const ExcelJS = (await import('exceljs')).default
    const output = new ExcelJS.Workbook(); await output.xlsx.load(bytes)
    const sheet = output.worksheets[0]
    expect(sheet.getCell('A21').value).toBe(1)
    expect(sheet.getCell('A50').value).toBe(30)
    expect(sheet.getCell('A51').value).toBe('Total')
    expect(sheet.getCell('K20').value).toBe('Receipt Amount (as paid)')
    expect(sheet.getCell('M20').value).toBe('Currency')
    expect(sheet.getCell('L21').value).toBeNull()
    expect(sheet.getCell('N55').value).toBeNull()
    expect(sheet.getCell('K10').value.formula).toBe('SUM(K21:K50)')
    expect(sheet.getCell('O53').value.formula).toBe('O51+O52')
    expect(JSON.stringify(sheet.getRow(5).values)).not.toContain('Vadivelan')
    expect(JSON.stringify(sheet.getRow(6).values)).not.toContain('Firdaus J')
    expect(JSON.stringify(sheet.getRow(4).values)).not.toContain('Department : Service')
    expect(JSON.stringify(sheet.getRow(7).values)).not.toContain('Service Engineer')
    expect(JSON.stringify(sheet.getRow(5).values)).toContain('Alice Example')
    await fs.rm(templatePath, { force: true })
  }, 15000)

  it('fails clearly when a configured private template cannot be read', async () => {
    process.env.EXCEL_TEMPLATE_STORAGE_KEY = 'private/missing-template.xlsx'
    await expect(reportInternals.buildExcel([], claim)).rejects.toMatchObject({ code: 'REPORT_TEMPLATE_UNAVAILABLE' })
  })

  it('fails clearly when a configured workbook has no claim table', async () => {
    const ExcelJS = (await import('exceljs')).default
    const workbook = new ExcelJS.Workbook()
    workbook.addWorksheet('unrelated')
    const templatePath = temporaryTemplatePath('invalid-receipt-template')
    await workbook.xlsx.writeFile(templatePath)
    process.env.EXCEL_TEMPLATE_PATH = templatePath
    await expect(reportInternals.buildExcel([], claim)).rejects.toMatchObject({ code: 'REPORT_TEMPLATE_INVALID' })
    await fs.rm(templatePath, { force: true })
  })
})

afterAll(async () => {
  await Promise.all([...temporaryTemplateFiles].map((file) => fs.rm(file, { force: true }).catch(() => {})))
  if (getStorageProvider() === 'local') {
    await fs.rm(path.join(getStorageRoot(), 'test-report', testRunId), { recursive: true, force: true }).catch(() => {})
  }
})

describe('report ownership', () => {
  it('rejects a claim that is not owned by the caller', async () => {
    await expect(reportInternals.claimRows(999999, 1)).rejects.toMatchObject({ statusCode: 404, message: 'Claim not found' })
  })
})
