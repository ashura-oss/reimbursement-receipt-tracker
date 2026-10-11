import { afterAll, describe, expect, it } from 'vitest'
import request from 'supertest'

process.env.NODE_ENV = 'test'
process.env.JWT_SECRET = 'test-only-secret'
process.env.ADMIN_PORTAL_ENABLED = 'false'
process.env.DATABASE_PROVIDER = 'sqlite'
process.env.DATABASE_URL = `file:upload-security-${process.pid}.db`

const [{ default: app }, { signToken }, { closeDatabase }] = await Promise.all([
  import('../../index.js'),
  import('../middlewares/authMiddleware.js'),
  import('../db/db.js'),
])

const token = signToken({ userId: 1, username: 'tester', role: 'USER' })

describe('receipt route security and upload handling', () => {
  it('rejects requests without a bearer token before touching receipt data', async () => {
    const response = await request(app).get('/api/receipts')
    expect(response.status).toBe(401)
    expect(response.body.error).toBe('Authentication required.')
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
    expect(response.body.error).toBe('Only image files are accepted.')
  })

  it('keeps the admin portal inaccessible while the feature flag is disabled', async () => {
    const response = await request(app).get('/api/admin/users')
    expect(response.status).toBe(404)
  })
})

afterAll(async () => {
  await closeDatabase()
})
