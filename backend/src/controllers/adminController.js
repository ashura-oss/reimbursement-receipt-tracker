import { desc, eq } from 'drizzle-orm'
import { db } from '../db/db.js'
import { claimReceipts, claims, receiptCategories, receipts, reportFiles, reports, users } from '../db/schema.js'
import { readBuffer } from '../services/storageService.js'

const userColumns = { id: users.id, username: users.username, displayName: users.displayName, role: users.role, createdAt: users.createdAt }
const claimColumns = { id: claims.id, userId: claims.userId, title: claims.title, startDate: claims.startDate, endDate: claims.endDate, status: claims.status, totalAmount: claims.totalAmount, totalSgdEquivalent: claims.totalSgdEquivalent, submittedAt: claims.submittedAt, createdAt: claims.createdAt, updatedAt: claims.updatedAt }

export const listUsers = async (req, res) => {
  try { const rows = await db.select(userColumns).from(users).orderBy(desc(users.createdAt)); return res.status(200).json({ message: 'Users retrieved.', data: rows }) }
  catch (error) { console.error(error); return res.status(500).json({ error: 'Internal Server Error' }) }
}

export const listClaims = async (req, res) => {
  try {
    const rows = await db.select({ claim: claimColumns, user: userColumns }).from(claims).innerJoin(users, eq(claims.userId, users.id)).orderBy(desc(claims.createdAt))
    return res.status(200).json({ message: 'Claims retrieved.', data: rows.map(({ claim, user }) => ({ ...claim, user })) })
  } catch (error) { console.error(error); return res.status(500).json({ error: 'Internal Server Error' }) }
}

export const getClaim = async (req, res) => {
  try {
    const claimId = Number(req.params.id)
    if (!Number.isInteger(claimId)) return res.status(400).json({ error: 'Claim id must be an integer.' })
    const [row] = await db.select({ claim: claimColumns, user: userColumns }).from(claims).innerJoin(users, eq(claims.userId, users.id)).where(eq(claims.id, claimId)).limit(1)
    if (!row) return res.status(404).json({ error: 'Claim not found.' })
    const receiptRows = await db.select({ id: receipts.id, userId: receipts.userId, categoryId: receipts.categoryId, categoryName: receiptCategories.name, receiptDate: receipts.receiptDate, amount: receipts.amount, currency: receipts.currency, exchangeRate: receipts.exchangeRate, sgdEquivalent: receipts.sgdEquivalent, description: receipts.description, note: receipts.note, storageKey: receipts.storageKey, originalFileName: receipts.originalFileName, mimeType: receipts.mimeType, status: receipts.status }).from(claimReceipts).innerJoin(receipts, eq(claimReceipts.receiptId, receipts.id)).innerJoin(receiptCategories, eq(receipts.categoryId, receiptCategories.id)).where(eq(claimReceipts.claimId, claimId))
    const reportRows = await db.select({ id: reports.id, userId: reports.userId, claimId: reports.claimId, status: reports.status, createdAt: reports.createdAt, completedAt: reports.completedAt, errorMessage: reports.errorMessage }).from(reports).where(eq(reports.claimId, claimId)).orderBy(desc(reports.createdAt))
    const files = reportRows.length ? await db.select({ id: reportFiles.id, reportId: reportFiles.reportId, fileType: reportFiles.fileType, storageKey: reportFiles.storageKey, fileName: reportFiles.fileName, mimeType: reportFiles.mimeType, createdAt: reportFiles.createdAt }).from(reportFiles).where(eq(reportFiles.reportId, reportRows[0].id)) : []
    return res.status(200).json({ message: 'Admin claim retrieved.', data: { claim: { ...row.claim, user: row.user }, receipts: receiptRows, reports: reportRows, files } })
  } catch (error) { console.error(error); return res.status(500).json({ error: 'Internal Server Error' }) }
}

export const getReceiptImage = async (req, res) => {
  try {
    const receiptId = Number(req.params.id)
    if (!Number.isInteger(receiptId)) return res.status(400).json({ error: 'Receipt id must be an integer.' })
    const [receipt] = await db.select({ storageKey: receipts.storageKey, mimeType: receipts.mimeType }).from(receipts).where(eq(receipts.id, receiptId)).limit(1)
    if (!receipt?.storageKey) return res.status(404).json({ error: 'Receipt image not found.' })
    const imageBuffer = await readBuffer(receipt.storageKey)
    return res.type(receipt.mimeType || 'application/octet-stream').send(imageBuffer)
  } catch (error) { console.error(error); return res.status(404).json({ error: 'Receipt image not found.' }) }
}
