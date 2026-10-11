import { asc, eq } from 'drizzle-orm'
import { db } from '../db/db.js'
import { receiptCategories } from '../db/schema.js'

export const listActive = async (tx = db) => tx.select({
  id: receiptCategories.id,
  name: receiptCategories.name,
}).from(receiptCategories).where(eq(receiptCategories.active, true)).orderBy(asc(receiptCategories.name))

export const getById = async (id, tx = db) => {
  const rows = await tx.select({ id: receiptCategories.id, name: receiptCategories.name })
    .from(receiptCategories).where(eq(receiptCategories.id, id)).limit(1)
  return rows[0] || null
}

export const createIfMissing = async (name, tx = db) => {
  const existing = await tx.select({ id: receiptCategories.id, name: receiptCategories.name })
    .from(receiptCategories).where(eq(receiptCategories.name, name)).limit(1)
  if (existing[0]) return existing[0]
  const rows = await tx.insert(receiptCategories).values({ name }).returning({ id: receiptCategories.id, name: receiptCategories.name })
  return rows[0]
}
