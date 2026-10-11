import { and, eq } from 'drizzle-orm'
import { db } from '../db/db.js'
import { reportFiles, reports } from '../db/schema.js'
import { readBuffer } from '../services/storageService.js'

export async function downloadReportFile(req, res, next) {
  try {
    const fileId = Number(req.params.id)
    const [row] = await db.select({ file: reportFiles, report: reports })
      .from(reportFiles).innerJoin(reports, eq(reportFiles.reportId, reports.id))
      .where(and(eq(reportFiles.id, fileId), eq(reports.userId, res.locals.userId))).limit(1)
    if (!row) return res.status(404).json({ error: 'Report file not found.' })
    const fileBuffer = await readBuffer(row.file.storageKey)
    res.type(row.file.mimeType).set('Content-Disposition', `attachment; filename="${row.file.fileName}"`).send(fileBuffer)
  } catch (error) { next(error) }
}
