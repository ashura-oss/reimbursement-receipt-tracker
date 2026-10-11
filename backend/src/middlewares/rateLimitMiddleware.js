import { rateLimit } from 'express-rate-limit'

const rateLimitOptions = (limit, message) => ({
  windowMs: 15 * 60 * 1000,
  limit,
  standardHeaders: 'draft-8',
  legacyHeaders: false,
  message: { error: message },
})

export const apiRateLimit = rateLimit(rateLimitOptions(120, 'Too many requests. Try again later.'))
export const authRateLimit = rateLimit(rateLimitOptions(10, 'Too many authentication attempts. Try again later.'))
export const uploadRateLimit = rateLimit(rateLimitOptions(20, 'Too many receipt uploads. Try again later.'))
