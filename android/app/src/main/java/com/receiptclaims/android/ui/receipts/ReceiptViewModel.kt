package com.receiptclaims.android.ui.receipts

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.data.local.CategoryEntity
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.repository.CategoryRepository
import com.receiptclaims.android.data.repository.ReceiptRepository
import com.receiptclaims.android.core.security.SessionStore
import com.receiptclaims.android.worker.UploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ReceiptRepository,
    private val categoryRepository: CategoryRepository,
    private val sessionStore: SessionStore
) : ViewModel() {
    data class SaveState(val saving: Boolean = false, val error: String? = null)
    data class RefreshState(val refreshing: Boolean = false, val error: String? = null, val imageWarning: Boolean = false)
    private val _saveState = kotlinx.coroutines.flow.MutableStateFlow(SaveState())
    val saveState: StateFlow<SaveState> = _saveState
    private val _refreshState = kotlinx.coroutines.flow.MutableStateFlow(RefreshState())
    val refreshState: StateFlow<RefreshState> = _refreshState
    val receipts: StateFlow<List<ReceiptEntity>> = repository.observeReceipts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            runCatching { categoryRepository.refresh() }.onFailure { if (it is CancellationException) throw it }
            runCatching { repository.refreshFromServer() }.onFailure { if (it is CancellationException) throw it }
            runCatching { val userId = sessionStore.snapshot()?.userId; if (userId != null) repository.pendingUploadIdsForCurrentUser().forEach { UploadScheduler.enqueue(context, it, userId) } }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    fun refreshReceipts() { viewModelScope.launch {
        _refreshState.value = RefreshState(refreshing = true)
        runCatching { repository.refreshFromServer() }
            .onSuccess { imagesUnavailable -> _refreshState.value = RefreshState(imageWarning = imagesUnavailable) }
            .onFailure { if (it is CancellationException) throw it; _refreshState.value = RefreshState(error = it.message ?: "Unable to refresh receipts. Tap refresh to retry.") }
    } }

    fun retryUpload(receipt: ReceiptEntity) {
        viewModelScope.launch {
            runCatching {
                val userId = requireNotNull(sessionStore.snapshot()?.userId) { "Sign in again to upload this receipt." }
                require(userId == receipt.ownerUserId) { "Sign in with the account that owns this receipt." }
                UploadScheduler.enqueue(context, receipt.clientReceiptId, userId)
            }.onFailure { _refreshState.value = RefreshState(error = it.message ?: "Unable to retry upload.") }
        }
    }

    fun saveReceipt(
        amount: String,
        currency: String,
        receiptDate: String,
        categoryId: Int,
        description: String?,
        note: String?,
        sourceUri: Uri,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            _saveState.value = SaveState(saving = true)
            var savedImagePath: String? = null
            runCatching {
                val snapshot = requireNotNull(sessionStore.snapshot()) { "Sign in again before saving receipts." }
                val path = copyToPrivateStorage(sourceUri)
                savedImagePath = path
                require(sessionStore.snapshot() == snapshot) { "Your account changed while saving. Please sign in again and retry." }
                val clientId = repository.createDraft(snapshot, amount, currency, receiptDate, categoryId, description, note, path)
                UploadScheduler.enqueue(context, clientId, snapshot.userId)
            }.onSuccess { _saveState.value = SaveState(); onSaved() }
            .onFailure { error -> if (error is CancellationException) throw error; savedImagePath?.let { File(it).delete() }; _saveState.value = SaveState(error = error.message ?: "Unable to save receipt.") }
        }
    }

    private suspend fun copyToPrivateStorage(sourceUri: Uri): String = withContext(Dispatchers.IO) {
        val output = File(context.filesDir, "receipt-images/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
        fun openInput() = if (sourceUri.scheme == "file") File(sourceUri.path!!).inputStream()
            else context.contentResolver.openInputStream(sourceUri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput().use { input ->
            requireNotNull(input) { "Unable to read selected image." }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected image could not be decoded." }
        val decodeOptions = BitmapFactory.Options().apply {
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= 2560 || bounds.outHeight / (sampleSize * 2) >= 2560) sampleSize *= 2
            inSampleSize = sampleSize
        }
        val bitmap = openInput().use { input ->
            requireNotNull(input) { "Unable to read selected image." }
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
        requireNotNull(bitmap) { "The selected image could not be decoded." }
        try {
            val orientation = openInput().use { input ->
                requireNotNull(input) { "Unable to read selected image." }
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                }
            }
            val normalized = if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else bitmap
            try {
                val compressed = ByteArrayOutputStream()
                require(normalized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, compressed)) { "Unable to encode receipt image." }
                require(compressed.size() <= 10 * 1024 * 1024) { "Receipt image must be 10 MB or smaller." }
                output.outputStream().use { compressed.writeTo(it) }
            } finally {
                if (normalized !== bitmap) normalized.recycle()
                bitmap.recycle()
            }
            output.absolutePath
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }
}
