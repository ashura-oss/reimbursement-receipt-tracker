package com.receiptclaims.android.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val working: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList()
)

@HiltViewModel
class BackupViewModel @Inject constructor(private val backupService: BackupService) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun exportTo(uri: Uri) = run(uri, "Backup saved") { backupService.exportTo(uri) }
    fun importFrom(uri: Uri) = run(uri, "Restore completed") { backupService.importFrom(uri) }

    fun clearMessage() { _state.value = _state.value.copy(message = null, error = null, warnings = emptyList()) }

    private fun run(uri: Uri, fallback: String, operation: suspend (Uri) -> BackupResult) {
        viewModelScope.launch {
            _state.value = BackupUiState(working = true)
            try {
                val result = operation(uri)
                _state.value = BackupUiState(message = result.message.ifBlank { fallback }, warnings = result.warnings)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.value = BackupUiState(error = error.message ?: "The backup operation failed.")
            }
        }
    }
}
