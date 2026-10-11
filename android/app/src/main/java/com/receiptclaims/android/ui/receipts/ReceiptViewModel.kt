package com.receiptclaims.android.ui.receipts

import android.content.Context
import android.net.Uri
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
            val path = copyToPrivateStorage(sourceUri)
            val clientId = repository.createDraft(amount, currency, receiptDate, categoryId, description, note, path)
            UploadScheduler.enqueue(context, clientId)
            onSaved()
        }
    }

    private suspend fun copyToPrivateStorage(sourceUri: Uri): String = withContext(Dispatchers.IO) {
        val output = File(context.filesDir, "receipt-images/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
        if (sourceUri.scheme == "file") File(sourceUri.path!!).inputStream().use { input -> output.outputStream().use(input::copyTo) }
        else context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Unable to read selected image." }
            output.outputStream().use(input::copyTo)
        }
        output.absolutePath
    }
}
