import 'dotenv/config'
import { and, eq } from 'drizzle-orm'
import { db } from '../db/db.js'
import { exchangeRates } from '../db/schema.js'
import { DEFAULT_CURRENCY, SUPPORTED_CURRENCIES } from '../constants/currencies.js'
import { convertAmountToSgd } from '../utils/validationHelpers.js'

const BASE_URL = (process.env.FRANKFURTER_BASE_URL || 'https://api.frankfurter.dev/v2').replace(/\/$/, '')
const REQUEST_TIMEOUT_MS = Math.min(15_000, Math.max(100, Number(process.env.FRANKFURTER_TIMEOUT_MS || 8_000)))
const cache = new Map()
const pendingRates = new Map()

export class ExchangeRateUnavailableError extends Error {
  constructor() {
    super('Exchange rate service is temporarily unavailable.')
    this.name = 'ExchangeRateUnavailableError'
    this.code = 'EXCHANGE_RATE_UNAVAILABLE'
    this.statusCode = 503
  }
}

function asDate(value) {
  const date = value ? new Date(value) : new Date()
  if (Number.isNaN(date.getTime())) throw new Error('Invalid rate date')
  return date
}

function dateKey(value) {
  return asDate(value).toISOString().slice(0, 10)
}

function assertCurrency(currency) {
  const code = String(currency || DEFAULT_CURRENCY).toUpperCase()
  if (!/^[A-Z]{3}$/.test(code)) throw new Error('Currency must be a 3-letter ISO code')
  if (SUPPORTED_CURRENCIES.length && !SUPPORTED_CURRENCIES.includes(code)) {
    throw new Error(`Unsupported currency: ${code}`)
  }
  return code
}

export async function getRate(sourceCurrency = DEFAULT_CURRENCY, rateDate = new Date(), targetCurrency = DEFAULT_CURRENCY) {
  const source = assertCurrency(sourceCurrency)
  const target = assertCurrency(targetCurrency)
  const date = asDate(rateDate)
  const day = dateKey(date)
  if (source === target) return 1

  const cacheKey = `${source}:${target}:${day}`
  if (cache.has(cacheKey)) return cache.get(cacheKey)
  if (pendingRates.has(cacheKey)) return pendingRates.get(cacheKey)
  const pending = (async () => {
    const [stored] = await db.select({ rate: exchangeRates.rate })
      .from(exchangeRates)
      .where(and(
        eq(exchangeRates.sourceCurrency, source),
        eq(exchangeRates.targetCurrency, target),
        eq(exchangeRates.rateDate, new Date(`${day}T00:00:00.000Z`)),
      ))
      .limit(1)
    if (stored) {
      const rate = Number(stored.rate)
      if (!Number.isFinite(rate) || rate <= 0 || rate > 1_000_000) throw new ExchangeRateUnavailableError()
      cache.set(cacheKey, rate)
      return rate
    }

    const rate = await fetchFrankfurterRate(getFrankfurterUrl(source, target, day))
    await db.insert(exchangeRates).values({
      sourceCurrency: source,
      targetCurrency: target,
      rateDate: new Date(`${day}T00:00:00.000Z`),
      rate,
      provider: 'frankfurter',
    }).onConflictDoNothing()
    cache.set(cacheKey, rate)
    return rate
  })()
  pendingRates.set(cacheKey, pending)
  try { return await pending } finally { pendingRates.delete(cacheKey) }
}

async function fetchFrankfurterRate(url) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  timeout.unref?.()
  try {
    const response = await fetch(url, { signal: controller.signal })
    if (!response.ok) throw new Error('upstream status')
    const payload = await response.json()
    const rate = Number(payload.rate)
    if (!Number.isFinite(rate) || rate <= 0 || rate > 1_000_000) throw new Error('invalid rate payload')
    return rate
  } catch {
    throw new ExchangeRateUnavailableError()
  } finally {
    clearTimeout(timeout)
  }
}

export async function calculateSgdEquivalent(amount, currency = DEFAULT_CURRENCY, rateDate = new Date()) {
  const numericAmount = Number(amount)
  if (!Number.isFinite(numericAmount) || numericAmount < 0) throw new Error('Amount must be a non-negative number')
  const rate = await getRate(currency, rateDate, DEFAULT_CURRENCY)
  const sgdEquivalent = numericAmount === 0 ? 0 : convertAmountToSgd(numericAmount, rate)
  if (sgdEquivalent === null) throw new RangeError('SGD equivalent exceeds the supported amount range')
  return { amount: numericAmount, currency: assertCurrency(currency), exchangeRate: rate, sgdEquivalent }
}

export function clearRateCache() { cache.clear() }
export function getFrankfurterUrl(sourceCurrency, targetCurrency, rateDate) {
  return `${BASE_URL}/rate/${encodeURIComponent(assertCurrency(sourceCurrency))}/${encodeURIComponent(assertCurrency(targetCurrency))}?date=${dateKey(rateDate)}`
}

export const exchangeRateService = { getRate, calculateSgdEquivalent, clearRateCache, getFrankfurterUrl, ExchangeRateUnavailableError }

