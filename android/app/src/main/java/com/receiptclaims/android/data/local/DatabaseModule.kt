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

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "receipt_claims.db")
            .addMigrations(MIGRATION_2_3)
            .build()

    @Provides
    fun receiptDao(database: AppDatabase): ReceiptDao = database.receiptDao()

    @Provides
    fun categoryDao(database: AppDatabase): CategoryDao = database.categoryDao()
}
