import { isIP } from 'node:net'
import { ipKeyGenerator, rateLimit } from 'express-rate-limit'

export const clientIpKey = (request) => {
  let ip = String(request.ip || '').trim()

  // Some hosting proxies append their source port to an IPv4 address. The port
  // changes per connection, so including it would let clients evade limits.
  const ipv4WithPort = /^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$/.exec(ip)
  if (ipv4WithPort && isIP(ipv4WithPort[1]) === 4 && Number(ipv4WithPort[2]) <= 65535) {
    ip = ipv4WithPort[1]
  }

  // Accept the conventional bracketed IPv6-with-port form, while leaving a
  // plain IPv6 address intact for express-rate-limit's subnet normalization.
  const bracketedIpv6WithPort = /^\[([^\]]+)\]:(\d{1,5})$/.exec(ip)
  if (bracketedIpv6WithPort && isIP(bracketedIpv6WithPort[1]) === 6 && Number(bracketedIpv6WithPort[2]) <= 65535) {
    ip = bracketedIpv6WithPort[1]
  }

  // Invalid/missing proxy IPs share a conservative bucket instead of gaining
  // a unique key for each malformed value.
  if (isIP(ip) === 0) return 'unknown-client-ip'
  return ipKeyGenerator(ip)
}

const rateLimitOptions = (limit, message) => ({
  windowMs: 15 * 60 * 1000,
  limit,
  standardHeaders: 'draft-8',
  legacyHeaders: false,
  keyGenerator: clientIpKey,
  message: { error: message },
})

export const apiRateLimit = rateLimit(rateLimitOptions(120, 'Too many requests. Try again later.'))
export const authRateLimit = rateLimit(rateLimitOptions(10, 'Too many authentication attempts. Try again later.'))
export const uploadRateLimit = rateLimit(rateLimitOptions(20, 'Too many receipt uploads. Try again later.'))
