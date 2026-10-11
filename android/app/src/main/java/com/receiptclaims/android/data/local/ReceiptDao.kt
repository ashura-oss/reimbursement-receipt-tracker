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

    @Query("SELECT * FROM receipts WHERE clientReceiptId = :id LIMIT 1")
    suspend fun find(id: String): ReceiptEntity?

    @Query("SELECT * FROM receipts WHERE ownerUserId = :userId AND status IN ('PENDING_UPLOAD', 'FAILED') ORDER BY createdAt ASC")
    suspend fun pendingUploads(userId: Int): List<ReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(receipt: ReceiptEntity)

    @Query("UPDATE receipts SET status = :status, errorMessage = :errorMessage WHERE clientReceiptId = :id")
    suspend fun updateStatus(id: String, status: String, errorMessage: String? = null)

    @Query("UPDATE receipts SET serverId = :serverId, status = :status, errorMessage = NULL WHERE clientReceiptId = :id")
    suspend fun markUploaded(id: String, serverId: String, status: String = ReceiptEntity.STATUS_UPLOADED)

    @Query("UPDATE receipts SET status = 'CLAIMED' WHERE ownerUserId = :userId AND serverId IN (:serverIds)")
    suspend fun markClaimed(userId: Int, serverIds: List<String>)
}
