import { Router } from 'express'
import { downloadReportFile } from '../controllers/reportFileController.js'
import { verifyToken } from '../middlewares/authMiddleware.js'

const router = Router()
router.use(verifyToken)
router.get('/:id/download', downloadReportFile)
export default router
