import { describe, expect, it } from 'vitest'
import { isUniqueConstraintError } from './databaseErrors.js'

describe('database error classification', () => {
  it('recognizes direct and wrapped PostgreSQL uniqueness conflicts', () => {
    expect(isUniqueConstraintError({ code: '23505' })).toBe(true)
    expect(isUniqueConstraintError({ cause: { code: '23505' } })).toBe(true)
    expect(isUniqueConstraintError({ parent: { code: 'SQLITE_CONSTRAINT_UNIQUE' } })).toBe(true)
    expect(isUniqueConstraintError(new Error('UNIQUE constraint failed'))).toBe(true)
    expect(isUniqueConstraintError({ code: '08006', message: 'database connection failed' })).toBe(false)
  })
})
