import multer from 'multer'

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 10 * 1024 * 1024 },
  fileFilter: (req, file, callback) => {
    if (!file.mimetype.startsWith('image/')) return callback(new Error('Only image files are accepted.'))
    callback(null, true)
  },
})

export const receiptUpload = upload.single('image')
