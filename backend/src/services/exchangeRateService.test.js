import fs from 'node:fs'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'

process.env.NODE_ENV = 'test'
process.env.DATABASE_PROVIDER = 'sqlite'
const databaseFile = `exchange-rate-${randomUUID()}.db`
process.env.DATABASE_URL = `file:${databaseFile}`
process.env.FRANKFURTER_TIMEOUT_MS = '100'
const databasePath = path.resolve(databaseFile)
try { fs.rmSync(databasePath, { force: true }) } catch {}

const [{ getFrankfurterUrl, getRate, clearRateCache, ExchangeRateUnavailableError }, { client, closeDatabase }] = await Promise.all([
  import('./exchangeRateService.js'),
  import('../db/db.js'),
])

beforeAll(async () => {
  await client.execute(`CREATE TABLE exchange_rates (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_currency TEXT NOT NULL,
    target_currency TEXT NOT NULL DEFAULT 'SGD',
    rate_date INTEGER NOT NULL,
    rate REAL NOT NULL,
    provider TEXT NOT NULL DEFAULT 'frankfurter',
    created_at INTEGER NOT NULL
  )`)
  await client.execute('CREATE UNIQUE INDEX exchange_rate_unique_idx ON exchange_rates(source_currency, target_currency, rate_date)')
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearRateCache()
})

describe('exchangeRateService', () => {
  it('builds the date-scoped Frankfurter URL', () => {
    expect(getFrankfurterUrl('INR', 'SGD', '2026-10-11')).toBe('https://api.frankfurter.dev/v2/rate/INR/SGD?date=2026-10-11')
  })

  it('coalesces concurrent requests and caches successful rates in memory and the database', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ date: '2026-10-11', rate: 0.016 }) }))
    vi.stubGlobal('fetch', fetchMock)
    const rates = await Promise.all([
      getRate('INR', '2026-10-11'),
      getRate('INR', '2026-10-11'),
    ])
    expect(rates).toEqual([0.016, 0.016])
    expect(fetchMock).toHaveBeenCalledTimes(1)
    clearRateCache()
    expect(await getRate('INR', '2026-10-11')).toBe(0.016)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not cache failed upstream responses and returns a typed 503 error', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 503 })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ date: '2026-10-10', rate: 1.27 }) })
    vi.stubGlobal('fetch', fetchMock)
    await expect(getRate('USD', '2026-10-10')).rejects.toBeInstanceOf(ExchangeRateUnavailableError)
    await expect(getRate('USD', '2026-10-10')).resolves.toBe(1.27)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('aborts an upstream request that exceeds its timeout', async () => {
    vi.stubGlobal('fetch', vi.fn((_url, { signal }) => new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
    })))
    await expect(getRate('EUR', '2026-10-09')).rejects.toMatchObject({ code: 'EXCHANGE_RATE_UNAVAILABLE', statusCode: 503 })
  })
})

afterAll(async () => {
  await closeDatabase()
  for (const file of [databasePath, `${databasePath}-shm`, `${databasePath}-wal`]) {
    try { fs.rmSync(file, { force: true }) } catch {}
  }
})
