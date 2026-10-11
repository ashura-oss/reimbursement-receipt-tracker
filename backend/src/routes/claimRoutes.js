import { Router } from 'express'
import * as authMiddleware from '../middlewares/authMiddleware.js'
import * as claimController from '../controllers/claimController.js'

const router = Router()
router.use(authMiddleware.verifyToken)
router.get('/', claimController.listClaims)
router.post('/', claimController.createClaim)
router.get('/:id', claimController.getClaim)
router.post('/:id/submit', claimController.submitClaim)

export default router
