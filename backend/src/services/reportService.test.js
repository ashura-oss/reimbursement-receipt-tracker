import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { PDFDocument } from 'pdf-lib'
import { buildTestPng, buildTestJpeg } from './testImageFixtures.js'
import { randomUUID } from 'node:crypto'

const testRunId = randomUUID()
const databaseFile = path.join(os.tmpdir(), `report-service-${testRunId}.db`)
const storageDirectory = path.join(os.tmpdir(), `report-service-storage-${testRunId}`)
process.env.NODE_ENV = 'test'
process.env.DATABASE_PROVIDER = 'sqlite'
process.env.DATABASE_URL = `file:${databaseFile.replaceAll('\\', '/')}`
process.env.STORAGE_PROVIDER = 'local'
process.env.STORAGE_DIR = storageDirectory

const [{ reportInternals }, database, schema, storage] = await Promise.all([
  import('./reportService.js'),
  import('../db/db.js'),
  import('../db/schema.js'),
  import('./storageService.js'),
])
const { db, client, closeDatabase } = database
const { removeFile, saveBuffer } = storage

const baseReceipt = (id, storageKey, mimeType) => ({ id, receiptDate: new Date('2026-10-11T00:00:00Z'), amount: 10 + id, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 10 + id, description: `Receipt ${id} with a long description that wraps`, categoryName: 'IT', storageKey, mimeType })
const claim = { title: 'Test claim', status: 'DRAFT', displayName: 'Alice Example', startDate: new Date('2026-10-01T00:00:00Z'), endDate: new Date('2026-10-11T00:00:00Z') }
let originalTemplatePath
let originalTemplateStorageKey
let otherUserId
let ownerClaimId
const cleanupKeys = []
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

async function createOwnershipTables() {
  await client.execute(`CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'USER',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  )`)
  await client.execute(`CREATE TABLE claims (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    title TEXT NOT NULL,
    start_date INTEGER,
    end_date INTEGER,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    total_amount REAL NOT NULL DEFAULT 0,
    total_sgd_equivalent REAL NOT NULL DEFAULT 0,
    submitted_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  )`)
  const now = new Date()
  const people = await db.insert(schema.users).values([
    { username: `report-owner-${testRunId}`, displayName: 'Report Owner', passwordHash: 'unused', createdAt: now, updatedAt: now },
    { username: `report-other-${testRunId}`, displayName: 'Other User', passwordHash: 'unused', createdAt: now, updatedAt: now },
  ]).returning({ id: schema.users.id })
  otherUserId = people[1].id
  const [ownerClaim] = await db.insert(schema.claims).values({
    userId: people[0].id,
    title: 'Owned fixture claim',
    status: 'DRAFT',
    createdAt: now,
    updatedAt: now,
  }).returning({ id: schema.claims.id })
  ownerClaimId = ownerClaim.id
}

beforeAll(async () => {
  await createOwnershipTables()
})

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

  it('does not total mixed-currency receipt amounts while keeping each receipt and SGD total', async () => {
    const { workbook } = await makeTemplate()
    const templateSheet = workbook.worksheets[0]
    // Match the private Vadi template: this summary is a valid SGD-equivalent
    // SUMIF over column O and must survive even when source currencies differ.
    templateSheet.getCell('K10').value = { formula: 'SUMIF($C$21:$C$49,H10,$O$21:$O$49)' }
    templateSheet.getCell('K50').value = { formula: 'SUM(K21:K49)' }
    const templatePath = temporaryTemplatePath('mixed-currency-template')
    await workbook.xlsx.writeFile(templatePath)
    process.env.EXCEL_TEMPLATE_PATH = templatePath
    const rows = [
      { ...baseReceipt(1, null, null), amount: 12.34, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 12.34 },
      { ...baseReceipt(2, null, null), amount: 100, currency: 'INR', exchangeRate: 0.016, sgdEquivalent: 1.6 },
    ]
    const bytes = await reportInternals.buildExcel(rows, claim)
    const ExcelJS = (await import('exceljs')).default
    const output = new ExcelJS.Workbook(); await output.xlsx.load(bytes)
    const sheet = output.worksheets[0]
    expect(sheet.getCell('K21').value).toBe(12.34)
    expect(sheet.getCell('M21').value).toBe('SGD')
    expect(sheet.getCell('K22').value).toBe(100)
    expect(sheet.getCell('M22').value).toBe('INR')
    expect(sheet.getCell('K10').formula).toBe('SUMIF($C$21:$C$49,H10,$O$21:$O$49)')
    expect(sheet.getCell('K50').value).toBeNull()
    expect(sheet.getCell('O50').value.formula).toBe('SUM(O21:O49)')
    await fs.rm(templatePath, { force: true })
  })

  it('keeps the original-amount total when every receipt uses the same currency', async () => {
    const { workbook } = await makeTemplate()
    const templatePath = temporaryTemplatePath('single-currency-template')
    await workbook.xlsx.writeFile(templatePath)
    process.env.EXCEL_TEMPLATE_PATH = templatePath
    const rows = [
      { ...baseReceipt(1, null, null), amount: 12.34, currency: 'SGD', sgdEquivalent: 12.34 },
      { ...baseReceipt(2, null, null), amount: 5.66, currency: 'SGD', sgdEquivalent: 5.66 },
    ]
    const bytes = await reportInternals.buildExcel(rows, claim)
    const ExcelJS = (await import('exceljs')).default
    const output = new ExcelJS.Workbook(); await output.xlsx.load(bytes)
    const sheet = output.worksheets[0]
    expect(sheet.getCell('K21').value).toBe(12.34)
    expect(sheet.getCell('K22').value).toBe(5.66)
    expect(sheet.getCell('K10').value.formula).toBe('SUM(K21:K49)')
    expect(sheet.getCell('K50').value.formula).toBe('SUM(K21:K49)')
    expect(sheet.getCell('O50').value.formula).toBe('SUM(O21:O49)')
    await fs.rm(templatePath, { force: true })
  })

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
  await Promise.all(cleanupKeys.splice(0).map(removeFile))
  await closeDatabase()
  await Promise.all([databaseFile, `${databaseFile}-shm`, `${databaseFile}-wal`].map((file) => fs.rm(file, { force: true }).catch(() => {})))
  await fs.rm(storageDirectory, { recursive: true, force: true }).catch(() => {})
})

describe('report ownership', () => {
  it('rejects a claim that is not owned by the caller', async () => {
    await expect(reportInternals.claimRows(otherUserId, ownerClaimId)).rejects.toMatchObject({ statusCode: 404, message: 'Claim not found' })
  })
})
