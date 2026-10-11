import 'dotenv/config'
import express from 'express'
import cors from 'cors'
import authRoutes from './src/routes/authRoutes.js'
import categoryRoutes from './src/routes/categoryRoutes.js'
import receiptRoutes from './src/routes/receiptRoutes.js'
import claimRoutes from './src/routes/claimRoutes.js'
import * as errorMiddleware from './src/middlewares/errorMiddleware.js'

const app = express()
const port = Number(process.env.PORT || 3000)

app.use(cors())
app.use(express.json({ limit: '1mb' }))
app.get('/health', (req, res) => res.status(200).json({ message: 'Service is healthy.', data: { status: 'ok' } }))
app.use('/api/auth', authRoutes)
app.use('/api/categories', categoryRoutes)
app.use('/api/receipts', receiptRoutes)
app.use('/api/claims', claimRoutes)

// Report routes are optional during the foundation phase and are mounted when implemented.
const reportRoutesModule = await import('./src/routes/reportRoutes.js').catch(() => null)
if (reportRoutesModule?.default) app.use('/api/reports', reportRoutesModule.default)
const reportFileRoutesModule = await import('./src/routes/reportFileRoutes.js').catch(() => null)
if (reportFileRoutesModule?.default) app.use('/api/report-files', reportFileRoutesModule.default)

// Admin routes remain unreachable unless explicitly enabled in the environment.
if (process.env.ADMIN_PORTAL_ENABLED === 'true') {
  const adminRoutesModule = await import('./src/routes/adminRoutes.js').catch(() => null)
  if (adminRoutesModule?.default) app.use('/api/admin', adminRoutesModule.default)
}

app.use(errorMiddleware.notFoundMiddleware)
app.use(errorMiddleware.errorMiddleware)

if (process.env.NODE_ENV !== 'test') app.listen(port, () => console.log(`Receipt claims API listening on port ${port}`))

export default app
