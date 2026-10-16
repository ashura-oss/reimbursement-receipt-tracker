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
import com.receiptclaims.android.data.local.ClaimDao
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.repository.CategoryRepository
import com.receiptclaims.android.data.repository.ReceiptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
    private val claimDao: ClaimDao,
) : ViewModel() {
    data class SaveState(val saving: Boolean = false, val error: String? = null)
    private val _saveState = kotlinx.coroutines.flow.MutableStateFlow(SaveState())
    val saveState: StateFlow<SaveState> = _saveState
    val receipts: StateFlow<List<ReceiptEntity>> = repository.observeReceipts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val claimedReceiptKeys: StateFlow<Set<String>> = claimDao.observeClaimedReceiptKeys()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        viewModelScope.launch {
            categoryRepository.ensureDefaults()
        }
    }

    fun saveReceipt(
        name: String?,
        amount: String,
        currency: String,
        exchangeRate: String?,
        receiptDate: String,
        categoryId: Int,
        description: String?,
        note: String?,
        sourceUri: Uri,
        expenseAccountId: String? = null,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            _saveState.value = SaveState(saving = true)
            var savedImagePath: String? = null
            runCatching {
                val path = copyToPrivateStorage(sourceUri)
                savedImagePath = path
                repository.createReceipt(name, amount, currency, exchangeRate, receiptDate, categoryId, description, note, path, expenseAccountId)
            }.onSuccess { _saveState.value = SaveState(); onSaved() }
            .onFailure { error -> if (error is CancellationException) throw error; savedImagePath?.let { File(it).delete() }; _saveState.value = SaveState(error = error.message ?: "Unable to save receipt.") }
        }
    }

    fun releaseLegacyClaim(receipt: ReceiptEntity) {
        viewModelScope.launch {
            runCatching { repository.releaseLegacyClaim(receipt.ownerUserId, receipt.clientReceiptId) }
                .onFailure { if (it is CancellationException) throw it; _saveState.value = SaveState(error = it.message ?: "Unable to make this receipt available locally.") }
        }
    }

    fun setExchangeRate(receipt: ReceiptEntity, rate: String) {
        viewModelScope.launch {
            runCatching { repository.setExchangeRate(receipt.ownerUserId, receipt.clientReceiptId, rate) }
                .onFailure { if (it is CancellationException) throw it; _saveState.value = SaveState(error = it.message ?: "Unable to save the exchange rate.") }
        }
    }

    fun setExpenseAccount(receipt: ReceiptEntity, expenseAccountId: String?) {
        viewModelScope.launch {
            runCatching { repository.setExpenseAccount(receipt.ownerUserId, receipt.clientReceiptId, expenseAccountId) }
                .onFailure { if (it is CancellationException) throw it; _saveState.value = SaveState(error = it.message ?: "Unable to save the expense account.") }
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
