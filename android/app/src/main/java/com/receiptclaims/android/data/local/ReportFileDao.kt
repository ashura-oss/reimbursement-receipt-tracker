package com.receiptclaims.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportFileDao {
    @Query("SELECT * FROM report_files ORDER BY createdAt DESC") fun observeAll(): Flow<List<ReportFileEntity>>
    @Query("SELECT * FROM report_files WHERE id = :id LIMIT 1") suspend fun find(id: Long): ReportFileEntity?
    @Query("SELECT * FROM report_files ORDER BY createdAt DESC") suspend fun all(): List<ReportFileEntity>
    @Query("SELECT * FROM report_files WHERE claimId = :claimId ORDER BY createdAt DESC") suspend fun forClaim(claimId: Long): List<ReportFileEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(file: ReportFileEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(files: List<ReportFileEntity>)
    @Query("DELETE FROM report_files") suspend fun deleteAll()
    @Query("DELETE FROM report_files WHERE claimId = :claimId") suspend fun deleteForClaim(claimId: Long): Int
}
