package com.receiptclaims.android.data.local

import androidx.room.Entity

@Entity(tableName = "receipts", primaryKeys = ["ownerUserId", "clientReceiptId"])
data class ReceiptEntity(
    val clientReceiptId: String,
    val ownerUserId: Int,
    val name: String? = null,
    val serverId: String? = null,
    val amount: String,
    val currency: String = "SGD",
    val exchangeRate: String? = "1",
    val sgdEquivalent: String? = amount,
    val receiptDate: String,
    val categoryId: Int,
    val description: String? = null,
    val note: String? = null,
    val imagePath: String = "",
    val status: String = STATUS_SAVED,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_SAVED = "SAVED"
        // Kept for migration compatibility with receipts claimed by the former online app.
        const val STATUS_CLAIMED = "CLAIMED"
    }
}
