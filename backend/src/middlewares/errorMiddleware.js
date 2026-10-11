export const notFoundMiddleware = (req, res) => res.status(404).json({ error: 'Route not found.' })

export const errorMiddleware = (error, req, res, next) => {
  console.error(error)
  if (res.headersSent) return next(error)
  if (error.message === 'Only image files are accepted.' || error.code === 'LIMIT_FILE_SIZE') {
    return res.status(400).json({ error: error.message === 'LIMIT_FILE_SIZE' ? 'Image must be 10 MB or smaller.' : error.message })
  }
  return res.status(500).json({ error: 'Internal Server Error' })
}
