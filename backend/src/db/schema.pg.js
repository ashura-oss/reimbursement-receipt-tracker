import { doublePrecision, index, integer, pgTable, serial, text, timestamp, uniqueIndex, boolean } from 'drizzle-orm/pg-core'

const timestampColumn = (name) => timestamp(name, { withTimezone: true, mode: 'date' })

export const users = pgTable('users', {
  id: serial('id').primaryKey(),
  username: text('username').notNull().unique(),
  displayName: text('display_name').notNull(),
  passwordHash: text('password_hash').notNull(),
  role: text('role').notNull().default('USER'),
  createdAt: timestampColumn('created_at').defaultNow().$defaultFn(() => new Date()).notNull(),
  updatedAt: timestampColumn('updated_at').defaultNow().$defaultFn(() => new Date()).notNull(),
})

export const receiptCategories = pgTable('receipt_categories', {
  id: serial('id').primaryKey(),
  name: text('name').notNull().unique(),
  active: boolean('active').notNull().default(true),
  createdAt: timestampColumn('created_at').defaultNow().$defaultFn(() => new Date()).notNull(),
})

export const receipts = pgTable('receipts', {
  id: serial('id').primaryKey(),
  userId: integer('user_id').notNull().references(() => users.id),
  clientReceiptId: text('client_receipt_id'),
  categoryId: integer('category_id').notNull().references(() => receiptCategories.id),
  receiptDate: timestampColumn('receipt_date').notNull(),
  amount: doublePrecision('amount').notNull(),
  currency: text('currency').notNull().default('SGD'),
  exchangeRate: doublePrecision('exchange_rate').notNull().default(1),
  sgdEquivalent: doublePrecision('sgd_equivalent').notNull(),
  description: text('description'),
  note: text('note'),
  storageKey: text('storage_key'),
  originalFileName: text('original_file_name'),
  mimeType: text('mime_type'),
  status: text('status').notNull().default('UNCLAIMED'),
  createdAt: timestampColumn('created_at').defaultNow().$defaultFn(() => new Date()).notNull(),
  updatedAt: timestampColumn('updated_at').defaultNow().$defaultFn(() => new Date()).notNull(),
}, (table) => ({
  clientReceiptUnique: uniqueIndex('receipts_client_receipt_unique_idx').on(table.userId, table.clientReceiptId),
  userDateIdx: index('receipts_user_date_idx').on(table.userId, table.receiptDate),
  categoryIdx: index('receipts_category_idx').on(table.categoryId),
}))

export const claims = pgTable('claims', {
  id: serial('id').primaryKey(),
  userId: integer('user_id').notNull().references(() => users.id),
  title: text('title').notNull(),
  startDate: timestampColumn('start_date'),
  endDate: timestampColumn('end_date'),
  status: text('status').notNull().default('DRAFT'),
  totalAmount: doublePrecision('total_amount').notNull().default(0),
  totalSgdEquivalent: doublePrecision('total_sgd_equivalent').notNull().default(0),
  submittedAt: timestampColumn('submitted_at'),
  createdAt: timestampColumn('created_at').defaultNow().$defaultFn(() => new Date()).notNull(),
  updatedAt: timestampColumn('updated_at').defaultNow().$defaultFn(() => new Date()).notNull(),
}, (table) => ({
  userStatusIdx: index('claims_user_status_idx').on(table.userId, table.status),
}))

export const claimReceipts = pgTable('claim_receipts', {
  id: serial('id').primaryKey(),
  claimId: integer('claim_id').notNull().references(() => claims.id),
  receiptId: integer('receipt_id').notNull().references(() => receipts.id),
  createdAt: timestampColumn('created_at').defaultNow().$defaultFn(() => new Date()).notNull(),
}, (table) => ({
  uniqueClaimReceipt: uniqueIndex('claim_receipt_unique_idx').on(table.claimId, table.receiptId),
    uniqueReceipt: uniqueIndex('claim_receipt_receipt_unique_idx').on(table.receiptId),
  claimIdx: index('claim_receipts_claim_idx').on(table.claimId),
}))

export const exchangeRates = pgTable('exchange_rates', {
  id: serial('id').primaryKey(),
  sourceCurrency: text('source_currency').notNull(),
  targetCurrency: text('target_currency').notNull().default('SGD'),
  rateDate: timestampColumn('rate_date').notNull(),
  rate: doublePrecision('rate').notNull(),
  provider: text('provider').notNull().default('frankfurter'),
  createdAt: timestampColumn('created_at').defaultNow().$defaultFn(() => new Date()).notNull(),
}, (table) => ({
  rateUnique: uniqueIndex('exchange_rate_unique_idx').on(table.sourceCurrency, table.targetCurrency, table.rateDate),
}))

export const reports = pgTable('reports', {
  id: serial('id').primaryKey(),
  userId: integer('user_id').notNull().references(() => users.id),
  claimId: integer('claim_id').notNull().references(() => claims.id),
  status: text('status').notNull().default('GENERATING'),
  createdAt: timestampColumn('created_at').defaultNow().$defaultFn(() => new Date()).notNull(),
  completedAt: timestampColumn('completed_at'),
  errorMessage: text('error_message'),
})

export const reportFiles = pgTable('report_files', {
  id: serial('id').primaryKey(),
  reportId: integer('report_id').notNull().references(() => reports.id),
  fileType: text('file_type').notNull(),
  storageKey: text('storage_key').notNull(),
  fileName: text('file_name').notNull(),
  mimeType: text('mime_type').notNull(),
  createdAt: timestampColumn('created_at').defaultNow().$defaultFn(() => new Date()).notNull(),
})

export const auditLogs = pgTable('audit_logs', {
  id: serial('id').primaryKey(),
  actorUserId: integer('actor_user_id').references(() => users.id),
  action: text('action').notNull(),
  resourceType: text('resource_type').notNull(),
  resourceId: integer('resource_id'),
  metadata: text('metadata'),
  createdAt: timestampColumn('created_at').defaultNow().$defaultFn(() => new Date()).notNull(),
})
