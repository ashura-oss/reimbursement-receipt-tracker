package com.receiptclaims.android.data.local

import androidx.room.Entity

@Entity(tableName = "receipts", primaryKeys = ["ownerUserId", "clientReceiptId"])
data class ReceiptEntity(
    val clientReceiptId: String,
    val ownerUserId: Int,
    val serverId: String? = null,
    val amount: String,
    val currency: String = "SGD",
    val receiptDate: String,
    val categoryId: Int,
    val description: String? = null,
    val note: String? = null,
    val imagePath: String = "",
    val status: String = STATUS_PENDING_UPLOAD,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_DRAFT = "DRAFT"
        const val STATUS_PENDING_UPLOAD = "PENDING_UPLOAD"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_UPLOADED = "UPLOADED"
        const val STATUS_CLAIMED = "CLAIMED"
        const val STATUS_FAILED = "FAILED"
    }
}
