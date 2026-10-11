import { DEFAULT_CURRENCY, SUPPORTED_CURRENCIES } from '../constants/currencies.js'
import * as categoryModel from '../models/categoryModel.js'
import * as receiptModel from '../models/receiptModel.js'
import * as exchangeRateService from '../services/exchangeRateService.js'
import * as storageService from '../services/storageService.js'
import { isNonEmptyString, isValidDateString, toDate } from '../utils/validationHelpers.js'

const parseAmount = (value) => {
  const amount = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(amount) && amount > 0 ? amount : null
}

export const listReceipts = async (req, res) => {
  try {
    const receipts = await receiptModel.listByUser(res.locals.userId)
    return res.status(200).json({ message: 'Receipts retrieved.', data: receipts })
  } catch (error) {
    console.error(error)
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}

export const getReceipt = async (req, res) => {
  try {
    const id = Number(req.params.id)
    if (!Number.isInteger(id)) return res.status(400).json({ error: 'Receipt id must be an integer.' })
    const receipt = await receiptModel.getByIdForUser(id, res.locals.userId)
    if (!receipt) return res.status(404).json({ error: 'Receipt not found.' })
    return res.status(200).json({ message: 'Receipt retrieved.', data: receipt })
  } catch (error) {
    console.error(error)
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}

export const createReceipt = async (req, res) => {
  try {
    const { amount: rawAmount, currency: rawCurrency = DEFAULT_CURRENCY, categoryId: rawCategoryId, receiptDate, description, note, clientReceiptId } = req.body || {}
    const amount = parseAmount(rawAmount)
    const currency = typeof rawCurrency === 'string' ? rawCurrency.trim().toUpperCase() : ''
    const categoryId = Number(rawCategoryId)
    if (amount === null) return res.status(400).json({ error: 'Amount must be a positive number.' })
    if (!SUPPORTED_CURRENCIES.includes(currency)) return res.status(400).json({ error: 'Unsupported currency.' })
    if (!Number.isInteger(categoryId)) return res.status(400).json({ error: 'A valid category is required.' })
    if (!isValidDateString(receiptDate)) return res.status(400).json({ error: 'Receipt date must use YYYY-MM-DD.' })
    if (!req.file) return res.status(400).json({ error: 'Receipt image is required.' })
    if (clientReceiptId !== undefined && !isNonEmptyString(clientReceiptId)) return res.status(400).json({ error: 'Client receipt id must be text.' })
    const normalizedClientReceiptId = isNonEmptyString(clientReceiptId) ? clientReceiptId.trim() : null
    const existingReceipt = await receiptModel.getByClientReceiptId(normalizedClientReceiptId, res.locals.userId)
    if (existingReceipt) return res.status(200).json({ message: 'Receipt already exists.', data: existingReceipt })
    if (description !== undefined && description !== null && typeof description !== 'string') return res.status(400).json({ error: 'Description must be text.' })
    if (note !== undefined && note !== null && typeof note !== 'string') return res.status(400).json({ error: 'Note must be text.' })
    if (!(await categoryModel.getById(categoryId))) return res.status(400).json({ error: 'Receipt category does not exist.' })
    const rate = await exchangeRateService.getRate(currency, receiptDate)
    const storedImage = req.file ? await storageService.saveReceiptImage(req.file, { userId: res.locals.userId }) : null
    const receipt = await receiptModel.createReceipt({
      userId: res.locals.userId, categoryId, receiptDate: toDate(receiptDate), amount, currency,
      exchangeRate: rate, sgdEquivalent: Number((amount * rate).toFixed(2)),
      description: isNonEmptyString(description) ? description.trim() : null,
      note: isNonEmptyString(note) ? note.trim() : null,
      clientReceiptId: normalizedClientReceiptId,
      storageKey: storedImage?.storageKey || null,
      originalFileName: storedImage?.originalFileName || null,
      mimeType: storedImage?.mimeType || null,
    })
    return res.status(201).json({ message: 'Receipt created.', data: receipt })
  } catch (error) {
    console.error(error)
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}

export const updateReceipt = async (req, res) => {
  try {
    const id = Number(req.params.id)
    if (!Number.isInteger(id)) return res.status(400).json({ error: 'Receipt id must be an integer.' })
    const existing = await receiptModel.getByIdForUser(id, res.locals.userId)
    if (!existing) return res.status(404).json({ error: 'Receipt not found.' })
    const { amount: rawAmount, currency: rawCurrency, categoryId: rawCategoryId, receiptDate, description, note } = req.body || {}
    const data = {}
    if (rawAmount !== undefined) { const amount = parseAmount(rawAmount); if (amount === null) return res.status(400).json({ error: 'Amount must be a positive number.' }); data.amount = amount }
    if (rawCurrency !== undefined) { if (typeof rawCurrency !== 'string' || !SUPPORTED_CURRENCIES.includes(rawCurrency.trim().toUpperCase())) return res.status(400).json({ error: 'Unsupported currency.' }); data.currency = rawCurrency.trim().toUpperCase() }
    if (rawCategoryId !== undefined) { const categoryId = Number(rawCategoryId); if (!Number.isInteger(categoryId) || !(await categoryModel.getById(categoryId))) return res.status(400).json({ error: 'Receipt category does not exist.' }); data.categoryId = categoryId }
    if (receiptDate !== undefined) { if (!isValidDateString(receiptDate)) return res.status(400).json({ error: 'Receipt date must use YYYY-MM-DD.' }); data.receiptDate = toDate(receiptDate) }
    if (description !== undefined) { if (description !== null && typeof description !== 'string') return res.status(400).json({ error: 'Description must be text.' }); data.description = description?.trim() || null }
    if (note !== undefined) { if (note !== null && typeof note !== 'string') return res.status(400).json({ error: 'Note must be text.' }); data.note = note?.trim() || null }
    if (data.amount !== undefined || data.currency !== undefined || data.receiptDate !== undefined) {
      const currency = data.currency || existing.currency
      const dateText = receiptDate || new Date(data.receiptDate || existing.receiptDate).toISOString().slice(0, 10)
      const rate = await exchangeRateService.getRate(currency, dateText)
      data.exchangeRate = rate
      data.sgdEquivalent = Number(((data.amount ?? existing.amount) * rate).toFixed(2))
    }
    const receipt = await receiptModel.updateReceipt(id, res.locals.userId, data)
    return res.status(200).json({ message: 'Receipt updated.', data: receipt })
  } catch (error) {
    console.error(error)
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}

export const deleteReceipt = async (req, res) => {
  try {
    const id = Number(req.params.id)
    if (!Number.isInteger(id)) return res.status(400).json({ error: 'Receipt id must be an integer.' })
    const receipt = await receiptModel.getByIdForUser(id, res.locals.userId)
    if (!receipt) return res.status(404).json({ error: 'Receipt not found.' })
    await receiptModel.deleteReceipt(id, res.locals.userId)
    if (receipt.storageKey) await storageService.deleteStorageObject(receipt.storageKey)
    return res.status(200).json({ message: 'Receipt deleted.', data: { id } })
  } catch (error) {
    console.error(error)
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}
