package com.receiptclaims.android.ui

import android.content.Context
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.core.security.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

data class SessionUiState(val loading: Boolean = true, val authenticated: Boolean = false, val username: String? = null)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionStore: SessionStore,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var wasAuthenticated = false
            var firstSnapshot = true
            sessionStore.snapshotFlow().collect { snapshot ->
                val authenticated = snapshot != null
                if ((firstSnapshot && !authenticated) || (wasAuthenticated && !authenticated)) {
                    File(context.cacheDir, "report-downloads").deleteRecursively()
                }
                firstSnapshot = false
                wasAuthenticated = authenticated
                _state.value = SessionUiState(loading = false, authenticated = authenticated, username = snapshot?.username)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            sessionStore.clear()
            File(context.cacheDir, "report-downloads").deleteRecursively()
        }
    }
}
