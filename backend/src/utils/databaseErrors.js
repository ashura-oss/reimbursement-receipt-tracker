export const isUniqueConstraintError = (error) => {
  const pending = [error]
  const seen = new Set()
  while (pending.length) {
    const current = pending.pop()
    if (!current || typeof current !== 'object' || seen.has(current)) continue
    seen.add(current)
    if (current.code === '23505' || current.code === 'SQLITE_CONSTRAINT_UNIQUE' || String(current.message || '').toLowerCase().includes('unique constraint')) return true
    pending.push(current.cause, current.originalError, current.parent)
  }
  return false
}
