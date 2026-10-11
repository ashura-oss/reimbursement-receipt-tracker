import { afterEach, describe, expect, it } from 'vitest'
import { createStorageKey, fileExists, readBuffer, removeFile, saveBuffer } from './storageService.js'

describe('storageService', () => {
  let key
  afterEach(async () => { if (key) await removeFile(key) })
  it('stores and reads receipt bytes using safe keys', async () => {
    key = createStorageKey('test', 'jpg')
    await saveBuffer(Buffer.from('receipt'), { key, contentType: 'image/jpeg' })
    expect(await fileExists(key)).toBe(true)
    expect((await readBuffer(key)).toString()).toBe('receipt')
  })
  it('rejects traversal keys', async () => {
    await expect(saveBuffer(Buffer.from('x'), { key: '../escape' })).rejects.toThrow('Invalid storage key')
  })
})
