package com.receiptclaims.android.core.security

import android.content.Context
import android.util.Base64
import org.json.JSONObject
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
    private val userIdKey = stringPreferencesKey("user_id")

    suspend fun save(accessToken: String, refreshToken: String?, userId: Int? = null) {
        context.sessionDataStore.edit { preferences ->
            preferences[accessTokenKey] = cipher.encrypt(accessToken)
            if (userId == null) preferences.remove(userIdKey) else preferences[userIdKey] = userId.toString()
            if (refreshToken == null) preferences.remove(refreshTokenKey)
            else preferences[refreshTokenKey] = cipher.encrypt(refreshToken)
        }
    }

    suspend fun accessToken(): String? = context.sessionDataStore.data.first()[accessTokenKey]
        ?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    suspend fun userId(): Int? {
        context.sessionDataStore.data.first()[userIdKey]?.toIntOrNull()?.let { return it }
        // Recover the id for sessions created by older builds; the backend JWT carries userId.
        val token = accessToken() ?: return null
        return runCatching {
            val encodedPayload = token.split('.').getOrNull(1) ?: return@runCatching null
            JSONObject(String(Base64.decode(encodedPayload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
                .optInt("userId").takeIf { it > 0 }
        }.getOrNull()
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}
