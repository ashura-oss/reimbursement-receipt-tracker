export const isNonEmptyString = (value) => typeof value === 'string' && value.trim().length > 0

export const isValidDateString = (value) => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  if (year < 1 || month < 1 || month > 12 || day < 1) return false
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return day <= daysInMonth[month - 1]
}

export const toDate = (value) => {
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(0)
  date.setUTCFullYear(year, month - 1, day)
  date.setUTCHours(0, 0, 0, 0)
  return date
}

export const parseReceiptAmount = (value) => {
  if (typeof value === 'string') {
    const normalized = value.trim()
    if (!/^\d+(?:\.\d{1,2})?$/.test(normalized)) return null
    const [whole, fraction = ''] = normalized.split('.')
    const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0') || '0')
    if (cents <= 0n || cents > 100_000_000_000n) return null
    return Number(cents) / 100
  }
  if (typeof value !== 'number') return null
  const amount = value
  if (!Number.isFinite(amount) || amount <= 0 || amount > 1_000_000_000) return null
  const cents = Math.round(amount * 100)
  const tolerance = Number.EPSILON * Math.max(1, Math.abs(amount)) * 2
  return Number.isSafeInteger(cents) && Math.abs(amount - cents / 100) <= tolerance ? cents / 100 : null
}

export const convertAmountToSgd = (amount, rate) => {
  if (!Number.isFinite(amount) || amount <= 0 || !Number.isFinite(rate) || rate <= 0) return null
  const converted = amount * rate
  if (!Number.isFinite(converted) || converted > 1_000_000_000_000) return null
  const cents = Math.round(converted * 100)
  return Number.isSafeInteger(cents) ? cents / 100 : null
}
