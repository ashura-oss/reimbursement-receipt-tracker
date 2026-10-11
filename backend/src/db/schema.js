import 'dotenv/config'
import * as sqliteSchema from './schema.sqlite.js'
import * as postgresSchema from './schema.pg.js'

const selectedSchema = process.env.DATABASE_PROVIDER === 'postgres' ? postgresSchema : sqliteSchema

export const users = selectedSchema.users
export const receiptCategories = selectedSchema.receiptCategories
export const receipts = selectedSchema.receipts
export const claims = selectedSchema.claims
export const claimReceipts = selectedSchema.claimReceipts
export const exchangeRates = selectedSchema.exchangeRates
export const reports = selectedSchema.reports
export const reportFiles = selectedSchema.reportFiles
export const auditLogs = selectedSchema.auditLogs
