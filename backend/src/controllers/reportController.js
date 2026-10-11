import * as reportService from '../services/reportService.js'

export const createReport = async (req, res) => {
  try {
    const claimId = Number(req.params.claimId || req.body?.claimId)
    if (!Number.isInteger(claimId) || claimId <= 0) return res.status(400).json({ error: 'A valid claimId is required.' })
    const result = await reportService.generateClaimReport({ userId: res.locals.userId, claimId })
    return res.status(201).json({ message: 'Report generated.', data: result })
  } catch (error) { console.error(error); if (error.statusCode === 404) return res.status(404).json({ error: 'Claim not found.' }); return res.status(500).json({ error: 'Internal Server Error' }) }
}

export const getReportById = async (req, res) => {
  try {
    const id = Number(req.params.id)
    if (!Number.isInteger(id)) return res.status(400).json({ error: 'Report id must be an integer.' })
    const report = await reportService.getReport(res.locals.userId, id)
    if (!report) return res.status(404).json({ error: 'Report not found.' })
    return res.status(200).json({ message: 'Report retrieved.', data: report })
  } catch (error) { console.error(error); return res.status(500).json({ error: 'Internal Server Error' }) }
}
