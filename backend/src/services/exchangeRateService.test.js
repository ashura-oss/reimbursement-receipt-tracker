import { describe, expect, it } from 'vitest'
import { getFrankfurterUrl } from './exchangeRateService.js'

describe('exchangeRateService', () => {
  it('builds the date-scoped Frankfurter URL', () => {
    expect(getFrankfurterUrl('INR', 'SGD', '2026-10-11')).toBe('https://api.frankfurter.dev/v2/rate/INR/SGD?date=2026-10-11')
  })
})
