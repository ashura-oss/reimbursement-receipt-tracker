import { afterAll, describe, expect, it } from 'vitest'
import request from 'supertest'
import fs from 'node:fs'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { clientIpKey } from './rateLimitMiddleware.js'

process.env.NODE_ENV = 'production'
process.env.DISABLE_HTTP_SERVER = 'true'
process.env.JWT_SECRET = 'rate-limit-test-secret-at-least-32-characters-long'
process.env.DATABASE_PROVIDER = 'sqlite'
const databasePath = path.resolve(`rate-limit-${randomUUID()}.db`)
process.env.DATABASE_URL = `file:${path.basename(databasePath)}`

const [{ default: app }, { closeDatabase }] = await Promise.all([
  import('../../index.js'),
  import('../db/db.js'),
])

describe('production request limits', () => {
  it('keeps one IPv4 rate-limit key when a proxy changes the source port', () => {
    expect(clientIpKey({ ip: '116.14.46.243:39765' })).toBe(clientIpKey({ ip: '116.14.46.243:58321' }))
    expect(clientIpKey({ ip: '116.14.46.243:39765' })).toBe(clientIpKey({ ip: '116.14.46.243' }))
  })

  it('uses the library IPv6 subnet key and groups addresses from the same /56', () => {
    expect(clientIpKey({ ip: '2001:db8:abcd:1200::1' })).toBe(clientIpKey({ ip: '2001:db8:abcd:12ff::2' }))
    expect(clientIpKey({ ip: '[2001:db8:abcd:1200::1]:39765' })).toBe(clientIpKey({ ip: '2001:db8:abcd:1200::1' }))
  })

  it('limits repeated authentication attempts and returns a retryable 429', async () => {
    const responses = []
    for (let attempt = 0; attempt < 11; attempt += 1) responses.push(await request(app).post('/api/auth/login').send({}))
    expect(responses.slice(0, 10).every((response) => response.status === 400)).toBe(true)
    expect(responses[10].status).toBe(429)
    expect(responses[10].body.error).toContain('Too many authentication attempts')
    expect(responses[10].headers['ratelimit']).toBeDefined()
  })
})

afterAll(async () => {
  await closeDatabase()
  for (const file of [databasePath, `${databasePath}-shm`, `${databasePath}-wal`]) {
    try { fs.rmSync(file, { force: true }) } catch {}
  }
})
