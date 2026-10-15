package com.receiptclaims.android.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.data.local.AppDatabase
import com.receiptclaims.android.data.local.CategoryEntity
import com.receiptclaims.android.data.local.ReceiptEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    val receipts: List<ReceiptEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val usage: StorageUsage? = null,
    val loading: Boolean = true,
    val working: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val deletePreview: StorageDeletionPreview? = null
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val database: AppDatabase,
    private val storageRepository: StorageRepository
) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            database.receiptDao().observeAll().collect { rows ->
                _state.value = _state.value.copy(receipts = rows, loading = false)
                refreshUsage()
            }
        }
        viewModelScope.launch { database.categoryDao().observeAll().collect { _state.value = _state.value.copy(categories = it) } }
        viewModelScope.launch { database.reportFileDao().observeAll().collect { refreshUsage() } }
    }

    private suspend fun refreshUsage() {
        runCatching { storageRepository.usage() }
            .onSuccess { _state.value = _state.value.copy(usage = it) }
            .onFailure { error -> if (error is CancellationException) throw error; _state.value = _state.value.copy(error = error.message ?: "Unable to read storage use.") }
    }

    fun previewDelete(keys: Set<ReceiptKey>) {
        if (keys.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(working = true, error = null, deletePreview = null)
            runCatching { storageRepository.previewDelete(keys) }
                .onSuccess { _state.value = _state.value.copy(working = false, deletePreview = it) }
                .onFailure { error -> if (error is CancellationException) throw error; _state.value = _state.value.copy(working = false, error = error.message ?: "Unable to prepare deletion.") }
        }
    }

    fun confirmDelete() {
        val preview = _state.value.deletePreview ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(working = true, error = null, deletePreview = null)
            runCatching { storageRepository.deleteReceipts(preview.selectedKeys, preview) }
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        working = false,
                        message = "Deleted ${result.receiptsDeleted} receipt(s), ${result.claimsDeleted} claim(s), and ${result.reportsDeleted} saved report(s).",
                        error = result.warnings.firstOrNull()
                    )
                    refreshUsage()
                }
                .onFailure { error -> if (error is CancellationException) throw error; _state.value = _state.value.copy(working = false, error = error.message ?: "Unable to delete the selected receipts.") }
        }
    }

    fun dismissDeletePreview() { _state.value = _state.value.copy(deletePreview = null) }
    fun clearMessage() { _state.value = _state.value.copy(message = null, error = null) }
}
