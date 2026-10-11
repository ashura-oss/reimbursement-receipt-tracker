import 'dotenv/config'
import jwt from 'jsonwebtoken'

const JWT_SECRET = process.env.JWT_SECRET || (process.env.NODE_ENV === 'test' ? 'test-only-secret' : 'development-only-secret-change-me')
const JWT_EXPIRES_IN = process.env.JWT_EXPIRES_IN || '7d'

if (process.env.NODE_ENV === 'production' && (!process.env.JWT_SECRET || process.env.JWT_SECRET.length < 32)) {
  throw new Error('JWT_SECRET must be configured with at least 32 characters in production.')
}

export const verifyToken = (req, res, next) => {
  const header = req.get('Authorization')
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return res.status(401).json({ error: 'Authentication required.' })
  try {
    const payload = jwt.verify(header.slice(7), JWT_SECRET, { algorithms: ['HS256'] })
    if (!payload || !Number.isInteger(payload.userId) || payload.userId <= 0 || typeof payload.username !== 'string' || typeof payload.role !== 'string') {
      return res.status(401).json({ error: 'Authentication required.' })
    }
    res.locals.userId = payload.userId
    res.locals.username = payload.username
    res.locals.role = payload.role
    next()
  } catch (error) {
    return res.status(401).json({ error: 'Authentication required.' })
  }
}

export const signToken = ({ userId, username, role }) => jwt.sign({ userId, username, role }, JWT_SECRET, { algorithm: 'HS256', expiresIn: JWT_EXPIRES_IN })

export const requireAdmin = (req, res, next) => {
  if (res.locals.role !== 'ADMIN') return res.status(403).json({ error: 'Admin access required.' })
  next()
}
