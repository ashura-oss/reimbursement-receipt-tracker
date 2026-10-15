package com.receiptclaims.android.data.repository

import androidx.room.withTransaction
import com.receiptclaims.android.data.local.AppDatabase
import com.receiptclaims.android.data.local.ClaimEntity
import com.receiptclaims.android.data.local.ClaimReceiptCrossRef
import com.receiptclaims.android.data.local.ReceiptDao
import com.receiptclaims.android.data.local.ReceiptEntity
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.File
import android.graphics.BitmapFactory
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptRepository @Inject constructor(
    private val dao: ReceiptDao,
    private val database: AppDatabase
) {
    companion object { const val LOCAL_OWNER_ID = 0 }

    fun observeReceipts(): Flow<List<ReceiptEntity>> = dao.observeAll()

    suspend fun createReceipt(
        name: String?,
        amount: String,
        currency: String,
        exchangeRate: String?,
        receiptDate: String,
        categoryId: Int,
        description: String?,
        note: String?,
        imagePath: String
    ): String {
        val parsedAmount = validatePositiveAmount(amount, "Amount")
        require(currency.matches(Regex("[A-Z]{3}"))) { "Choose a valid three-letter currency." }
        val rate = if (currency == "SGD") BigDecimal.ONE else validatePositiveAmount(requireNotNull(exchangeRate) { "Enter the exchange rate to SGD." }, "Exchange rate", maxScale = 8)
        val sgd = parsedAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP)
        require(runCatching { LocalDate.parse(receiptDate) }.isSuccess) { "Enter a valid date as YYYY-MM-DD." }
        require(imagePath.isNotBlank()) { "Add a receipt image first." }
        require((description?.length ?: 0) <= 2_000 && (note?.length ?: 0) <= 2_000) { "Description and note can each be at most 2,000 characters." }
        require((name?.length ?: 0) <= 120) { "Receipt name can be at most 120 characters." }
        val clientId = UUID.randomUUID().toString()
        dao.upsert(ReceiptEntity(
            clientReceiptId = clientId,
            ownerUserId = LOCAL_OWNER_ID,
            name = name?.trim()?.takeIf(String::isNotEmpty),
            amount = parsedAmount.stripTrailingZeros().toPlainString(),
            currency = currency,
            exchangeRate = rate.stripTrailingZeros().toPlainString(),
            sgdEquivalent = sgd.toPlainString(),
            receiptDate = receiptDate,
            categoryId = categoryId,
            description = description,
            note = note,
            imagePath = imagePath,
            status = ReceiptEntity.STATUS_SAVED
        ))
        return clientId
    }

    suspend fun createClaim(title: String, startDate: String?, endDate: String?, receipts: List<ReceiptEntity>, claimantName: String): Long {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= 120) { "Enter a claim title up to 120 characters." }
        fun date(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)?.also {
            require(runCatching { LocalDate.parse(it) }.isSuccess) { "Enter valid dates as YYYY-MM-DD." }
        }
        val start = date(startDate)
        val end = date(endDate)
        require(start == null || end == null || !LocalDate.parse(end).isBefore(LocalDate.parse(start))) { "The end date must not precede the start date." }
        require(receipts.isNotEmpty()) { "Select at least one receipt." }
        require(receipts.size <= 500) { "A claim can contain at most 500 receipts." }
        require(receipts.map { it.ownerUserId to it.clientReceiptId }.toSet().size == receipts.size) { "A receipt can only appear once in a claim." }
        require(receipts.all { it.status == ReceiptEntity.STATUS_SAVED }) { "One or more receipts are already claimed or unavailable." }
        require(receipts.all { it.exchangeRate != null && it.sgdEquivalent != null }) { "Enter a manual SGD exchange rate for each foreign-currency receipt before creating this claim." }
        val images = receipts.map { File(it.imagePath) }
        require(images.all { it.isFile && it.length() in 1..(10L * 1024 * 1024) }) { "Every receipt needs a readable image no larger than 10 MiB before you can create a claim." }
        require(images.sumOf { it.length() } <= 50L * 1024 * 1024) { "Images in one claim must total 50 MiB or less." }
        require(images.all { image ->
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, dimensions)
            dimensions.outWidth > 0 && dimensions.outHeight > 0 && dimensions.outWidth.toLong() * dimensions.outHeight <= 40_000_000L
        }) { "Every receipt image must be readable and no larger than 40 megapixels." }
        return database.withTransaction {
            receipts.forEach { receipt ->
                val current = dao.findForUser(receipt.ownerUserId, receipt.clientReceiptId)
                require(current == receipt && receipt.status == ReceiptEntity.STATUS_SAVED) { "A selected receipt changed. Refresh and try again." }
            }
            val claimId = database.claimDao().insert(ClaimEntity(title = cleanTitle, startDate = start, endDate = end, claimantName = claimantName.trim()))
            database.claimDao().insertLinks(receipts.map { ClaimReceiptCrossRef(claimId, it.ownerUserId, it.clientReceiptId) })
            claimId
        }
    }

    suspend fun receiptsForClaim(claimId: Long): List<ReceiptEntity> = database.claimDao().linksForClaim(claimId).mapNotNull {
        dao.findByKey(it.receiptOwnerUserId, it.receiptClientReceiptId)
    }

    suspend fun releaseLegacyClaim(ownerId: Int, clientId: String) {
        database.withTransaction {
            require(!database.claimDao().isClaimed(ownerId, clientId)) { "This receipt belongs to a local claim and cannot be released." }
            database.openHelper.writableDatabase.execSQL("UPDATE receipts SET status = 'SAVED', errorMessage = NULL WHERE ownerUserId = ? AND clientReceiptId = ? AND status = 'CLAIMED'", arrayOf(ownerId, clientId))
        }
    }

    suspend fun setExchangeRate(ownerId: Int, clientId: String, rateText: String) {
        val rate = validatePositiveAmount(rateText, "Exchange rate", maxScale = 8)
        require(rate <= BigDecimal("1000000")) { "Exchange rate must be no more than 1,000,000." }
        database.withTransaction {
            require(!database.claimDao().isClaimed(ownerId, clientId)) { "Receipts in a local claim cannot be edited." }
            val receipt = dao.findForUser(ownerId, clientId) ?: error("Receipt was not found.")
            require(receipt.status == ReceiptEntity.STATUS_SAVED && receipt.currency != "SGD") { "Only unclaimed foreign-currency receipts can be edited." }
            val sgd = BigDecimal(receipt.amount).multiply(rate).setScale(2, RoundingMode.HALF_UP)
            require(dao.updateExchange(ownerId, clientId, rate.stripTrailingZeros().toPlainString(), sgd.toPlainString()) == 1) { "Receipt changed before the rate was saved." }
        }
    }

    private fun validatePositiveAmount(value: String, label: String, maxScale: Int = 6): BigDecimal {
        val number = value.toBigDecimalOrNull()
        val maximum = if (label == "Exchange rate") BigDecimal("1000000") else BigDecimal("1000000000")
        require(number != null && number > BigDecimal.ZERO && number <= maximum && number.scale().coerceAtLeast(0) <= maxScale) {
            "$label must be greater than 0 and no more than ${maximum.toPlainString()}, with at most $maxScale decimal places."
        }
        return number
    }
}
