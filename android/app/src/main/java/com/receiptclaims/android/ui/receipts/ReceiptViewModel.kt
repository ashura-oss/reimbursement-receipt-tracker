package com.receiptclaims.android.ui.receipts

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.data.local.CategoryEntity
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.repository.CategoryRepository
import com.receiptclaims.android.data.repository.ReceiptRepository
import com.receiptclaims.android.worker.UploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    data class SaveState(val saving: Boolean = false, val error: String? = null)
    private val _saveState = kotlinx.coroutines.flow.MutableStateFlow(SaveState())
    val saveState: StateFlow<SaveState> = _saveState
    val receipts: StateFlow<List<ReceiptEntity>> = repository.observeReceipts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { viewModelScope.launch { runCatching { categoryRepository.refresh() } } }

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
            runCatching {
                val path = copyToPrivateStorage(sourceUri)
                val clientId = repository.createDraft(amount, currency, receiptDate, categoryId, description, note, path)
                UploadScheduler.enqueue(context, clientId)
            }.onSuccess { _saveState.value = SaveState(); onSaved() }
                .onFailure { _saveState.value = SaveState(error = it.message ?: "Unable to save receipt.") }
        }
    }

    private suspend fun copyToPrivateStorage(sourceUri: Uri): String = withContext(Dispatchers.IO) {
        val output = File(context.filesDir, "receipt-images/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
        val bitmap = if (sourceUri.scheme == "file") File(sourceUri.path!!).inputStream().use(BitmapFactory::decodeStream)
        else context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Unable to read selected image." }
            BitmapFactory.decodeStream(input)
        }
        requireNotNull(bitmap) { "The selected image could not be decoded." }
        val compressed = ByteArrayOutputStream()
        require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, compressed)) { "Unable to encode receipt image." }
        require(compressed.size() <= 10 * 1024 * 1024) { "Receipt image must be 10 MB or smaller." }
        output.outputStream().use { compressed.writeTo(it) }
        output.absolutePath
    }
}
