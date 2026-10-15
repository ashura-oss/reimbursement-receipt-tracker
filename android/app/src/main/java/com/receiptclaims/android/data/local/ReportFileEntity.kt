package com.receiptclaims.android.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "report_files", indices = [Index("claimId")], foreignKeys = [ForeignKey(entity = ClaimEntity::class, parentColumns = ["id"], childColumns = ["claimId"], onDelete = ForeignKey.CASCADE)])
data class ReportFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val claimId: Long,
    val type: String,
    val name: String,
    val mimeType: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis()
)
