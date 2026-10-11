package com.receiptclaims.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptDaoIsolationTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ReceiptDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.receiptDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun observingAndClaimingReceiptsIsScopedToOwner() = runBlocking {
        dao.upsert(receipt("owner-one", ownerUserId = 1, serverId = "41"))
        dao.upsert(receipt("owner-two", ownerUserId = 2, serverId = "42"))

        assertEquals(listOf("owner-one"), dao.observeForUser(1).first().map { it.clientReceiptId })
        dao.markClaimed(1, listOf("42", "41"))

        val ownerOneReceipt = dao.findForUser(1, "owner-one")
        val ownerTwoReceipt = dao.findForUser(2, "owner-two")
        assertEquals(ReceiptEntity.STATUS_CLAIMED, ownerOneReceipt?.status)
        assertEquals(ReceiptEntity.STATUS_UPLOADED, ownerTwoReceipt?.status)
        assertTrue(dao.observeForUser(2).first().all { it.ownerUserId == 2 })
    }

    @Test
    fun identicalClientIdsAreIsolatedByCompositeOwnerKey() = runBlocking {
        dao.upsert(receipt("same-client-id", ownerUserId = 1, serverId = null).copy(amount = "10.00"))
        dao.upsert(receipt("same-client-id", ownerUserId = 2, serverId = null).copy(amount = "99.00"))

        dao.markUploaded(1, "same-client-id", "501")
        dao.updateStatus(2, "same-client-id", ReceiptEntity.STATUS_FAILED, "network")

        val userOne = dao.findForUser(1, "same-client-id")
        val userTwo = dao.findForUser(2, "same-client-id")
        assertEquals("10.00", userOne?.amount)
        assertEquals("501", userOne?.serverId)
        assertEquals(ReceiptEntity.STATUS_UPLOADED, userOne?.status)
        assertEquals("99.00", userTwo?.amount)
        assertEquals(ReceiptEntity.STATUS_FAILED, userTwo?.status)
        assertEquals("network", userTwo?.errorMessage)
    }

    @Test
    fun reconcilingServerReceiptRemovesSyntheticDuplicateAndKeepsLocalDraftImage() = runBlocking {
        dao.upsert(receipt("client-uuid", ownerUserId = 8, serverId = null).copy(
            imagePath = "/private/local-receipt.jpg",
            status = ReceiptEntity.STATUS_PENDING_UPLOAD
        ))
        dao.upsert(receipt("server-77", ownerUserId = 8, serverId = "77").copy(
            imagePath = "/private/server-cache.jpg"
        ))

        dao.upsert(receipt("client-uuid", ownerUserId = 8, serverId = "77").copy(
            imagePath = "/private/local-receipt.jpg",
            status = ReceiptEntity.STATUS_UPLOADED
        ))
        dao.deleteDuplicateServerRows(8, "77", "client-uuid")

        val receipts = dao.observeForUser(8).first()
        assertEquals(1, receipts.size)
        assertEquals("client-uuid", receipts.single().clientReceiptId)
        assertEquals("/private/local-receipt.jpg", receipts.single().imagePath)
        assertEquals("77", receipts.single().serverId)
    }

    @Test
    fun migrationFromVersionTwoPreservesPendingReceiptAndPrivateImagePath() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "receipt_migration_${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val legacy = SQLiteDatabase.openOrCreateDatabase(path, null)
        legacy.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE receipts (clientReceiptId TEXT NOT NULL PRIMARY KEY, ownerUserId INTEGER NOT NULL, serverId TEXT, amount TEXT NOT NULL, currency TEXT NOT NULL, receiptDate TEXT NOT NULL, categoryId INTEGER NOT NULL, description TEXT, note TEXT, imagePath TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT, createdAt INTEGER NOT NULL)")
        legacy.execSQL("INSERT INTO categories VALUES (1, 'Travel')")
        legacy.execSQL("INSERT INTO receipts VALUES ('legacy-pending', 7, NULL, '15.40', 'SGD', '2026-10-01', 1, 'Taxi', NULL, '/private/receipt-images/legacy.jpg', 'PENDING_UPLOAD', NULL, 123456)")
        legacy.version = 2
        legacy.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(DatabaseModule.MIGRATION_2_3)
            .build()
        try {
            val receipt = migrated.receiptDao().findForUser(7, "legacy-pending")
            assertEquals("15.40", receipt?.amount)
            assertEquals("/private/receipt-images/legacy.jpg", receipt?.imagePath)
            assertEquals(ReceiptEntity.STATUS_PENDING_UPLOAD, receipt?.status)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun installedAppDatabaseUpgradesInPlaceWithoutLosingExistingRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val existingDatabase = context.getDatabasePath("receipt_claims.db")
        val rowsBefore = if (existingDatabase.exists()) {
            SQLiteDatabase.openDatabase(existingDatabase.path, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
                legacy.rawQuery("SELECT COUNT(*) FROM receipts", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            }
        } else 0

        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, "receipt_claims.db")
            .addMigrations(DatabaseModule.MIGRATION_2_3)
            .build()
        try {
            assertEquals(rowsBefore, upgraded.receiptDao().countAll())
            assertEquals(3, upgraded.openHelper.readableDatabase.version)
        } finally {
            upgraded.close()
        }
    }

    private fun receipt(id: String, ownerUserId: Int, serverId: String?) = ReceiptEntity(
        clientReceiptId = id,
        ownerUserId = ownerUserId,
        serverId = serverId,
        amount = "12.34",
        currency = "SGD",
        receiptDate = "2026-10-12",
        categoryId = 1,
        imagePath = "",
        status = ReceiptEntity.STATUS_UPLOADED
    )
}
