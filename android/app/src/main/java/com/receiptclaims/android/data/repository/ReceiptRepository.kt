package com.receiptclaims.android.data.repository

import com.receiptclaims.android.core.network.ApiService
import com.receiptclaims.android.data.local.ReceiptDao
import com.receiptclaims.android.data.local.ReceiptEntity
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptRepository @Inject constructor(
    private val api: ApiService,
    private val dao: ReceiptDao
) {
    fun observeReceipts(): Flow<List<ReceiptEntity>> = dao.observeAll()

    suspend fun createDraft(
        amount: String,
        currency: String,
        receiptDate: String,
        categoryId: Int,
        description: String?,
        note: String?,
        imagePath: String
    ): String {
        val clientId = UUID.randomUUID().toString()
        dao.upsert(
            ReceiptEntity(
                clientReceiptId = clientId,
                amount = amount,
                currency = currency,
                receiptDate = receiptDate,
                categoryId = categoryId,
                description = description,
                note = note,
                imagePath = imagePath,
                status = ReceiptEntity.STATUS_PENDING_UPLOAD
            )
        )
        return clientId
    }

    suspend fun upload(clientId: String) {
        val receipt = dao.find(clientId) ?: return
        dao.updateStatus(clientId, ReceiptEntity.STATUS_UPLOADING)
        try {
            val file = File(receipt.imagePath)
            require(file.exists()) { "Receipt image is missing." }
            val image = MultipartBody.Part.createFormData(
                "image",
                file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )
            val plain = "text/plain".toMediaType()
            val response = api.uploadReceipt(
                clientReceiptId = receipt.clientReceiptId,
                image = image,
                amount = receipt.amount.toRequestBody(plain),
                currency = receipt.currency.toRequestBody(plain),
                receiptDate = receipt.receiptDate.toRequestBody(plain),
                categoryId = receipt.categoryId.toString().toRequestBody(plain),
                description = receipt.description?.toRequestBody(plain),
                note = receipt.note?.toRequestBody(plain)
            )
            val serverReceipt = requireNotNull(response.data) { "Receipt response was empty." }
            dao.markUploaded(clientId, serverReceipt.id.toString())
        } catch (error: Throwable) {
            dao.updateStatus(clientId, ReceiptEntity.STATUS_FAILED, error.message ?: "Upload failed")
            throw error
        }
    }

    suspend fun refreshFromServer() {
        val serverReceipts = requireNotNull(api.receipts().data)
        serverReceipts.forEach { receipt ->
            val current = dao.find(receipt.clientReceiptId ?: return@forEach) ?: return@forEach
            dao.upsert(current.copy(serverId = receipt.id.toString(), status = ReceiptEntity.STATUS_UPLOADED))
        }
    }
}
