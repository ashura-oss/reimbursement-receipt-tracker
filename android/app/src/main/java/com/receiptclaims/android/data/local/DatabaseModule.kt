package com.receiptclaims.android.data.local

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "receipt_claims.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun receiptDao(database: AppDatabase): ReceiptDao = database.receiptDao()

    @Provides
    fun categoryDao(database: AppDatabase): CategoryDao = database.categoryDao()
}
