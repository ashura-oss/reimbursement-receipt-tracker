import { Router } from 'express'
import { getClaim, getReceiptImage, listClaims, listUsers } from '../controllers/adminController.js'
import { requireAdmin, verifyToken } from '../middlewares/authMiddleware.js'
import { requireAdminPortal } from '../middlewares/adminPortalMiddleware.js'

const router = Router()
router.use(requireAdminPortal, verifyToken, requireAdmin)
router.get('/users', listUsers)
router.get('/claims', listClaims)
router.get('/claims/:id', getClaim)
router.get('/receipts/:id/image', getReceiptImage)
export default router
