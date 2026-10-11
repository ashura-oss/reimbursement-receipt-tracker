import { Router } from 'express'
import * as authMiddleware from '../middlewares/authMiddleware.js'
import { receiptUpload } from '../middlewares/uploadMiddleware.js'
import * as receiptController from '../controllers/receiptController.js'

const router = Router()
router.use(authMiddleware.verifyToken)
router.get('/', receiptController.listReceipts)
router.post('/', receiptUpload, receiptController.createReceipt)
router.get('/:id', receiptController.getReceipt)
router.put('/:id', receiptUpload, receiptController.updateReceipt)
router.delete('/:id', receiptController.deleteReceipt)

export default router
