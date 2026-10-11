import { afterAll, describe, expect, it } from 'vitest'
import request from 'supertest'
import fs from 'node:fs'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { eq } from 'drizzle-orm'
import { buildTestPng } from '../services/testImageFixtures.js'

process.env.NODE_ENV = 'test'
process.env.JWT_SECRET = 'test-only-secret'
process.env.ADMIN_PORTAL_ENABLED = 'false'
process.env.DATABASE_PROVIDER = 'sqlite'
const testRunId = randomUUID()
const databaseFile = `upload-security-${testRunId}.db`
process.env.DATABASE_URL = `file:${databaseFile}`

const [{ default: app }, { signToken }, database, schema, storage] = await Promise.all([
  import('../../index.js'),
  import('../middlewares/authMiddleware.js'),
  import('../db/db.js'),
  import('../db/schema.js'),
  import('../services/storageService.js'),
])

const token = signToken({ userId: 1, username: 'tester', role: 'USER' })
const otherToken = signToken({ userId: 2, username: 'other', role: 'USER' })
const databasePath = path.resolve(databaseFile)
let ownedReceiptId
let claimedReceiptId
let imageKey

async function createTables() {
  const statements = [
    `CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'USER', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
    `CREATE TABLE receipt_categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)`,
    `CREATE TABLE receipts (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id), client_receipt_id TEXT, category_id INTEGER NOT NULL REFERENCES receipt_categories(id), receipt_date INTEGER NOT NULL, amount REAL NOT NULL, currency TEXT NOT NULL DEFAULT 'SGD', exchange_rate REAL NOT NULL DEFAULT 1, sgd_equivalent REAL NOT NULL, description TEXT, note TEXT, storage_key TEXT, original_file_name TEXT, mime_type TEXT, status TEXT NOT NULL DEFAULT 'UNCLAIMED', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
    `CREATE UNIQUE INDEX receipts_client_receipt_unique_idx ON receipts(user_id, client_receipt_id)`,
  ]
  for (const statement of statements) await database.client.execute(statement)
  const now = new Date()
  await database.db.insert(schema.users).values([
    { id: 1, username: 'tester', displayName: 'Test User', passwordHash: 'test', role: 'USER', createdAt: now, updatedAt: now },
    { id: 2, username: 'other', displayName: 'Other User', passwordHash: 'test', role: 'USER', createdAt: now, updatedAt: now },
  ])
  const [category] = await database.db.insert(schema.receiptCategories).values({ name: 'Test', active: true, createdAt: now }).returning({ id: schema.receiptCategories.id })
  imageKey = storage.createStorageKey('route-tests', 'png')
  await storage.saveBuffer(buildTestPng(), { key: imageKey, contentType: 'image/png' })
  const rows = await database.db.insert(schema.receipts).values([
    { userId: 1, categoryId: category.id, receiptDate: now, amount: 10, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 10, storageKey: imageKey, originalFileName: 'receipt.png', mimeType: 'image/png', status: 'UNCLAIMED', createdAt: now, updatedAt: now },
    { userId: 1, categoryId: category.id, receiptDate: now, amount: 20, currency: 'SGD', exchangeRate: 1, sgdEquivalent: 20, status: 'CLAIMED', createdAt: now, updatedAt: now },
  ]).returning({ id: schema.receipts.id })
  ownedReceiptId = rows[0].id
  claimedReceiptId = rows[1].id
}

describe('receipt route security and upload handling', () => {
  it('sets up the isolated receipt fixtures', async () => { await createTables() })

  it('rejects requests without a bearer token before touching receipt data', async () => {
    const response = await request(app).get('/api/receipts')
    expect(response.status).toBe(401)
    expect(response.body.error).toBe('Authentication required.')
  })

  it('validates report download IDs before database access', async () => {
    const response = await request(app).get('/api/report-files/0/download').set('Authorization', `Bearer ${token}`)
    expect(response.status).toBe(400)
  })

  it('rejects non-image multipart uploads through the Multer 2 pipeline', async () => {
    const response = await request(app)
      .post('/api/receipts')
      .set('Authorization', `Bearer ${token}`)
      .field('amount', '12')
      .field('categoryId', '1')
      .field('receiptDate', '2026-10-11')
      .attach('image', Buffer.from('plain text'), { filename: 'receipt.txt', contentType: 'text/plain' })
    expect(response.status).toBe(400)
    expect(response.body.error).toBe('Only JPEG and PNG images are accepted.')
  })

  it('accepts a valid decoded PNG upload and rejects impossible calendar dates', async () => {
    const accepted = await request(app)
      .post('/api/receipts')
      .set('Authorization', `Bearer ${token}`)
      .field('amount', '12.50')
      .field('categoryId', '1')
      .field('receiptDate', '2026-10-11')
      .field('clientReceiptId', 'valid-image-upload')
      .attach('image', buildTestPng(), { filename: 'receipt.png', contentType: 'image/png' })
    expect(accepted.status).toBe(201)
    expect(accepted.body.data.mimeType).toBe('image/png')
    expect(accepted.body.data.clientReceiptId).toBe('valid-image-upload')

    const retry = await request(app)
      .post('/api/receipts')
      .set('Authorization', `Bearer ${token}`)
      .field('amount', '12.50')
      .field('categoryId', '1')
      .field('receiptDate', '2026-10-11')
      .field('clientReceiptId', 'valid-image-upload')
      .attach('image', buildTestPng(), { filename: 'receipt.png', contentType: 'image/png' })
    expect(retry.status).toBe(200)
    expect(retry.body.data.id).toBe(accepted.body.data.id)
    expect(retry.body.data.clientReceiptId).toBe('valid-image-upload')

    const listing = await request(app).get('/api/receipts').set('Authorization', `Bearer ${token}`)
    expect(listing.status).toBe(200)
    expect(listing.body.data.find(({ id }) => id === accepted.body.data.id).clientReceiptId).toBe('valid-image-upload')

    const invalidDate = await request(app)
      .post('/api/receipts')
      .set('Authorization', `Bearer ${token}`)
      .field('amount', '12.50')
      .field('categoryId', '1')
      .field('receiptDate', '2026-02-30')
      .attach('image', buildTestPng(), { filename: 'receipt.png', contentType: 'image/png' })
    expect(invalidDate.status).toBe(400)
    expect(invalidDate.body.error).toContain('real Gregorian date')
  })

  it('returns 400 for malformed JSON instead of an internal server error', async () => {
    const response = await request(app).post('/api/auth/login').set('Content-Type', 'application/json').send('{"username":')
    expect(response.status).toBe(400)
    expect(response.body.error).toBe('Request body contains invalid JSON.')
  })

  it('bounds registration input and bcrypt passwords by UTF-8 byte length', async () => {
    const longUsername = await request(app).post('/api/auth/register').send({ username: 'u'.repeat(65), password: 'correct horse battery', displayName: 'Test' })
    expect(longUsername.status).toBe(400)
    const longDisplayName = await request(app).post('/api/auth/register').send({ username: 'valid-user', password: 'correct horse battery', displayName: 'D'.repeat(101) })
    expect(longDisplayName.status).toBe(400)
    const multibytePassword = await request(app).post('/api/auth/register').send({ username: 'valid-user', password: '💳'.repeat(19), displayName: 'Test' })
    expect(multibytePassword.status).toBe(400)
  })

  it('validates claim sizes, positive safe receipt IDs, and date ordering before database work', async () => {
    const tooLongTitle = await request(app).post('/api/claims').set('Authorization', `Bearer ${token}`).send({ title: 'T'.repeat(201), receiptIds: [1] })
    expect(tooLongTitle.status).toBe(400)
    const zeroId = await request(app).post('/api/claims').set('Authorization', `Bearer ${token}`).send({ title: 'Claim', receiptIds: [0] })
    expect(zeroId.status).toBe(400)
    const nonIntegerId = await request(app).post('/api/claims').set('Authorization', `Bearer ${token}`).send({ title: 'Claim', receiptIds: [1.5] })
    expect(nonIntegerId.status).toBe(400)
    const tooMany = await request(app).post('/api/claims').set('Authorization', `Bearer ${token}`).send({ title: 'Claim', receiptIds: Array.from({ length: 501 }, (_, index) => index + 1) })
    expect(tooMany.status).toBe(400)
    const reversedRange = await request(app).post('/api/claims').set('Authorization', `Bearer ${token}`).send({ title: 'Claim', receiptIds: [1], startDate: '2026-10-12', endDate: '2026-10-11' })
    expect(reversedRange.status).toBe(400)
  })

  it('rejects an image MIME type when the payload is not a decodable PNG or JPEG', async () => {
    const response = await request(app)
      .post('/api/receipts')
      .set('Authorization', `Bearer ${token}`)
      .field('amount', '12')
      .field('categoryId', '1')
      .field('receiptDate', '2026-10-11')
      .attach('image', Buffer.from('<svg><script>alert(1)</script></svg>'), { filename: 'receipt.png', contentType: 'image/png' })
    expect(response.status).toBe(400)
    expect(response.body.error).toBe('Image is corrupt, truncated, or unsupported.')
  })

  it('restores an image only to its owner with private, no-store headers', async () => {
    const own = await request(app).get(`/api/receipts/${ownedReceiptId}/image`).set('Authorization', `Bearer ${token}`)
    expect(own.status).toBe(200)
    expect(own.headers['content-type']).toMatch(/^image\/png/)
    expect(own.headers['cache-control']).toContain('private')
    expect(own.headers['cache-control']).toContain('no-store')
    expect(own.headers['x-content-type-options']).toBe('nosniff')
    expect(Buffer.isBuffer(own.body)).toBe(true)

    const other = await request(app).get(`/api/receipts/${ownedReceiptId}/image`).set('Authorization', `Bearer ${otherToken}`)
    expect(other.status).toBe(404)
  })

  it('rejects edits and deletion of claimed receipts', async () => {
    const update = await request(app).put(`/api/receipts/${claimedReceiptId}`).set('Authorization', `Bearer ${token}`).send({ amount: 99 })
    expect(update.status).toBe(409)
    const deletion = await request(app).delete(`/api/receipts/${claimedReceiptId}`).set('Authorization', `Bearer ${token}`)
    expect(deletion.status).toBe(409)
  })

  it('keeps the admin portal inaccessible while the feature flag is disabled', async () => {
    const response = await request(app).get('/api/admin/users')
    expect(response.status).toBe(404)
  })
})

afterAll(async () => {
  try {
    const [uploaded] = await database.db.select({ id: schema.receipts.id, storageKey: schema.receipts.storageKey }).from(schema.receipts).where(eq(schema.receipts.clientReceiptId, 'valid-image-upload')).limit(1)
    if (uploaded?.storageKey) await storage.removeFile(uploaded.storageKey)
    if (uploaded) await database.db.delete(schema.receipts).where(eq(schema.receipts.id, uploaded.id))
    if (imageKey) await storage.removeFile(imageKey)
  } catch {}
  await database.closeDatabase()
  for (const file of [databasePath, `${databasePath}-shm`, `${databasePath}-wal`]) {
    try { fs.rmSync(file, { force: true }) } catch {}
  }
})
