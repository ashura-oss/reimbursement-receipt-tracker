import 'dotenv/config'

export function isAdminPortalEnabled() {
  return String(process.env.ADMIN_PORTAL_ENABLED || 'false').toLowerCase() === 'true'
}

export function requireAdminPortal(req, res, next) {
  if (!isAdminPortalEnabled()) return res.status(404).json({ error: 'Admin portal is disabled.' })
  next()
}
