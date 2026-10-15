package com.receiptclaims.android.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.receiptclaims.android.data.repository.ReceiptRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineMigrationAndClaimTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: AppDatabase? = null

    @After fun closeDb() { database?.close(); database = null }

    @Test fun migrationKeepsCompositeOwnersLegacyClaimAndImagesAndAddsOnlyKnownSgd() {
        val name = "migration-v3-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).absolutePath
        val old = SQLiteDatabase.openOrCreateDatabase(path, null)
        old.execSQL("CREATE TABLE receipts (ownerUserId INTEGER NOT NULL, clientReceiptId TEXT NOT NULL, serverId TEXT, amount TEXT NOT NULL, currency TEXT NOT NULL, receiptDate TEXT NOT NULL, categoryId INTEGER NOT NULL, description TEXT, note TEXT, imagePath TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(ownerUserId, clientReceiptId))")
        old.execSQL("CREATE TABLE categories (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
        old.execSQL("INSERT INTO categories VALUES (1, 'Admin')")
        old.execSQL("INSERT INTO receipts VALUES (10, 'same-id', '501', '12.50', 'SGD', '2026-10-01', 1, 'first', NULL, '/private/one.jpg', 'PENDING_UPLOAD', 'offline', 1)")
        old.execSQL("INSERT INTO receipts VALUES (20, 'same-id', '502', '4.20', 'USD', '2026-10-02', 1, 'second', NULL, '/private/two.jpg', 'CLAIMED', NULL, 2)")
        old.version = 3
        old.close()

        database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4, DatabaseModule.MIGRATION_4_5)
            .build()
        val rows = runBlocking { database!!.receiptDao().allReceipts() }
        assertEquals(2, rows.size)
        assertEquals(setOf(10, 20), rows.map { it.ownerUserId }.toSet())
        val saved = rows.single { it.ownerUserId == 10 }
        assertEquals("SAVED", saved.status)
        assertEquals("/private/one.jpg", saved.imagePath)
        assertNull(saved.name)
        assertEquals("1", saved.exchangeRate)
        assertEquals("12.50", saved.sgdEquivalent)
        val legacyClaimed = rows.single { it.ownerUserId == 20 }
        assertEquals("CLAIMED", legacyClaimed.status)
        assertEquals("/private/two.jpg", legacyClaimed.imagePath)
        assertNull(legacyClaimed.exchangeRate)
        assertNull(legacyClaimed.sgdEquivalent)
    }

    @Test fun aClaimCanContainSameClientIdFromTwoOwnersButEachReceiptCanOnlyBeClaimedOnce() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val db = database!!
        val dao = db.receiptDao()
        val one = receipt(owner = 10, amount = "8")
        val two = receipt(owner = 20, amount = "9")
        dao.upsert(one); dao.upsert(two)
        val repo = ReceiptRepository(dao, db)

        val claimId = repo.createClaim("Travel", null, null, listOf(one, two), "A. Person")
        val links = db.claimDao().linksForClaim(claimId)
        assertEquals(setOf(10, 20), links.map { it.receiptOwnerUserId }.toSet())
        assertEquals(listOf("same-id", "same-id"), links.map { it.receiptClientReceiptId })
        assertEquals(setOf(10, 20), repo.receiptsForClaim(claimId).map { it.ownerUserId }.toSet())

        val secondAttempt = runCatching { repo.createClaim("Second", null, null, listOf(one), "A. Person") }
        assertTrue("receipt may not be included in a second claim", secondAttempt.isFailure)
        assertEquals(1, db.claimDao().all().size)
        assertEquals(2, db.claimDao().allLinks().size)
    }

    private fun receipt(owner: Int, amount: String): ReceiptEntity {
        val image = java.io.File(context.filesDir, "claim-test-$owner.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ReceiptEntity(
        clientReceiptId = "same-id", ownerUserId = owner, amount = amount, currency = "SGD",
        exchangeRate = "1", sgdEquivalent = amount, receiptDate = "2026-10-01", categoryId = 1,
        imagePath = image.absolutePath, status = ReceiptEntity.STATUS_SAVED
        )
    }
}
