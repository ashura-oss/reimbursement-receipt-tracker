export const isNonEmptyString = (value) => typeof value === 'string' && value.trim().length > 0

export const isValidDateString = (value) => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  return !Number.isNaN(Date.parse(`${value}T00:00:00.000Z`))
}

export const toDate = (value) => new Date(`${value}T00:00:00.000Z`)
