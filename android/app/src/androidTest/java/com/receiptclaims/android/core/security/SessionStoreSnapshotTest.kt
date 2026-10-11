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

    private fun tokenFor(userId: Int): String {
        val payload = Base64.encodeToString(
            "{\"userId\":$userId}".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "header.$payload.signature"
    }
}
