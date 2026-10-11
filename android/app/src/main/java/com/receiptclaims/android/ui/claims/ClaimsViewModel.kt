package com.receiptclaims.android.ui.claims

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.core.network.ApiService
import com.receiptclaims.android.core.network.ClaimDto
import com.receiptclaims.android.core.network.CreateClaimRequest
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.repository.ReceiptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ClaimsUiState(val claims: List<ClaimDto> = emptyList(), val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class ClaimsViewModel @Inject constructor(
    private val api: ApiService,
    private val receiptRepository: ReceiptRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ClaimsUiState())
    val state: StateFlow<ClaimsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { api.claims() }
                .onSuccess { _state.value = ClaimsUiState(claims = it.data.orEmpty()) }
                .onFailure { _state.value = ClaimsUiState(error = it.message ?: "Unable to load claims.") }
        }
    }

    fun createClaim(title: String, receipts: List<ReceiptEntity>, onCreated: () -> Unit) {
        val ids = receipts.mapNotNull { it.serverId?.toIntOrNull() }
        if (title.isBlank() || ids.isEmpty()) {
            _state.value = _state.value.copy(error = "Upload at least one receipt before creating a claim.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { api.createClaim(CreateClaimRequest(title.trim(), receiptIds = ids)) }
                .onSuccess { _state.value = _state.value.copy(loading = false); onCreated() }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Unable to create claim.") }
        }
    }

    fun generateReport(claimId: Int) {
        viewModelScope.launch { runCatching { api.generateReport(claimId) } }
    }
}
