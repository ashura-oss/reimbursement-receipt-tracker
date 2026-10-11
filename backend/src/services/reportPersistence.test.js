import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import request from 'supertest'

process.env.NODE_ENV = 'test'
process.env.JWT_SECRET = 'report-persistence-test-secret'
process.env.DATABASE_PROVIDER = 'sqlite'
const testRunId = randomUUID()
const databaseFile = path.join(os.tmpdir(), `report-persistence-${testRunId}.db`)
const storageDirectory = path.join(os.tmpdir(), `report-storage-${testRunId}`)
const invalidTemplatePath = path.join(os.tmpdir(), `invalid-report-template-${testRunId}.xlsx`)
const missingTemplatePath = path.join(os.tmpdir(), `missing-report-template-${testRunId}.xlsx`)
process.env.DATABASE_URL = `file:${databaseFile.replaceAll('\\', '/')}`
process.env.STORAGE_DIR = storageDirectory
delete process.env.EXCEL_TEMPLATE_PATH
delete process.env.EXCEL_TEMPLATE_STORAGE_KEY

const [{ db, client, closeDatabase }, schema, { reportService }, { default: app }, { signToken }] = await Promise.all([
  import('../db/db.js'),
  import('../db/schema.js'),
  import('./reportService.js'),
  import('../../index.js'),
  import('../middlewares/authMiddleware.js'),
])

async function createTables() {
  await client.execute('PRAGMA foreign_keys = ON')
  const statements = [
    `CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'USER', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
    `CREATE TABLE receipt_categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)`,
    `CREATE TABLE receipts (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id), client_receipt_id TEXT, category_id INTEGER NOT NULL REFERENCES receipt_categories(id), receipt_date INTEGER NOT NULL, amount REAL NOT NULL, currency TEXT NOT NULL DEFAULT 'SGD', exchange_rate REAL NOT NULL DEFAULT 1, sgd_equivalent REAL NOT NULL, description TEXT, note TEXT, storage_key TEXT, original_file_name TEXT, mime_type TEXT, status TEXT NOT NULL DEFAULT 'UNCLAIMED', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
    `CREATE TABLE claims (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id), title TEXT NOT NULL, start_date INTEGER, end_date INTEGER, status TEXT NOT NULL DEFAULT 'DRAFT', total_amount REAL NOT NULL DEFAULT 0, total_sgd_equivalent REAL NOT NULL DEFAULT 0, submitted_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
    `CREATE TABLE claim_receipts (id INTEGER PRIMARY KEY AUTOINCREMENT, claim_id INTEGER NOT NULL REFERENCES claims(id), receipt_id INTEGER NOT NULL REFERENCES receipts(id), created_at INTEGER NOT NULL)`,
    `CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id), claim_id INTEGER NOT NULL REFERENCES claims(id), status TEXT NOT NULL DEFAULT 'GENERATING', created_at INTEGER NOT NULL, completed_at INTEGER, error_message TEXT)`,
    `CREATE TABLE report_files (id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL REFERENCES reports(id), file_type TEXT NOT NULL, storage_key TEXT NOT NULL, file_name TEXT NOT NULL, mime_type TEXT NOT NULL, created_at INTEGER NOT NULL)`,
    `CREATE TABLE audit_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, actor_user_id INTEGER REFERENCES users(id), action TEXT NOT NULL, resource_type TEXT NOT NULL, resource_id INTEGER, metadata TEXT, created_at INTEGER NOT NULL)`,
  ]
  for (const statement of statements) await client.execute(statement)
}

let ownerId
let claimId
let ownerToken
let otherToken

beforeAll(async () => {
  await createTables()
  const now = new Date()
  const insertedUsers = await db.insert(schema.users).values([
    { username: 'report-owner', displayName: 'Report Owner', passwordHash: 'test', createdAt: now, updatedAt: now },
    { username: 'report-other', displayName: 'Other User', passwordHash: 'test', createdAt: now, updatedAt: now },
  ]).returning({ id: schema.users.id, username: schema.users.username })
  ownerId = insertedUsers[0].id
  ownerToken = signToken({ userId: insertedUsers[0].id, username: insertedUsers[0].username, role: 'USER' })
  otherToken = signToken({ userId: insertedUsers[1].id, username: insertedUsers[1].username, role: 'USER' })
  const [claim] = await db.insert(schema.claims).values({ userId: ownerId, title: 'October expenses', status: 'DRAFT', createdAt: now, updatedAt: now }).returning({ id: schema.claims.id })
  claimId = claim.id
})

describe('claim report persistence', () => {
  it('deduplicates concurrent generation and returns the latest ReportDto when the app reopens', async () => {
    const [first, concurrent] = await Promise.all([
      reportService.generateClaimReport({ userId: ownerId, claimId }),
      reportService.generateClaimReport({ userId: ownerId, claimId }),
    ])
    expect(first.report.id).toBe(concurrent.report.id)
    expect(first.report.status).toBe('COMPLETED')
    expect(first.files).toHaveLength(3)

    const response = await request(app).get(`/api/reports/claims/${claimId}/latest`).set('Authorization', `Bearer ${ownerToken}`)
    expect(response.status).toBe(200)
    expect(response.body.data.report.id).toBe(first.report.id)
    expect(response.body.data.files).toHaveLength(3)
  })

  it('does not expose a claim report to another authenticated user', async () => {
    const response = await request(app).get(`/api/reports/claims/${claimId}/latest`).set('Authorization', `Bearer ${otherToken}`)
    expect(response.status).toBe(404)
  })

  it('returns typed client errors for invalid and unavailable configured templates', async () => {
    const ExcelJS = (await import('exceljs')).default
    const invalidWorkbook = new ExcelJS.Workbook()
    invalidWorkbook.addWorksheet('not a claim template')
    const invalidPath = invalidTemplatePath
    await invalidWorkbook.xlsx.writeFile(invalidPath)
    const originalTemplate = process.env.EXCEL_TEMPLATE_PATH
    try {
      process.env.EXCEL_TEMPLATE_PATH = invalidPath
      const invalid = await request(app).post(`/api/reports/claims/${claimId}`).set('Authorization', `Bearer ${ownerToken}`)
      expect(invalid.status).toBe(422)
      expect(invalid.body.error).toMatch(/template is invalid/i)

      process.env.EXCEL_TEMPLATE_PATH = missingTemplatePath
      const unavailable = await request(app).post(`/api/reports/claims/${claimId}`).set('Authorization', `Bearer ${ownerToken}`)
      expect(unavailable.status).toBe(503)
      expect(unavailable.body.error).toMatch(/template is unavailable/i)
    } finally {
      if (originalTemplate === undefined) delete process.env.EXCEL_TEMPLATE_PATH
      else process.env.EXCEL_TEMPLATE_PATH = originalTemplate
      await fs.rm(invalidPath, { force: true })
      await fs.rm(missingTemplatePath, { force: true })
    }
  })

  it('marks a report failed without files when a receipt image is missing', async () => {
    const now = new Date()
    const [category] = await db.insert(schema.receiptCategories).values({ name: 'Report persistence test', active: true, createdAt: now }).returning({ id: schema.receiptCategories.id })
    const [receipt] = await db.insert(schema.receipts).values({ userId: ownerId, categoryId: category.id, receiptDate: now, amount: 25, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 25, description: 'Missing image', storageKey: null, status: 'CLAIMED', createdAt: now, updatedAt: now }).returning({ id: schema.receipts.id })
    const [claim] = await db.insert(schema.claims).values({ userId: ownerId, title: 'Missing image test', status: 'DRAFT', createdAt: now, updatedAt: now }).returning({ id: schema.claims.id })
    await db.insert(schema.claimReceipts).values({ claimId: claim.id, receiptId: receipt.id, createdAt: now })

    const response = await request(app).post(`/api/reports/claims/${claim.id}`).set('Authorization', `Bearer ${ownerToken}`)
    expect(response.status).toBe(422)
    const latest = await reportService.getLatestClaimReport(ownerId, claim.id)
    expect(latest.report.status).toBe('FAILED')
    expect(latest.files).toHaveLength(0)
  })
})

afterAll(async () => {
  await closeDatabase()
  await Promise.all([databaseFile, `${databaseFile}-shm`, `${databaseFile}-wal`, invalidTemplatePath, missingTemplatePath].map((file) => fs.rm(file, { force: true }).catch(() => {})))
  await fs.rm(storageDirectory, { recursive: true, force: true })
})
