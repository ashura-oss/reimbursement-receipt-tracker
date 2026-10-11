import multer from 'multer'
import sharp from 'sharp'

const MAX_IMAGE_PIXELS = 40_000_000

export async function validateReceiptImage(buffer, declaredMimeType) {
  try {
    const image = sharp(buffer, { limitInputPixels: MAX_IMAGE_PIXELS, failOn: 'error' })
    const metadata = await image.metadata()
    if (!metadata.width || !metadata.height || metadata.width * metadata.height > MAX_IMAGE_PIXELS) {
      throw new Error('Image dimensions are invalid or too large.')
    }
    if (!['jpeg', 'png'].includes(metadata.format)) throw new Error('Only JPEG and PNG images are accepted.')
    const contentType = metadata.format === 'jpeg' ? 'image/jpeg' : 'image/png'
    if (declaredMimeType !== contentType) throw new Error('Image content does not match its file type.')
    // stats() forces a decode, detecting truncated and malformed image payloads before storage.
    await image.stats()
    return contentType
  } catch (error) {
    if (['Image dimensions are invalid or too large.', 'Only JPEG and PNG images are accepted.', 'Image content does not match its file type.'].includes(error.message)) throw error
    throw new Error('Image is corrupt, truncated, or unsupported.')
  }
}

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 10 * 1024 * 1024, files: 1, fields: 20, fieldSize: 64 * 1024, parts: 22 },
  fileFilter: (req, file, callback) => {
    if (!['image/jpeg', 'image/png'].includes(file.mimetype)) return callback(new Error('Only JPEG and PNG images are accepted.'))
    callback(null, true)
  },
})

const receiveAndValidate = upload.single('image')
export const receiptUpload = (req, res, next) => receiveAndValidate(req, res, async (error) => {
  if (error) return next(error)
  if (!req.file) return next()
  try {
    req.file.mimetype = await validateReceiptImage(req.file.buffer, req.file.mimetype)
    return next()
  } catch (validationError) {
    return next(validationError)
  }
})
