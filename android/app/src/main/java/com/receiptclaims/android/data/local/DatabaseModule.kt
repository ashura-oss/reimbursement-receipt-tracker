package com.receiptclaims.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    internal val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE receipts_new (ownerUserId INTEGER NOT NULL, clientReceiptId TEXT NOT NULL, serverId TEXT, amount TEXT NOT NULL, currency TEXT NOT NULL, receiptDate TEXT NOT NULL, categoryId INTEGER NOT NULL, description TEXT, note TEXT, imagePath TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(ownerUserId, clientReceiptId))")
            db.execSQL("INSERT INTO receipts_new (ownerUserId, clientReceiptId, serverId, amount, currency, receiptDate, categoryId, description, note, imagePath, status, errorMessage, createdAt) SELECT ownerUserId, clientReceiptId, serverId, amount, currency, receiptDate, categoryId, description, note, imagePath, status, errorMessage, createdAt FROM receipts")
            db.execSQL("DROP TABLE receipts")
            db.execSQL("ALTER TABLE receipts_new RENAME TO receipts")
        }
    }

    internal val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE receipts ADD COLUMN exchangeRate TEXT")
            db.execSQL("ALTER TABLE receipts ADD COLUMN sgdEquivalent TEXT")
            db.execSQL("UPDATE receipts SET exchangeRate = '1', sgdEquivalent = amount WHERE currency = 'SGD'")
            db.execSQL("UPDATE receipts SET status = 'SAVED', errorMessage = NULL WHERE status != 'CLAIMED'")
            db.execSQL("ALTER TABLE categories ADD COLUMN accountCode TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS claims (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, startDate TEXT, endDate TEXT, claimantName TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS claim_receipts (claimId INTEGER NOT NULL, receiptOwnerUserId INTEGER NOT NULL, receiptClientReceiptId TEXT NOT NULL, PRIMARY KEY(claimId, receiptOwnerUserId, receiptClientReceiptId), FOREIGN KEY(claimId) REFERENCES claims(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(receiptOwnerUserId, receiptClientReceiptId) REFERENCES receipts(ownerUserId, clientReceiptId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_claim_receipts_receiptOwnerUserId_receiptClientReceiptId ON claim_receipts(receiptOwnerUserId, receiptClientReceiptId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS report_files (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, claimId INTEGER NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, mimeType TEXT NOT NULL, path TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(claimId) REFERENCES claims(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_report_files_claimId ON report_files(claimId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS local_settings (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
        }
    }

    internal val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE receipts ADD COLUMN name TEXT")
        }
    }

    internal val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE receipts ADD COLUMN expenseAccountId TEXT")
        }
    }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "receipt_claims.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

    @Provides
    fun receiptDao(database: AppDatabase): ReceiptDao = database.receiptDao()

    @Provides
    fun categoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides fun claimDao(database: AppDatabase): ClaimDao = database.claimDao()
    @Provides fun reportFileDao(database: AppDatabase): ReportFileDao = database.reportFileDao()
    @Provides fun localSettingDao(database: AppDatabase): LocalSettingDao = database.localSettingDao()
}
