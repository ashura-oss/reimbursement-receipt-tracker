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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore by preferencesDataStore(name = "secure_session")

data class SessionSnapshot(val userId: Int, val accessToken: String, val username: String? = null)

@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: TokenCipher
) {
    private val accessTokenKey = stringPreferencesKey("access_token_ciphertext")
    private val refreshTokenKey = stringPreferencesKey("refresh_token_ciphertext")
    private val userIdKey = stringPreferencesKey("user_id")
    private val usernameKey = stringPreferencesKey("username")

    suspend fun save(accessToken: String, refreshToken: String?, userId: Int? = null, username: String? = null) {
        context.sessionDataStore.edit { preferences ->
            preferences[accessTokenKey] = cipher.encrypt(accessToken)
            if (userId == null) preferences.remove(userIdKey) else preferences[userIdKey] = userId.toString()
            if (username.isNullOrBlank()) preferences.remove(usernameKey) else preferences[usernameKey] = username.trim()
            if (refreshToken == null) preferences.remove(refreshTokenKey)
            else preferences[refreshTokenKey] = cipher.encrypt(refreshToken)
        }
    }

    suspend fun snapshot(): SessionSnapshot? {
        val preferences = context.sessionDataStore.data.first()
        val snapshot = snapshotFrom(preferences)
        if (snapshot == null) {
            val storedToken = preferences[accessTokenKey]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
            if (storedToken != null && isJwtExpired(storedToken, System.currentTimeMillis() / 1_000)) {
                clearIfAccessTokenMatches(storedToken)
            }
        }
        return snapshot
    }

    /** Clears only the credential that produced a failed request; a newer login is preserved. */
    suspend fun clearIfAccessTokenMatches(accessToken: String): Boolean {
        var cleared = false
        context.sessionDataStore.edit { preferences ->
            val storedToken = preferences[accessTokenKey]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
            if (storedToken == accessToken) {
                preferences.clear()
                cleared = true
            }
        }
        return cleared
    }

    suspend fun accessToken(): String? = snapshot()?.accessToken

    suspend fun userId(): Int? = snapshot()?.userId

    fun userIdFlow(): Flow<Int?> = snapshotFlow().map { it?.userId }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshotFlow(): Flow<SessionSnapshot?> = context.sessionDataStore.data.flatMapLatest { preferences ->
        val snapshot = snapshotFrom(preferences)
        flow {
            emit(snapshot)
            if (snapshot != null) {
                val expiresAt = jwtExpirationFrom(snapshot.accessToken)
                if (expiresAt != null) {
                    val remainingMillis = (expiresAt * 1_000 - System.currentTimeMillis()).coerceAtLeast(0)
                    if (remainingMillis > 0) delay(remainingMillis)
                    clearIfAccessTokenMatches(snapshot.accessToken)
                }
            } else {
                val storedToken = preferences[accessTokenKey]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
                if (storedToken != null && isJwtExpired(storedToken, System.currentTimeMillis() / 1_000)) {
                    clearIfAccessTokenMatches(storedToken)
                }
            }
        }
    }

    private fun snapshotFrom(preferences: androidx.datastore.preferences.core.Preferences): SessionSnapshot? {
        val token = preferences[accessTokenKey]?.let { runCatching { cipher.decrypt(it) }.getOrNull() } ?: return null
        if (isJwtExpired(token, System.currentTimeMillis() / 1_000)) return null
        val jwtUserId = userIdFromJwt(token)
        val storedUserId = preferences[userIdKey]?.toIntOrNull()
        // The identity from the same signed-in token is authoritative; preference identity is a legacy fallback.
        val userId = jwtUserId ?: storedUserId ?: return null
        return SessionSnapshot(userId, token, preferences[usernameKey])
    }

    private fun userIdFromJwt(token: String): Int? = runCatching {
        val encodedPayload = token.split('.').getOrNull(1) ?: return@runCatching null
        JSONObject(String(Base64.decode(encodedPayload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            .optInt("userId").takeIf { it > 0 }
    }.getOrNull()

    private fun isJwtExpired(token: String, nowEpochSeconds: Long): Boolean = runCatching {
        val expiresAt = jwtExpirationFrom(token) ?: return@runCatching false
        expiresAt > 0L && expiresAt <= nowEpochSeconds
    }.getOrDefault(false)

    private fun jwtExpirationFrom(token: String): Long? = runCatching {
        val encodedPayload = token.split('.').getOrNull(1) ?: return@runCatching null
        JSONObject(String(Base64.decode(encodedPayload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            .optLong("exp", 0L).takeIf { it > 0L }
    }.getOrNull()

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}
