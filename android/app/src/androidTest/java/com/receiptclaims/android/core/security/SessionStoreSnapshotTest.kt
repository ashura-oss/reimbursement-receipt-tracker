package com.receiptclaims.android.core.security

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionStoreSnapshotTest {
    private lateinit var store: SessionStore

    @Before
    fun setUp() = runBlocking {
        store = SessionStore(ApplicationProvider.getApplicationContext<Context>(), TokenCipher())
        store.clear()
    }

    @Test
    fun identityAndBearerTokenAreReadAsOneReactiveSnapshot() = runBlocking {
        val firstToken = tokenFor(101)
        store.save(firstToken, refreshToken = null, userId = 101)
        assertEquals(SessionSnapshot(101, firstToken), store.snapshot())

        val snapshots = async(start = CoroutineStart.UNDISPATCHED) {
            store.snapshotFlow().take(2).toList()
        }
        val secondToken = tokenFor(202)
        store.save(secondToken, refreshToken = null, userId = 202)

        assertEquals(
            listOf(SessionSnapshot(101, firstToken), SessionSnapshot(202, secondToken)),
            snapshots.await()
        )
        store.clear()
    }

    @Test
    fun expiredJwtIsNotAnAuthenticatedSession() = runBlocking {
        val expiredToken = tokenFor(303, expiresAt = System.currentTimeMillis() / 1_000 - 1)
        store.save(expiredToken, refreshToken = null, userId = 303)

        assertNull(store.snapshot())
        assertNull(store.snapshotFlow().take(1).toList().single())
    }

    @Test
    fun unauthorizedResponseOnlyClearsTheSessionThatSentItsToken() = runBlocking {
        val oldToken = tokenFor(404)
        val newToken = tokenFor(505)
        store.save(oldToken, refreshToken = null, userId = 404)
        store.save(newToken, refreshToken = null, userId = 505)

        assertFalse(store.clearIfAccessTokenMatches(oldToken))
        assertEquals(SessionSnapshot(505, newToken), store.snapshot())
        assertTrue(store.clearIfAccessTokenMatches(newToken))
        assertNull(store.snapshot())
    }

    private fun tokenFor(userId: Int, expiresAt: Long = System.currentTimeMillis() / 1_000 + 3600): String {
        val payload = Base64.encodeToString(
            "{\"userId\":$userId,\"exp\":$expiresAt}".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "header.$payload.signature"
    }
}
