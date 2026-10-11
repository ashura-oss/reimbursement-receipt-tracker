package com.receiptclaims.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.core.network.ApiService
import com.receiptclaims.android.core.network.RegisterRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterUiState(val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {
    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    fun register(username: String, password: String, displayName: String, onCreated: () -> Unit) {
        when {
            username.isBlank() || password.isBlank() || displayName.isBlank() -> {
                _state.value = RegisterUiState(error = "Complete all fields.")
                return
            }
            password.length < 8 -> {
                _state.value = RegisterUiState(error = "Password must be at least 8 characters.")
                return
            }
        }
        viewModelScope.launch {
            _state.value = RegisterUiState(loading = true)
            runCatching { api.register(RegisterRequest(username.trim(), password, displayName.trim())) }
                .onSuccess { response ->
                    if (response.data == null) _state.value = RegisterUiState(error = "Account creation response was empty.")
                    else {
                        _state.value = RegisterUiState()
                        onCreated()
                    }
                }
                .onFailure { _state.value = RegisterUiState(error = it.message ?: "Unable to create account.") }
        }
    }
}
