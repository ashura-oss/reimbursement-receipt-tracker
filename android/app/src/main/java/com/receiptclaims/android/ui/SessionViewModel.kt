package com.receiptclaims.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.core.security.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionUiState(val loading: Boolean = true, val authenticated: Boolean = false)

@HiltViewModel
class SessionViewModel @Inject constructor(private val sessionStore: SessionStore) : ViewModel() {
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    init { viewModelScope.launch { _state.value = SessionUiState(loading = false, authenticated = !sessionStore.accessToken().isNullOrBlank()) } }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            sessionStore.clear()
            _state.value = SessionUiState(loading = false, authenticated = false)
            onLoggedOut()
        }
    }
}
