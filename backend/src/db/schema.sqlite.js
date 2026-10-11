import { index, integer, real, sqliteTable, text, uniqueIndex } from 'drizzle-orm/sqlite-core'

const timestamp = (name) => integer(name, { mode: 'timestamp' })

export const users = sqliteTable('users', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  username: text('username').notNull().unique(),
  displayName: text('display_name').notNull(),
  passwordHash: text('password_hash').notNull(),
  role: text('role').notNull().default('USER'),
  createdAt: timestamp('created_at').$defaultFn(() => new Date()).notNull(),
  updatedAt: timestamp('updated_at').$defaultFn(() => new Date()).notNull(),
})

export const receiptCategories = sqliteTable('receipt_categories', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  name: text('name').notNull().unique(),
  active: integer('active', { mode: 'boolean' }).notNull().default(true),
  createdAt: timestamp('created_at').$defaultFn(() => new Date()).notNull(),
})

export const receipts = sqliteTable('receipts', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  userId: integer('user_id').notNull().references(() => users.id),
  clientReceiptId: text('client_receipt_id'),
  categoryId: integer('category_id').notNull().references(() => receiptCategories.id),
  receiptDate: timestamp('receipt_date').notNull(),
  amount: real('amount').notNull(),
  currency: text('currency').notNull().default('SGD'),
  exchangeRate: real('exchange_rate').notNull().default(1),
  sgdEquivalent: real('sgd_equivalent').notNull(),
  description: text('description'),
  note: text('note'),
  storageKey: text('storage_key'),
  originalFileName: text('original_file_name'),
  mimeType: text('mime_type'),
  status: text('status').notNull().default('UNCLAIMED'),
  createdAt: timestamp('created_at').$defaultFn(() => new Date()).notNull(),
  updatedAt: timestamp('updated_at').$defaultFn(() => new Date()).notNull(),
}, (table) => ({
  clientReceiptUnique: uniqueIndex('receipts_client_receipt_unique_idx').on(table.userId, table.clientReceiptId),
  userDateIdx: index('receipts_user_date_idx').on(table.userId, table.receiptDate),
  categoryIdx: index('receipts_category_idx').on(table.categoryId),
}))

export const claims = sqliteTable('claims', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  userId: integer('user_id').notNull().references(() => users.id),
  title: text('title').notNull(),
  startDate: timestamp('start_date'),
  endDate: timestamp('end_date'),
  status: text('status').notNull().default('DRAFT'),
  totalAmount: real('total_amount').notNull().default(0),
  totalSgdEquivalent: real('total_sgd_equivalent').notNull().default(0),
  submittedAt: timestamp('submitted_at'),
  createdAt: timestamp('created_at').$defaultFn(() => new Date()).notNull(),
  updatedAt: timestamp('updated_at').$defaultFn(() => new Date()).notNull(),
}, (table) => ({
  userStatusIdx: index('claims_user_status_idx').on(table.userId, table.status),
}))

export const claimReceipts = sqliteTable('claim_receipts', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  claimId: integer('claim_id').notNull().references(() => claims.id),
  receiptId: integer('receipt_id').notNull().references(() => receipts.id),
  createdAt: timestamp('created_at').$defaultFn(() => new Date()).notNull(),
}, (table) => ({
  uniqueClaimReceipt: uniqueIndex('claim_receipt_unique_idx').on(table.claimId, table.receiptId),
    uniqueReceipt: uniqueIndex('claim_receipt_receipt_unique_idx').on(table.receiptId),
  claimIdx: index('claim_receipts_claim_idx').on(table.claimId),
}))

export const exchangeRates = sqliteTable('exchange_rates', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  sourceCurrency: text('source_currency').notNull(),
  targetCurrency: text('target_currency').notNull().default('SGD'),
  rateDate: timestamp('rate_date').notNull(),
  rate: real('rate').notNull(),
  provider: text('provider').notNull().default('frankfurter'),
  createdAt: timestamp('created_at').$defaultFn(() => new Date()).notNull(),
}, (table) => ({
  rateUnique: uniqueIndex('exchange_rate_unique_idx').on(table.sourceCurrency, table.targetCurrency, table.rateDate),
}))

export const reports = sqliteTable('reports', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  userId: integer('user_id').notNull().references(() => users.id),
  claimId: integer('claim_id').notNull().references(() => claims.id),
  status: text('status').notNull().default('GENERATING'),
  createdAt: timestamp('created_at').$defaultFn(() => new Date()).notNull(),
  completedAt: timestamp('completed_at'),
  errorMessage: text('error_message'),
})

export const reportFiles = sqliteTable('report_files', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  reportId: integer('report_id').notNull().references(() => reports.id),
  fileType: text('file_type').notNull(),
  storageKey: text('storage_key').notNull(),
  fileName: text('file_name').notNull(),
  mimeType: text('mime_type').notNull(),
  createdAt: timestamp('created_at').$defaultFn(() => new Date()).notNull(),
})

export const auditLogs = sqliteTable('audit_logs', {
  id: integer('id').primaryKey({ autoIncrement: true }),
  actorUserId: integer('actor_user_id').references(() => users.id),
  action: text('action').notNull(),
  resourceType: text('resource_type').notNull(),
  resourceId: integer('resource_id'),
  metadata: text('metadata'),
  createdAt: timestamp('created_at').$defaultFn(() => new Date()).notNull(),
})
