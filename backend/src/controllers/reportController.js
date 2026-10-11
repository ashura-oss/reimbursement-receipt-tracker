import * as reportService from '../services/reportService.js'

export const createReport = async (req, res) => {
  try {
    const claimId = Number(req.params.claimId || req.body?.claimId)
    if (!Number.isInteger(claimId) || claimId <= 0) return res.status(400).json({ error: 'A valid claimId is required.' })
    const result = await reportService.generateClaimReport({ userId: res.locals.userId, claimId })
    return res.status(201).json({ message: 'Report generated.', data: result })
  } catch (error) {
    console.error(error)
    if (error.statusCode === 404) return res.status(404).json({ error: 'Claim not found.' })
    if (error.statusCode === 503 && ['REPORT_GENERATION_IN_PROGRESS', 'REPORT_TEMPLATE_UNAVAILABLE', 'REPORT_RECEIPT_IMAGE_UNAVAILABLE'].includes(error.code)) return res.status(503).json({ error: error.message })
    if (error.statusCode === 422 && ['REPORT_TEMPLATE_INVALID', 'REPORT_RECEIPT_IMAGE_INVALID', 'REPORT_CLAIM_TOO_LARGE'].includes(error.code)) return res.status(422).json({ error: error.message })
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}

export const getReportById = async (req, res) => {
  try {
    const id = Number(req.params.id)
    if (!Number.isInteger(id)) return res.status(400).json({ error: 'Report id must be an integer.' })
    const data = await reportService.getReport(res.locals.userId, id)
    if (!data) return res.status(404).json({ error: 'Report not found.' })
    return res.status(200).json({ message: 'Report retrieved.', data })
  } catch (error) { console.error(error); return res.status(500).json({ error: 'Internal Server Error' }) }
}

export const getLatestClaimReport = async (req, res) => {
  try {
    const claimId = Number(req.params.claimId)
    if (!Number.isInteger(claimId) || claimId <= 0) return res.status(400).json({ error: 'Claim id must be a positive integer.' })
    const data = await reportService.getLatestClaimReport(res.locals.userId, claimId)
    if (!data) return res.status(404).json({ error: 'Report not found.' })
    return res.status(200).json({ message: 'Latest claim report retrieved.', data })
  } catch (error) { console.error(error); return res.status(500).json({ error: 'Internal Server Error' }) }
}
