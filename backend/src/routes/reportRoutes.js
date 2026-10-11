import { Router } from 'express'
import { createReport, getLatestClaimReport, getReportById } from '../controllers/reportController.js'
import { verifyToken } from '../middlewares/authMiddleware.js'

const router = Router()
router.use(verifyToken)
router.post('/claims/:claimId', createReport)
router.get('/claims/:claimId/latest', getLatestClaimReport)
router.get('/:id', getReportById)
export default router
