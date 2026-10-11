import { db } from '../db/db.js'
import * as claimModel from '../models/claimModel.js'
import { DEFAULT_CLAIM_STATUS } from '../constants/claims.js'
import { isNonEmptyString, isValidDateString, toDate } from '../utils/validationHelpers.js'
import { isUniqueConstraintError } from '../utils/databaseErrors.js'

const shapeClaim = async (claim, userId) => ({ ...claim, receipts: await claimModel.getReceipts(claim.id, userId) })

export const listClaims = async (req, res) => {
  try {
    const claims = await claimModel.listByUser(res.locals.userId)
    return res.status(200).json({ message: 'Claims retrieved.', data: claims })
  } catch (error) { console.error(error); return res.status(500).json({ error: 'Internal Server Error' }) }
}

export const getClaim = async (req, res) => {
  try {
    const id = Number(req.params.id)
    if (!Number.isInteger(id)) return res.status(400).json({ error: 'Claim id must be an integer.' })
    const claim = await claimModel.getByIdForUser(id, res.locals.userId)
    if (!claim) return res.status(404).json({ error: 'Claim not found.' })
    return res.status(200).json({ message: 'Claim retrieved.', data: await shapeClaim(claim, res.locals.userId) })
  } catch (error) { console.error(error); return res.status(500).json({ error: 'Internal Server Error' }) }
}

export const createClaim = async (req, res) => {
  try {
    const { title, startDate, endDate, receiptIds } = req.body || {}
    if (!isNonEmptyString(title) || title.trim().length > 200) return res.status(400).json({ error: 'Title is required and must be 200 characters or fewer.' })
    if (!Array.isArray(receiptIds) || receiptIds.length === 0 || receiptIds.length > 500 || receiptIds.some((id) => !(typeof id === 'number' && Number.isSafeInteger(id) && id > 0 || typeof id === 'string' && /^[1-9]\d{0,14}$/.test(id) && Number.isSafeInteger(Number(id))))) return res.status(400).json({ error: 'Provide between 1 and 500 positive receipt ids.' })
    if (startDate !== undefined && !isValidDateString(startDate)) return res.status(400).json({ error: 'Start date must use YYYY-MM-DD.' })
    if (endDate !== undefined && !isValidDateString(endDate)) return res.status(400).json({ error: 'End date must use YYYY-MM-DD.' })
    if (startDate && endDate && endDate < startDate) return res.status(400).json({ error: 'End date must be on or after the start date.' })
    const uniqueReceiptIds = [...new Set(receiptIds.map(Number))]
    const claim = await db.transaction((tx) => claimModel.createClaimWithReceipts({ userId: res.locals.userId, title: title.trim(), startDate: startDate ? toDate(startDate) : null, endDate: endDate ? toDate(endDate) : null, receiptIds: uniqueReceiptIds }, tx))
    return res.status(201).json({ message: 'Claim created.', data: await shapeClaim(claim, res.locals.userId) })
  } catch (error) {
    console.error(error)
    if (error.message === 'RECEIPT_OWNERSHIP_MISMATCH') return res.status(404).json({ error: 'One or more receipts were not found.' })
    if (error.message === 'RECEIPT_ALREADY_CLAIMED' || isUniqueConstraintError(error)) return res.status(409).json({ error: 'One or more receipts have already been claimed.' })
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}

export const submitClaim = async (req, res) => {
  try {
    const id = Number(req.params.id)
    if (!Number.isInteger(id)) return res.status(400).json({ error: 'Claim id must be an integer.' })
    const claim = await claimModel.submitClaim(id, res.locals.userId)
    if (!claim) return res.status(404).json({ error: 'Claim not found.' })
    return res.status(200).json({ message: 'Claim submitted.', data: claim })
  } catch (error) { console.error(error); return res.status(500).json({ error: 'Internal Server Error' }) }
}
