import 'dotenv/config'
import { and, eq } from 'drizzle-orm'
import { db } from '../db/db.js'
import { exchangeRates } from '../db/schema.js'
import { DEFAULT_CURRENCY, SUPPORTED_CURRENCIES } from '../constants/currencies.js'

const BASE_URL = (process.env.FRANKFURTER_BASE_URL || 'https://api.frankfurter.dev/v2').replace(/\/$/, '')
const cache = new Map()

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

  const [stored] = await db.select({ rate: exchangeRates.rate })
    .from(exchangeRates)
    .where(and(
      eq(exchangeRates.sourceCurrency, source),
      eq(exchangeRates.targetCurrency, target),
      eq(exchangeRates.rateDate, new Date(`${day}T00:00:00.000Z`)),
    ))
    .limit(1)
  if (stored) {
    cache.set(cacheKey, Number(stored.rate))
    return Number(stored.rate)
  }

  const url = `${BASE_URL}/rate/${encodeURIComponent(source)}/${encodeURIComponent(target)}?date=${day}`
  const response = await fetch(url)
  if (!response.ok) throw new Error(`Frankfurter request failed (${response.status})`)
  const payload = await response.json()
  const rate = Number(payload.rate)
  if (!Number.isFinite(rate) || rate <= 0) throw new Error('Frankfurter returned an invalid exchange rate')

  const normalizedDate = new Date(`${payload.date || day}T00:00:00.000Z`)
  await db.insert(exchangeRates).values({
    sourceCurrency: source,
    targetCurrency: target,
    rateDate: normalizedDate,
    rate,
    provider: 'frankfurter',
  }).onConflictDoNothing()
  cache.set(cacheKey, rate)
  return rate
}

export async function calculateSgdEquivalent(amount, currency = DEFAULT_CURRENCY, rateDate = new Date()) {
  const numericAmount = Number(amount)
  if (!Number.isFinite(numericAmount) || numericAmount < 0) throw new Error('Amount must be a non-negative number')
  const rate = await getRate(currency, rateDate, DEFAULT_CURRENCY)
  return { amount: numericAmount, currency: assertCurrency(currency), exchangeRate: rate, sgdEquivalent: Math.round(numericAmount * rate * 100) / 100 }
}

export function clearRateCache() { cache.clear() }
export function getFrankfurterUrl(sourceCurrency, targetCurrency, rateDate) {
  return `${BASE_URL}/rate/${encodeURIComponent(assertCurrency(sourceCurrency))}/${encodeURIComponent(assertCurrency(targetCurrency))}?date=${dateKey(rateDate)}`
}

export const exchangeRateService = { getRate, calculateSgdEquivalent, clearRateCache, getFrankfurterUrl }

