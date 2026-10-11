package com.receiptclaims.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.core.network.ApiService
import com.receiptclaims.android.core.network.LoginRequest
import com.receiptclaims.android.core.security.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val api: ApiService,
    private val sessionStore: SessionStore
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun login(username: String, password: String, onSuccess: () -> Unit) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = LoginUiState(error = "Enter your username and password.")
            return
        }
        viewModelScope.launch {
            _state.value = LoginUiState(loading = true)
            runCatching { api.login(LoginRequest(username.trim(), password)) }
                .onSuccess { response ->
                    val payload = response.data
                    if (payload == null) _state.value = LoginUiState(error = "Login response was empty.")
                    else {
                        sessionStore.save(payload.token, null)
                        _state.value = LoginUiState()
                        onSuccess()
                    }
                }
                .onFailure { _state.value = LoginUiState(error = it.message ?: "Unable to sign in.") }
        }
    }
}
