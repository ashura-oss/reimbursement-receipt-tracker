import { Router } from 'express'
import * as authController from '../controllers/authController.js'
import * as authMiddleware from '../middlewares/authMiddleware.js'

const router = Router()
router.post('/register', authController.register)
router.post('/login', authController.login)
router.get('/me', authMiddleware.verifyToken, authController.getCurrentUser)

export default router
