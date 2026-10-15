package com.receiptclaims.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClaimDao {
    @Query("SELECT * FROM claims ORDER BY createdAt DESC, id DESC") fun observeAll(): Flow<List<ClaimEntity>>
    @Query("SELECT * FROM claims ORDER BY createdAt DESC, id DESC") suspend fun all(): List<ClaimEntity>
    @Query("SELECT * FROM claims WHERE id = :id") suspend fun find(id: Long): ClaimEntity?
    @Insert suspend fun insert(claim: ClaimEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(claim: ClaimEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertLinks(links: List<ClaimReceiptCrossRef>)
    @Query("SELECT * FROM claim_receipts ORDER BY claimId") suspend fun allLinks(): List<ClaimReceiptCrossRef>
    @Query("SELECT * FROM claim_receipts WHERE claimId = :claimId") suspend fun linksForClaim(claimId: Long): List<ClaimReceiptCrossRef>
    @Query("DELETE FROM claim_receipts") suspend fun deleteAllLinks()
    @Query("DELETE FROM claims") suspend fun deleteAllClaims()
    @Query("DELETE FROM claims WHERE id = :id") suspend fun deleteById(id: Long): Int
    @Query("SELECT EXISTS(SELECT 1 FROM claim_receipts WHERE receiptOwnerUserId = :ownerId AND receiptClientReceiptId = :clientId)") suspend fun isClaimed(ownerId: Int, clientId: String): Boolean
    @Query("SELECT receiptOwnerUserId || ':' || receiptClientReceiptId FROM claim_receipts") fun observeClaimedReceiptKeys(): Flow<List<String>>
}
