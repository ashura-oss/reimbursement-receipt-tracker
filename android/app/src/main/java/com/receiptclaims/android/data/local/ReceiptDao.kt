package com.receiptclaims.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM receipts WHERE ownerUserId = :userId ORDER BY receiptDate DESC, createdAt DESC")
    fun observeForUser(userId: Int): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts ORDER BY receiptDate DESC, createdAt DESC, ownerUserId, clientReceiptId")
    fun observeAll(): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts WHERE ownerUserId = :userId AND clientReceiptId = :clientId LIMIT 1")
    suspend fun findForUser(userId: Int, clientId: String): ReceiptEntity?

    @Query("SELECT * FROM receipts ORDER BY ownerUserId, receiptDate, createdAt") suspend fun allReceipts(): List<ReceiptEntity>

    @Query("DELETE FROM receipts") suspend fun deleteAll()
    @Query("DELETE FROM receipts WHERE ownerUserId = :ownerId AND clientReceiptId = :clientId") suspend fun deleteByKey(ownerId: Int, clientId: String): Int

    @Query("SELECT * FROM receipts WHERE ownerUserId = :ownerId AND clientReceiptId = :clientId") suspend fun findByKey(ownerId: Int, clientId: String): ReceiptEntity?

    @Query("UPDATE receipts SET exchangeRate = :rate, sgdEquivalent = :sgd WHERE ownerUserId = :ownerId AND clientReceiptId = :clientId AND status = 'SAVED'")
    suspend fun updateExchange(ownerId: Int, clientId: String, rate: String, sgd: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(receipt: ReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(receipts: List<ReceiptEntity>)

}
