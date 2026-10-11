package com.receiptclaims.android.core.security

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore by preferencesDataStore(name = "secure_session")

data class SessionSnapshot(val userId: Int, val accessToken: String)

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

    suspend fun snapshot(): SessionSnapshot? = context.sessionDataStore.data.first().let(::snapshotFrom)

    suspend fun accessToken(): String? = snapshot()?.accessToken

    suspend fun userId(): Int? = snapshot()?.userId

    fun userIdFlow(): Flow<Int?> = context.sessionDataStore.data.map { snapshotFrom(it)?.userId }

    fun snapshotFlow(): Flow<SessionSnapshot?> = context.sessionDataStore.data.map(::snapshotFrom)

    private fun snapshotFrom(preferences: androidx.datastore.preferences.core.Preferences): SessionSnapshot? {
        val token = preferences[accessTokenKey]?.let { runCatching { cipher.decrypt(it) }.getOrNull() } ?: return null
        val jwtUserId = userIdFromJwt(token)
        val storedUserId = preferences[userIdKey]?.toIntOrNull()
        // The identity from the same signed-in token is authoritative; preference identity is a legacy fallback.
        val userId = jwtUserId ?: storedUserId ?: return null
        return SessionSnapshot(userId, token)
    }

    private fun userIdFromJwt(token: String): Int? = runCatching {
        val encodedPayload = token.split('.').getOrNull(1) ?: return@runCatching null
        JSONObject(String(Base64.decode(encodedPayload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            .optInt("userId").takeIf { it > 0 }
    }.getOrNull()

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}
