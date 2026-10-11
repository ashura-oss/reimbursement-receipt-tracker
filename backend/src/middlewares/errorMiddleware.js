export const notFoundMiddleware = (req, res) => res.status(404).json({ error: 'Route not found.' })

export const errorMiddleware = (error, req, res, next) => {
  if (res.headersSent) return next(error)
  if (error.type === 'entity.parse.failed' || error instanceof SyntaxError && error.status === 400) {
    return res.status(400).json({ error: 'Request body contains invalid JSON.' })
  }
  if (error.type === 'entity.too.large' || error.status === 413) return res.status(413).json({ error: 'Request body must be 1 MB or smaller.' })
  if (error.name === 'MulterError') {
    const message = error.code === 'LIMIT_FILE_SIZE' ? 'Image must be 10 MB or smaller.' : 'Multipart upload is invalid or exceeds its limits.'
    return res.status(400).json({ error: message })
  }
  if (['Only JPEG and PNG images are accepted.', 'Image dimensions are invalid or too large.', 'Image content does not match its file type.', 'Image is corrupt, truncated, or unsupported.'].includes(error.message)) return res.status(400).json({ error: error.message })
  console.error(error)
  return res.status(500).json({ error: 'Internal Server Error' })
}
