import { Router } from 'express'
import * as authMiddleware from '../middlewares/authMiddleware.js'
import { receiptUpload } from '../middlewares/uploadMiddleware.js'
import * as receiptController from '../controllers/receiptController.js'
import { uploadRateLimit } from '../middlewares/rateLimitMiddleware.js'

const router = Router()
router.use(authMiddleware.verifyToken)
router.get('/', receiptController.listReceipts)
router.post('/', ...(process.env.NODE_ENV === 'production' ? [uploadRateLimit] : []), receiptUpload, receiptController.createReceipt)
router.get('/:id', receiptController.getReceipt)
router.get('/:id/image', receiptController.getReceiptImage)
router.put('/:id', receiptUpload, receiptController.updateReceipt)
router.delete('/:id', receiptController.deleteReceipt)

export default router
