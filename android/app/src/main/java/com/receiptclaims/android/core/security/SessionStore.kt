package com.receiptclaims.android.core.security

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore by preferencesDataStore(name = "secure_session")

@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: TokenCipher
) {
    private val accessTokenKey = stringPreferencesKey("access_token_ciphertext")
    private val refreshTokenKey = stringPreferencesKey("refresh_token_ciphertext")

    suspend fun save(accessToken: String, refreshToken: String?) {
        context.sessionDataStore.edit { preferences ->
            preferences[accessTokenKey] = cipher.encrypt(accessToken)
            if (refreshToken == null) preferences.remove(refreshTokenKey)
            else preferences[refreshTokenKey] = cipher.encrypt(refreshToken)
        }
    }

    suspend fun accessToken(): String? = context.sessionDataStore.data.first()[accessTokenKey]
        ?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}
