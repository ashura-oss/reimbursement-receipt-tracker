import fs from 'node:fs'
import path from 'node:path'
import { eq } from 'drizzle-orm'
import { randomUUID } from 'node:crypto'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

process.env.NODE_ENV = 'test'
process.env.DATABASE_PROVIDER = 'sqlite'
const databaseFile = `claim-model-${randomUUID()}.db`
process.env.DATABASE_URL = `file:${databaseFile}`
const databasePath = path.resolve(databaseFile)
try { fs.rmSync(databasePath, { force: true }) } catch {}

const [{ db, client, closeDatabase }, schema, claimModel] = await Promise.all([
  import('../db/db.js'),
  import('../db/schema.js'),
  import('./claimModel.js'),
])
const { createReceipt, updateReceipt, deleteReceipt } = await import('./receiptModel.js')

const createTables = async () => {
  await client.execute('PRAGMA foreign_keys = ON')
  const statements = [
    `CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'USER', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
    `CREATE TABLE receipt_categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)`,
    `CREATE TABLE receipts (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id), client_receipt_id TEXT, category_id INTEGER NOT NULL REFERENCES receipt_categories(id), receipt_date INTEGER NOT NULL, amount REAL NOT NULL, currency TEXT NOT NULL DEFAULT 'SGD', exchange_rate REAL NOT NULL DEFAULT 1, sgd_equivalent REAL NOT NULL, description TEXT, note TEXT, storage_key TEXT, original_file_name TEXT, mime_type TEXT, status TEXT NOT NULL DEFAULT 'UNCLAIMED', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
    `CREATE UNIQUE INDEX receipts_client_receipt_unique_idx ON receipts(user_id, client_receipt_id)`,
    `CREATE TABLE claims (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id), title TEXT NOT NULL, start_date INTEGER, end_date INTEGER, status TEXT NOT NULL DEFAULT 'DRAFT', total_amount REAL NOT NULL DEFAULT 0, total_sgd_equivalent REAL NOT NULL DEFAULT 0, submitted_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
    `CREATE TABLE claim_receipts (id INTEGER PRIMARY KEY AUTOINCREMENT, claim_id INTEGER NOT NULL REFERENCES claims(id), receipt_id INTEGER NOT NULL REFERENCES receipts(id), created_at INTEGER NOT NULL)`,
    `CREATE UNIQUE INDEX claim_receipt_unique_idx ON claim_receipts(claim_id, receipt_id)`,
    `CREATE UNIQUE INDEX claim_receipt_receipt_unique_idx ON claim_receipts(receipt_id)`,
  ]
  for (const statement of statements) await client.execute(statement)
}

describe('claim receipt exclusivity', () => {
  let ownerId
  let otherUserId
  let firstReceiptId
  let otherReceiptId

  beforeAll(async () => {
    await createTables()
    const now = new Date()
    const owners = await db.insert(schema.users).values([
      { username: 'claim-owner', displayName: 'Claim Owner', passwordHash: 'test', createdAt: now, updatedAt: now },
      { username: 'other-owner', displayName: 'Other Owner', passwordHash: 'test', createdAt: now, updatedAt: now },
    ]).returning({ id: schema.users.id })
    ownerId = owners[0].id
    otherUserId = owners[1].id
    const categories = await db.insert(schema.receiptCategories).values({ name: 'Testing', active: true, createdAt: now }).returning({ id: schema.receiptCategories.id })
    const receipts = await db.insert(schema.receipts).values([
      { userId: ownerId, categoryId: categories[0].id, receiptDate: now, amount: 10, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 10, status: 'UNCLAIMED', createdAt: now, updatedAt: now },
      { userId: otherUserId, categoryId: categories[0].id, receiptDate: now, amount: 20, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 20, status: 'UNCLAIMED', createdAt: now, updatedAt: now },
    ]).returning({ id: schema.receipts.id })
    firstReceiptId = receipts[0].id
    otherReceiptId = receipts[1].id
  })

  it('allows a receipt in one claim and rejects re-use in another claim', async () => {
    await db.transaction((tx) => claimModel.createClaimWithReceipts({ userId: ownerId, title: 'First claim', receiptIds: [firstReceiptId] }, tx))
    await expect(db.transaction((tx) => claimModel.createClaimWithReceipts({ userId: ownerId, title: 'Duplicate claim', receiptIds: [firstReceiptId] }, tx))).rejects.toThrow('RECEIPT_ALREADY_CLAIMED')
    expect(await updateReceipt(firstReceiptId, ownerId, { amount: 999 })).toBeNull()
    expect(await deleteReceipt(firstReceiptId, ownerId)).toBe(false)
  })

  it('does not allow a user to claim another user\'s receipt', async () => {
    await expect(db.transaction((tx) => claimModel.createClaimWithReceipts({ userId: ownerId, title: 'Wrong owner', receiptIds: [otherReceiptId] }, tx))).rejects.toThrow('RECEIPT_OWNERSHIP_MISMATCH')
  })

  it('returns the original row for a retried client receipt id', async () => {
    const now = new Date()
    const first = await createReceipt({ userId: ownerId, categoryId: 1, clientReceiptId: 'android-retry-1', receiptDate: now, amount: 7, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 7, status: 'UNCLAIMED', createdAt: now, updatedAt: now })
    const retry = await createReceipt({ userId: ownerId, categoryId: 1, clientReceiptId: 'android-retry-1', receiptDate: now, amount: 99, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 99, status: 'UNCLAIMED', createdAt: now, updatedAt: now })
    expect(retry.id).toBe(first.id)
    expect(retry.amount).toBe(7)
  })

  it('keeps concurrent client receipt retries idempotent', async () => {
    const now = new Date()
    const payload = { userId: ownerId, categoryId: 1, clientReceiptId: 'android-race-1', receiptDate: now, amount: 8, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 8, status: 'UNCLAIMED', createdAt: now, updatedAt: now }
    const attempts = await Promise.allSettled([createReceipt(payload), createReceipt(payload)])
    expect(attempts.some((attempt) => attempt.status === 'fulfilled')).toBe(true)
    const rows = await db.select({ id: schema.receipts.id }).from(schema.receipts).where(eq(schema.receipts.clientReceiptId, 'android-race-1'))
    expect(rows).toHaveLength(1)
  })
})

afterAll(async () => {
  await closeDatabase()
  for (const file of [databasePath, `${databasePath}-shm`, `${databasePath}-wal`]) {
    try { fs.rmSync(file, { force: true }) } catch {}
  }
})
