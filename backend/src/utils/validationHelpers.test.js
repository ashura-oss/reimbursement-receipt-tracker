import { describe, expect, it } from 'vitest'
import { convertAmountToSgd, isValidDateString, parseReceiptAmount, toDate } from './validationHelpers.js'

describe('receipt input validation', () => {
  it('validates actual Gregorian calendar dates, including leap years', () => {
    expect(isValidDateString('2024-02-29')).toBe(true)
    expect(isValidDateString('2025-02-29')).toBe(false)
    expect(isValidDateString('2026-04-31')).toBe(false)
    expect(isValidDateString('0000-01-01')).toBe(false)
    expect(isValidDateString('2026-10-11')).toBe(true)
  })

  it('round-trips low Gregorian years without JavaScript Date year remapping', () => {
    expect(toDate('0001-01-01').toISOString()).toBe('0001-01-01T00:00:00.000Z')
  })

  it('bounds amounts to positive, two-decimal currency values', () => {
    expect(parseReceiptAmount('0.01')).toBe(0.01)
    expect(parseReceiptAmount(12.5)).toBe(12.5)
    expect(parseReceiptAmount('1.234')).toBeNull()
    expect(parseReceiptAmount('1e3')).toBeNull()
    expect(parseReceiptAmount('1000000000.01')).toBeNull()
    expect(parseReceiptAmount('999999999.99')).toBe(999999999.99)
    expect(parseReceiptAmount(true)).toBeNull()
    expect(parseReceiptAmount([1])).toBeNull()
    expect(parseReceiptAmount({ amount: 1 })).toBeNull()
    expect(parseReceiptAmount(null)).toBeNull()
    expect(parseReceiptAmount(0)).toBeNull()
    expect(parseReceiptAmount(Infinity)).toBeNull()
  })

  it('rounds SGD conversion to cents and rejects overflow', () => {
    expect(convertAmountToSgd(10.01, 1.25)).toBe(12.51)
    expect(convertAmountToSgd(1_000_000_000, 1_000_000)).toBeNull()
    expect(convertAmountToSgd(Number.MAX_VALUE, 10)).toBeNull()
  })
})
