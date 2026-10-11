import { and, desc, eq, inArray } from 'drizzle-orm'
import { db } from '../db/db.js'
import { receiptCategories, receipts } from '../db/schema.js'

const receiptColumns = {
  id: receipts.id,
  userId: receipts.userId,
  categoryId: receipts.categoryId,
  categoryName: receiptCategories.name,
  receiptDate: receipts.receiptDate,
  amount: receipts.amount,
  currency: receipts.currency,
  exchangeRate: receipts.exchangeRate,
  sgdEquivalent: receipts.sgdEquivalent,
  description: receipts.description,
  note: receipts.note,
  storageKey: receipts.storageKey,
  originalFileName: receipts.originalFileName,
  mimeType: receipts.mimeType,
  status: receipts.status,
  createdAt: receipts.createdAt,
  updatedAt: receipts.updatedAt,
}

export const listByUser = async (userId, tx = db) => tx.select(receiptColumns)
  .from(receipts).innerJoin(receiptCategories, eq(receipts.categoryId, receiptCategories.id))
  .where(eq(receipts.userId, userId)).orderBy(desc(receipts.receiptDate), desc(receipts.createdAt))

export const getByIdForUser = async (id, userId, tx = db) => {
  const rows = await tx.select(receiptColumns).from(receipts)
    .innerJoin(receiptCategories, eq(receipts.categoryId, receiptCategories.id))
    .where(and(eq(receipts.id, id), eq(receipts.userId, userId))).limit(1)
  return rows[0] || null
}

export const getManyByIdsForUser = async (ids, userId, tx = db) => {
  if (ids.length === 0) return []
  return tx.select(receiptColumns).from(receipts)
    .innerJoin(receiptCategories, eq(receipts.categoryId, receiptCategories.id))
    .where(and(inArray(receipts.id, ids), eq(receipts.userId, userId)))
}

export const getByClientReceiptId = async (clientReceiptId, userId, tx = db) => {
  if (!clientReceiptId) return null
  const rows = await tx.select(receiptColumns).from(receipts)
    .innerJoin(receiptCategories, eq(receipts.categoryId, receiptCategories.id))
    .where(and(eq(receipts.clientReceiptId, clientReceiptId), eq(receipts.userId, userId))).limit(1)
  return rows[0] || null
}

export const createReceipt = async (data, tx = db) => {
  if (data.clientReceiptId) {
    const existing = await tx.select(receiptColumns).from(receipts)
      .innerJoin(receiptCategories, eq(receipts.categoryId, receiptCategories.id))
      .where(and(eq(receipts.userId, data.userId), eq(receipts.clientReceiptId, data.clientReceiptId))).limit(1)
    if (existing[0]) return existing[0]
  }
  const rows = await tx.insert(receipts).values(data).returning({ id: receipts.id })
  return getByIdForUser(rows[0].id, data.userId, tx)
}

export const updateReceipt = async (id, userId, data, tx = db) => {
  const rows = await tx.update(receipts).set({ ...data, updatedAt: new Date() })
    .where(and(eq(receipts.id, id), eq(receipts.userId, userId))).returning({ id: receipts.id })
  if (!rows[0]) return null
  return getByIdForUser(id, userId, tx)
}

export const deleteReceipt = async (id, userId, tx = db) => {
  const rows = await tx.delete(receipts).where(and(eq(receipts.id, id), eq(receipts.userId, userId))).returning({ id: receipts.id })
  return Boolean(rows[0])
}
