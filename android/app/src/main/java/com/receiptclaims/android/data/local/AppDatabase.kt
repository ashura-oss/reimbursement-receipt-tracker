package com.receiptclaims.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ReceiptEntity::class, CategoryEntity::class, ClaimEntity::class, ClaimReceiptCrossRef::class, ReportFileEntity::class, LocalSettingEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao
    abstract fun categoryDao(): CategoryDao
    abstract fun claimDao(): ClaimDao
    abstract fun reportFileDao(): ReportFileDao
    abstract fun localSettingDao(): LocalSettingDao
}
