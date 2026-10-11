package com.receiptclaims.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    @Query("SELECT COUNT(*) FROM receipts")
    suspend fun countAll(): Int

    @Query("SELECT * FROM receipts WHERE ownerUserId = :userId ORDER BY receiptDate DESC, createdAt DESC")
    fun observeForUser(userId: Int): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts WHERE ownerUserId = :userId AND clientReceiptId = :clientId LIMIT 1")
    suspend fun findForUser(userId: Int, clientId: String): ReceiptEntity?

    @Query("SELECT * FROM receipts WHERE ownerUserId = :userId AND serverId = :serverId LIMIT 1")
    suspend fun findByServerId(userId: Int, serverId: String): ReceiptEntity?

    @Query("DELETE FROM receipts WHERE ownerUserId = :userId AND serverId = :serverId AND clientReceiptId != :keepClientId")
    suspend fun deleteDuplicateServerRows(userId: Int, serverId: String, keepClientId: String)

    @Query("SELECT * FROM receipts WHERE ownerUserId = :userId AND status IN ('PENDING_UPLOAD', 'FAILED', 'UPLOADING') ORDER BY createdAt ASC")
    suspend fun pendingUploads(userId: Int): List<ReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(receipt: ReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(receipts: List<ReceiptEntity>)

    @Query("UPDATE receipts SET status = :status, errorMessage = :errorMessage WHERE ownerUserId = :userId AND clientReceiptId = :id")
    suspend fun updateStatus(userId: Int, id: String, status: String, errorMessage: String? = null)

    @Query("UPDATE receipts SET serverId = :serverId, status = :status, errorMessage = NULL WHERE ownerUserId = :userId AND clientReceiptId = :id")
    suspend fun markUploaded(userId: Int, id: String, serverId: String, status: String = ReceiptEntity.STATUS_UPLOADED)

    @Query("UPDATE receipts SET status = 'CLAIMED' WHERE ownerUserId = :userId AND serverId IN (:serverIds)")
    suspend fun markClaimed(userId: Int, serverIds: List<String>)
}
