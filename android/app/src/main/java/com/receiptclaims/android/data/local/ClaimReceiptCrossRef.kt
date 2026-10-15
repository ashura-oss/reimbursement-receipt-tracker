package com.receiptclaims.android.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "claim_receipts",
    primaryKeys = ["claimId", "receiptOwnerUserId", "receiptClientReceiptId"],
    indices = [Index(value = ["receiptOwnerUserId", "receiptClientReceiptId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = ClaimEntity::class, parentColumns = ["id"], childColumns = ["claimId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReceiptEntity::class, parentColumns = ["ownerUserId", "clientReceiptId"], childColumns = ["receiptOwnerUserId", "receiptClientReceiptId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class ClaimReceiptCrossRef(
    val claimId: Long,
    val receiptOwnerUserId: Int,
    val receiptClientReceiptId: String
)
