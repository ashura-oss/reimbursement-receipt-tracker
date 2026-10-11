import 'dotenv/config'
import express from 'express'
import cors from 'cors'
import authRoutes from './src/routes/authRoutes.js'
import categoryRoutes from './src/routes/categoryRoutes.js'
import receiptRoutes from './src/routes/receiptRoutes.js'
import claimRoutes from './src/routes/claimRoutes.js'
import reportRoutes from './src/routes/reportRoutes.js'
import reportFileRoutes from './src/routes/reportFileRoutes.js'
import adminRoutes from './src/routes/adminRoutes.js'
import * as errorMiddleware from './src/middlewares/errorMiddleware.js'

const app = express()
const port = Number(process.env.PORT || 3000)

const configuredCorsOrigins = (process.env.CORS_ORIGINS || '').split(',').map((origin) => origin.trim()).filter(Boolean)
app.use(cors({
  origin: (origin, callback) => {
    if (!origin || configuredCorsOrigins.length === 0) return callback(null, process.env.NODE_ENV !== 'production' || !origin)
    return callback(null, configuredCorsOrigins.includes(origin))
  },
}))
app.use(express.json({ limit: '1mb' }))
app.get('/health', (req, res) => res.status(200).json({ message: 'Service is healthy.', data: { status: 'ok' } }))
app.use('/api/auth', authRoutes)
app.use('/api/categories', categoryRoutes)
app.use('/api/receipts', receiptRoutes)
app.use('/api/claims', claimRoutes)

app.use('/api/reports', reportRoutes)
app.use('/api/report-files', reportFileRoutes)

// The router itself returns 404 while disabled; mounting it here keeps the feature flag dynamic.
app.use('/api/admin', adminRoutes)

app.use(errorMiddleware.notFoundMiddleware)
app.use(errorMiddleware.errorMiddleware)

if (process.env.NODE_ENV !== 'test') app.listen(port, () => console.log(`Receipt claims API listening on port ${port}`))

export default app
