import { and, eq } from 'drizzle-orm'
import { db } from '../db/db.js'
import { reportFiles, reports } from '../db/schema.js'
import { readBuffer } from '../services/storageService.js'

export async function downloadReportFile(req, res, next) {
  try {
    const fileId = Number(req.params.id)
    if (!Number.isSafeInteger(fileId) || fileId <= 0) return res.status(400).json({ error: 'Report file id must be a positive integer.' })
    const [row] = await db.select({ file: reportFiles, report: reports })
      .from(reportFiles).innerJoin(reports, eq(reportFiles.reportId, reports.id))
      .where(and(eq(reportFiles.id, fileId), eq(reports.userId, res.locals.userId))).limit(1)
    if (!row) return res.status(404).json({ error: 'Report file not found.' })
    const fileBuffer = await readBuffer(row.file.storageKey)
    const fileName = String(row.file.fileName || 'report-file').replace(/[\r\n"\\]/g, '')
    res.type(row.file.mimeType)
      .set('Cache-Control', 'private, no-store, max-age=0')
      .set('X-Content-Type-Options', 'nosniff')
      .set('Content-Disposition', `attachment; filename="${fileName}"`)
      .send(fileBuffer)
  } catch (error) { next(error) }
}
