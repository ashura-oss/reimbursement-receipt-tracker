import 'dotenv/config'
import fs from 'node:fs/promises'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const backendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const storageRoot = path.resolve(process.env.STORAGE_DIR || path.join(backendRoot, 'storage'))
const storageProvider = String(process.env.STORAGE_PROVIDER || 'local').toLowerCase()
let azureContainerClient

function safeKey(key) {
  const normalized = String(key || '').replaceAll('\\', '/').replace(/^\/+/, '')
  if (!normalized || normalized.includes('\0') || normalized.includes('..') || path.posix.isAbsolute(normalized)) throw new Error('Invalid storage key')
  return normalized
}

function filePath(key) {
  const safe = safeKey(key)
  const resolved = path.resolve(storageRoot, ...safe.split('/'))
  if (resolved !== storageRoot && !resolved.startsWith(`${storageRoot}${path.sep}`)) throw new Error('Invalid storage key')
  return resolved
}

async function getAzureContainerClient() {
  if (storageProvider !== 'azure') return null
  if (azureContainerClient) return azureContainerClient
  const containerName = process.env.AZURE_STORAGE_CONTAINER
  const accountUrl = process.env.AZURE_STORAGE_ACCOUNT_URL
  const connectionString = process.env.AZURE_STORAGE_CONNECTION_STRING
  if (!containerName) throw new Error('Azure storage requires AZURE_STORAGE_CONTAINER.')
  const { BlobServiceClient } = await import('@azure/storage-blob')
  let serviceClient
  if (accountUrl) {
    const { DefaultAzureCredential } = await import('@azure/identity')
    serviceClient = new BlobServiceClient(accountUrl, new DefaultAzureCredential())
  } else if (connectionString) {
    serviceClient = BlobServiceClient.fromConnectionString(connectionString)
  } else {
    throw new Error('Azure storage requires AZURE_STORAGE_ACCOUNT_URL (managed identity) or AZURE_STORAGE_CONNECTION_STRING.')
  }
  azureContainerClient = serviceClient.getContainerClient(containerName)
  await azureContainerClient.createIfNotExists()
  return azureContainerClient
}

export async function ensureStorage() {
  const azure = await getAzureContainerClient()
  if (azure) return azure.containerName
  await fs.mkdir(storageRoot, { recursive: true })
  return storageRoot
}

export function createStorageKey(folder, extension = '') {
  const ext = extension ? `.${String(extension).replace(/^\./, '')}` : ''
  return `${String(folder || 'files').replaceAll('\\', '/').replace(/^\/+|\/+$/g, '')}/${Date.now()}-${randomUUID()}${ext}`
}

export async function saveBuffer(buffer, { key, contentType } = {}) {
  if (!Buffer.isBuffer(buffer) && !(buffer instanceof Uint8Array)) throw new TypeError('buffer must be a Buffer or Uint8Array')
  const storageKey = safeKey(key || createStorageKey('files'))
  const azure = await getAzureContainerClient()
  if (azure) {
    const blob = azure.getBlockBlobClient(storageKey)
    await blob.uploadData(Buffer.from(buffer), { blobHTTPHeaders: contentType ? { blobContentType: contentType } : undefined })
    return { key: storageKey, contentType, size: buffer.length }
  }
  await ensureStorage()
  const resolved = filePath(storageKey)
  await fs.mkdir(path.dirname(resolved), { recursive: true })
  await fs.writeFile(resolved, buffer)
  return { key: path.relative(storageRoot, resolved).replaceAll(path.sep, '/'), contentType, size: buffer.length }
}

export async function saveFile(sourcePath, options = {}) {
  return saveBuffer(await fs.readFile(sourcePath), options)
}

export async function saveReceiptImage(file, { userId, receiptId } = {}) {
  const extension = path.extname(file.originalname || '').replace(/^\./, '') || 'bin'
  const folder = `receipts/${userId || 'unassigned'}/${receiptId || 'pending'}`
  const saved = await saveBuffer(file.buffer, { key: createStorageKey(folder, extension), contentType: file.mimetype })
  return { ...saved, storageKey: saved.key, originalFileName: file.originalname || null, mimeType: file.mimetype || null }
}

export async function deleteStorageObject(key) { return removeFile(key) }

export async function readBuffer(key) {
  const azure = await getAzureContainerClient()
  if (azure) return azure.getBlobClient(safeKey(key)).downloadToBuffer()
  return fs.readFile(filePath(key))
}

export async function getFilePath(key) {
  if (storageProvider === 'azure') throw new Error('Direct file paths are unavailable for Azure Blob Storage.')
  const resolved = filePath(key)
  await fs.access(resolved)
  return resolved
}

export async function fileExists(key) {
  const azure = await getAzureContainerClient()
  if (azure) return azure.getBlobClient(safeKey(key)).exists()
  try { await fs.access(filePath(key)); return true } catch { return false }
}

export async function removeFile(key) {
  const azure = await getAzureContainerClient()
  if (azure) return azure.getBlobClient(safeKey(key)).deleteIfExists()
  try { await fs.unlink(filePath(key)); return true } catch (error) { if (error.code === 'ENOENT') return false; throw error }
}

export function getStorageRoot() { return storageProvider === 'azure' ? `azure://${process.env.AZURE_STORAGE_CONTAINER || 'unconfigured'}` : storageRoot }
export function getStorageProvider() { return storageProvider }

export const storageService = {
  ensureStorage, createStorageKey, saveBuffer, saveFile, saveReceiptImage, readBuffer,
  getFilePath, fileExists, removeFile, deleteStorageObject, getStorageRoot, getStorageProvider,
}
