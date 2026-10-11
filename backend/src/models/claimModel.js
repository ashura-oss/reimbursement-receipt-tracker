import { and, desc, eq, inArray } from 'drizzle-orm'
import { db } from '../db/db.js'
import { claimReceipts, claims, receiptCategories, receipts } from '../db/schema.js'

const claimColumns = {
  id: claims.id,
  userId: claims.userId,
  title: claims.title,
  startDate: claims.startDate,
  endDate: claims.endDate,
  status: claims.status,
  totalAmount: claims.totalAmount,
  totalSgdEquivalent: claims.totalSgdEquivalent,
  submittedAt: claims.submittedAt,
  createdAt: claims.createdAt,
  updatedAt: claims.updatedAt,
}

export const listByUser = async (userId, tx = db) => tx.select(claimColumns).from(claims)
  .where(eq(claims.userId, userId)).orderBy(desc(claims.createdAt))

export const getByIdForUser = async (id, userId, tx = db) => {
  const rows = await tx.select(claimColumns).from(claims)
    .where(and(eq(claims.id, id), eq(claims.userId, userId))).limit(1)
  return rows[0] || null
}

export const getReceipts = async (claimId, userId, tx = db) => tx.select({
  id: receipts.id,
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
}).from(claimReceipts)
  .innerJoin(claims, eq(claimReceipts.claimId, claims.id))
  .innerJoin(receipts, eq(claimReceipts.receiptId, receipts.id))
  .innerJoin(receiptCategories, eq(receipts.categoryId, receiptCategories.id))
  .where(and(eq(claimReceipts.claimId, claimId), eq(claims.userId, userId)))

export const createClaimWithReceipts = async ({ userId, title, startDate, endDate, receiptIds }, tx = db) => {
  const selected = await tx.select({ id: receipts.id, amount: receipts.amount, sgdEquivalent: receipts.sgdEquivalent, status: receipts.status })
    .from(receipts).where(and(eq(receipts.userId, userId), inArray(receipts.id, receiptIds)))
  if (selected.length !== receiptIds.length) throw new Error('RECEIPT_OWNERSHIP_MISMATCH')
  if (selected.some((receipt) => receipt.status !== 'UNCLAIMED')) throw new Error('RECEIPT_ALREADY_CLAIMED')
  const totalAmount = selected.reduce((sum, item) => sum + item.amount, 0)
  const totalSgdEquivalent = selected.reduce((sum, item) => sum + item.sgdEquivalent, 0)
  const claimRows = await tx.insert(claims).values({ userId, title, startDate, endDate, totalAmount, totalSgdEquivalent }).returning({ id: claims.id })
  const claimId = claimRows[0].id
  await tx.insert(claimReceipts).values(receiptIds.map((receiptId) => ({ claimId, receiptId })))
  await tx.update(receipts).set({ status: 'CLAIMED', updatedAt: new Date() }).where(inArray(receipts.id, receiptIds))
  return getByIdForUser(claimId, userId, tx)
}

export const submitClaim = async (id, userId, tx = db) => {
  const rows = await tx.update(claims).set({ status: 'SUBMITTED', submittedAt: new Date(), updatedAt: new Date() })
    .where(and(eq(claims.id, id), eq(claims.userId, userId))).returning({ id: claims.id })
  return rows[0] ? getByIdForUser(id, userId, tx) : null
}
