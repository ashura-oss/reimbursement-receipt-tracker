package com.receiptclaims.android.data.repository

import android.content.Context
import com.receiptclaims.android.core.network.ApiService
import com.receiptclaims.android.core.security.SessionStore
import com.receiptclaims.android.core.security.SessionSnapshot
import com.receiptclaims.android.data.local.ReceiptDao
import com.receiptclaims.android.data.local.ReceiptEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val dao: ReceiptDao,
    private val sessionStore: SessionStore
) {
    class AccountMismatchException : IllegalStateException("Sign in with the account that owns this receipt to upload it.")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeReceipts(): Flow<List<ReceiptEntity>> = sessionStore.userIdFlow()
        .distinctUntilChanged()
        .flatMapLatest { userId -> if (userId == null) flowOf(emptyList()) else dao.observeForUser(userId) }

    suspend fun createDraft(
        expectedSession: SessionSnapshot,
        amount: String,
        currency: String,
        receiptDate: String,
        categoryId: Int,
        description: String?,
        note: String?,
        imagePath: String
    ): String {
        require(sessionStore.snapshot() == expectedSession) { "Your signed-in account changed before the receipt could be saved." }
        val ownerUserId = expectedSession.userId
        val clientId = UUID.randomUUID().toString()
        dao.upsert(
            ReceiptEntity(
                clientReceiptId = clientId,
                ownerUserId = ownerUserId,
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

    suspend fun upload(clientId: String, expectedOwnerUserId: Int) {
        val snapshot = sessionStore.snapshot() ?: throw AccountMismatchException()
        if (snapshot.userId != expectedOwnerUserId) throw AccountMismatchException()
        val receipt = dao.findForUser(expectedOwnerUserId, clientId) ?: return
        dao.updateStatus(expectedOwnerUserId, clientId, ReceiptEntity.STATUS_UPLOADING)
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
                image = image,
                clientReceiptId = receipt.clientReceiptId.toRequestBody(plain),
                amount = receipt.amount.toRequestBody(plain),
                currency = receipt.currency.toRequestBody(plain),
                receiptDate = receipt.receiptDate.toRequestBody(plain),
                categoryId = receipt.categoryId.toString().toRequestBody(plain),
                description = receipt.description?.toRequestBody(plain),
                note = receipt.note?.toRequestBody(plain),
                authorization = "Bearer ${snapshot.accessToken}"
            )
            val serverReceipt = requireNotNull(response.data) { "Receipt response was empty." }
            val active = sessionStore.snapshot()
            if (active?.userId != snapshot.userId || active.accessToken != snapshot.accessToken) throw AccountMismatchException()
            dao.markUploaded(expectedOwnerUserId, clientId, serverReceipt.id.toString())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            dao.updateStatus(expectedOwnerUserId, clientId, ReceiptEntity.STATUS_PENDING_UPLOAD)
            throw cancelled
        } catch (error: Throwable) {
            dao.updateStatus(expectedOwnerUserId, clientId, ReceiptEntity.STATUS_FAILED, error.message ?: "Upload failed")
            throw error
        }
    }

    suspend fun refreshFromServer(): Boolean {
        val snapshot = sessionStore.snapshot() ?: return false
        val userId = snapshot.userId
        val authorization = "Bearer ${snapshot.accessToken}"
        val serverReceipts = requireNotNull(api.receipts(authorization).data)
        var imageDownloadFailed = false
        serverReceipts.forEach { remote ->
            val serverId = remote.id.toString()
            val current = remote.clientReceiptId?.let { dao.findForUser(userId, it) }
                ?: dao.findByServerId(userId, serverId)
            var imagePath = current?.imagePath.orEmpty()
            if ((imagePath.isBlank() || !File(imagePath).exists()) && !remote.storageKey.isNullOrBlank()) {
                try {
                    val imageResponse = api.receiptImage(remote.id, authorization)
                    require(imageResponse.isSuccessful) { "Receipt image unavailable (${imageResponse.code()})." }
                    val body = requireNotNull(imageResponse.body())
                    imagePath = withContext(Dispatchers.IO) {
                        val imageFile = File(File(context.filesDir, "receipt-images").apply { mkdirs() }, "server-$serverId.jpg")
                        val temporaryFile = File(imageFile.parentFile, "${imageFile.name}.part")
                        temporaryFile.delete()
                        try {
                            body.byteStream().use { input -> temporaryFile.outputStream().use { output -> input.copyTo(output) } }
                            require(temporaryFile.length() > 0) { "Receipt image was empty." }
                            require(temporaryFile.renameTo(imageFile)) { "Unable to save receipt image." }
                        } catch (error: Throwable) {
                            temporaryFile.delete()
                            throw error
                        }
                        imageFile.absolutePath
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    imageDownloadFailed = true
                }
            }
            val entity = ReceiptEntity(
                clientReceiptId = current?.clientReceiptId ?: remote.clientReceiptId ?: "server-$serverId",
                ownerUserId = userId,
                serverId = serverId,
                amount = remote.amount,
                currency = remote.currency,
                receiptDate = remote.receiptDate.take(10),
                categoryId = remote.categoryId,
                description = remote.description,
                note = remote.note,
                imagePath = imagePath,
                status = if (remote.status.equals("CLAIMED", ignoreCase = true)) ReceiptEntity.STATUS_CLAIMED else ReceiptEntity.STATUS_UPLOADED,
                createdAt = current?.createdAt ?: System.currentTimeMillis()
            )
            // A user/token switch while sync was in flight must not persist this response.
            if (sessionStore.snapshot() == snapshot) {
                dao.upsert(entity)
                // Older app versions could have inserted the server receipt under a synthetic
                // server-$id key before the API exposed clientReceiptId. Once the UUID arrives,
                // retain the UUID row (and its local image) and remove the duplicate server row.
                dao.deleteDuplicateServerRows(userId, serverId, entity.clientReceiptId)
            }
        }
        return imageDownloadFailed
    }

    suspend fun markClaimed(userId: Int, serverIds: List<String>) {
        if (serverIds.isEmpty()) return
        if (sessionStore.snapshot()?.userId != userId) return
        dao.markClaimed(userId, serverIds)
    }

    suspend fun pendingUploadIdsForCurrentUser(): List<String> {
        val snapshot = sessionStore.snapshot() ?: return emptyList()
        return dao.pendingUploads(snapshot.userId).map { it.clientReceiptId }
    }
}
