import 'dotenv/config'
import fs from 'node:fs/promises'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const backendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const storageRoot = path.resolve(process.env.STORAGE_DIR || path.join(backendRoot, 'storage'))

function safeKey(key) {
  const normalized = String(key || '').replaceAll('\\', '/').replace(/^\/+/, '')
  if (!normalized || normalized.includes('..') || path.posix.isAbsolute(normalized)) {
    throw new Error('Invalid storage key')
  }
  return normalized
}

function filePath(key) {
  const safe = safeKey(key)
  const resolved = path.resolve(storageRoot, ...safe.split('/'))
  if (resolved !== storageRoot && !resolved.startsWith(`${storageRoot}${path.sep}`)) {
    throw new Error('Invalid storage key')
  }
  return resolved
}

export async function ensureStorage() {
  await fs.mkdir(storageRoot, { recursive: true })
  return storageRoot
}

export function createStorageKey(folder, extension = '') {
  const ext = extension ? `.${String(extension).replace(/^\./, '')}` : ''
  return `${String(folder || 'files').replaceAll('\\', '/').replace(/^\/+|\/+$/g, '')}/${Date.now()}-${randomUUID()}${ext}`
}

export async function saveBuffer(buffer, { key, contentType } = {}) {
  if (!Buffer.isBuffer(buffer) && !(buffer instanceof Uint8Array)) throw new TypeError('buffer must be a Buffer or Uint8Array')
  await ensureStorage()
  const resolved = filePath(key || createStorageKey('files'))
  await fs.mkdir(path.dirname(resolved), { recursive: true })
  await fs.writeFile(resolved, buffer)
  return { key: path.relative(storageRoot, resolved).replaceAll(path.sep, '/'), contentType, size: buffer.length }
}

export async function saveFile(sourcePath, options = {}) {
  return saveBuffer(await fs.readFile(sourcePath), options)
}

// Receipt controllers use these named helpers so the storage provider can be
// replaced by Azure Blob Storage later without changing HTTP handlers.
export async function saveReceiptImage(file, { userId, receiptId } = {}) {
  const extension = path.extname(file.originalname || '').replace(/^\./, '') || 'bin'
  const folder = `receipts/${userId || 'unassigned'}/${receiptId || 'pending'}`
  const saved = await saveBuffer(file.buffer, {
    key: createStorageKey(folder, extension),
    contentType: file.mimetype,
  })
  return { ...saved, storageKey: saved.key, originalFileName: file.originalname || null, mimeType: file.mimetype || null }
}

export async function deleteStorageObject(key) {
  return removeFile(key)
}

export async function readBuffer(key) {
  return fs.readFile(filePath(key))
}

export async function getFilePath(key) {
  const resolved = filePath(key)
  await fs.access(resolved)
  return resolved
}

export async function fileExists(key) {
  try { await fs.access(filePath(key)); return true } catch { return false }
}

export async function removeFile(key) {
  try { await fs.unlink(filePath(key)); return true } catch (error) {
    if (error.code === 'ENOENT') return false
    throw error
  }
}

export function getStorageRoot() {
  return storageRoot
}

export const storageService = {
  ensureStorage, createStorageKey, saveBuffer, saveFile, saveReceiptImage,
  readBuffer, getFilePath, fileExists, removeFile, deleteStorageObject, getStorageRoot,
}
