package com.receiptclaims.android.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.receiptclaims.android.backup.restoreStagingBudgetBytes
import com.receiptclaims.android.data.local.AppDatabase
import com.receiptclaims.android.data.local.CategoryEntity
import com.receiptclaims.android.data.local.ClaimEntity
import com.receiptclaims.android.data.local.ClaimReceiptCrossRef
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.local.ReportFileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var repository: StorageRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = StorageRepository(database, context)
    }

    @After fun tearDown() { database.close() }

    @Test fun unclaimedDeleteRemovesOnlyUnsharedPhoto() = runBlocking {
        val photo = privateFile("receipt-images", "shared.jpg", "photo bytes")
        database.categoryDao().upsertAll(listOf(CategoryEntity(1, "Meals")))
        database.receiptDao().upsertAll(listOf(
            receipt("one", imagePath = photo.absolutePath),
            receipt("two", imagePath = photo.absolutePath)
        ))

        val firstKeys = setOf(ReceiptKey(0, "one"))
        val firstPreview = repository.previewDelete(firstKeys)
        assertEquals(1, firstPreview.receipts)
        assertEquals(0, firstPreview.claims)
        assertEquals(0, firstPreview.fileCount)
        val firstResult = repository.deleteReceipts(firstKeys, firstPreview)
        assertEquals(1, firstResult.receiptsDeleted)
        assertEquals(0, firstResult.filesDeleted)
        assertTrue(photo.exists())
        assertEquals(listOf("two"), database.receiptDao().allReceipts().map { it.clientReceiptId })

        val secondKeys = setOf(ReceiptKey(0, "two"))
        val secondPreview = repository.previewDelete(secondKeys)
        assertEquals(1, secondPreview.fileCount)
        val secondResult = repository.deleteReceipts(secondKeys, secondPreview)
        assertEquals(1, secondResult.filesDeleted)
        assertFalse(photo.exists())
    }

    @Test fun deletingClaimMemberRemovesClaimAndReportsButKeepsOtherReceiptsReusable() = runBlocking {
        database.categoryDao().upsertAll(listOf(CategoryEntity(1, "Travel")))
        val selectedPhoto = privateFile("receipt-images", "selected.jpg", "selected image")
        val remainingPhoto = privateFile("receipt-images", "remaining.jpg", "remaining image")
        val report = privateFile("reports", "claim.pdf", "report bytes")
        database.receiptDao().upsertAll(listOf(
            receipt("selected", name = "Taxi", imagePath = selectedPhoto.absolutePath),
            receipt("remaining", name = "Hotel", imagePath = remainingPhoto.absolutePath)
        ))
        val claimId = database.claimDao().insert(ClaimEntity(title = "Trip claim", createdAt = 10))
        database.claimDao().insertLinks(listOf(ClaimReceiptCrossRef(claimId, 0, "selected"), ClaimReceiptCrossRef(claimId, 0, "remaining")))
        database.reportFileDao().upsert(ReportFileEntity(claimId = claimId, type = "PDF", name = "claim.pdf", mimeType = "application/pdf", path = report.absolutePath))

        val keys = setOf(ReceiptKey(0, "selected"))
        val preview = repository.previewDelete(keys)
        assertEquals(1, preview.receipts)
        assertEquals(1, preview.claims)
        assertEquals(1, preview.reports)
        assertEquals(listOf("Taxi"), preview.receiptNames)
        assertEquals(listOf("Trip claim"), preview.claimTitles)

        val result = repository.deleteReceipts(keys, preview)
        assertEquals(1, result.receiptsDeleted)
        assertEquals(1, result.claimsDeleted)
        assertEquals(1, result.reportsDeleted)
        assertFalse(selectedPhoto.exists())
        assertFalse(report.exists())
        assertTrue(remainingPhoto.exists())
        assertEquals(listOf("remaining"), database.receiptDao().allReceipts().map { it.clientReceiptId })
        assertTrue(database.claimDao().all().isEmpty())
        assertTrue(database.claimDao().allLinks().isEmpty())
        assertTrue(database.reportFileDao().all().isEmpty())
    }

    @Test fun stalePreviewRejectsDeletionAndFilteredSelectAllUsesDateAndCategory() = runBlocking {
        database.categoryDao().upsertAll(listOf(CategoryEntity(1, "Meals"), CategoryEntity(2, "Travel")))
        database.receiptDao().upsertAll(listOf(
            receipt("a", date = "2026-10-01", category = 1),
            receipt("b", date = "2026-10-15", category = 1),
            receipt("c", date = "2026-10-15", category = 2),
            receipt("d", date = "2026-11-01", category = 1)
        ))
        val rows = database.receiptDao().allReceipts()
        val filter = StorageReceiptFilter(startDate = "2026-10-10", endDate = "2026-10-31", categoryId = 1)
        assertEquals(setOf(ReceiptKey(0, "b")), filteredReceiptKeys(rows, filter))
        assertEquals(listOf("b"), filterReceipts(rows, filter).map { it.clientReceiptId })

        val keys = setOf(ReceiptKey(0, "b"))
        val stale = repository.previewDelete(keys)
        val claimId = database.claimDao().insert(ClaimEntity(title = "Changed after preview", createdAt = 20))
        database.claimDao().insertLinks(listOf(ClaimReceiptCrossRef(claimId, 0, "b")))
        val failure = runCatching { repository.deleteReceipts(keys, stale) }.exceptionOrNull()
        assertTrue(failure is StoragePreviewChangedException)
        assertEquals(4, database.receiptDao().allReceipts().size)
        assertEquals(1, database.claimDao().all().size)
    }

    @Test fun restoreCapacityLeavesHeadroomForBothStagingAndFinalFiles() {
        val available = 100L * 1024 * 1024
        assertEquals(45L * 1024 * 1024, restoreStagingBudgetBytes(available))
        assertEquals(0L, restoreStagingBudgetBytes(0))
        assertTrue(restoreStagingBudgetBytes(available) * 2 < available)
    }

    private fun receipt(id: String, date: String = "2026-10-15", category: Int = 1, name: String? = null, imagePath: String = "") =
        ReceiptEntity(id, 0, amount = "12.00", receiptDate = date, categoryId = category, name = name, imagePath = imagePath)

    private fun privateFile(directory: String, name: String, contents: String): File =
        File(File(context.filesDir, directory).apply { mkdirs() }, "${UUID.randomUUID()}-$name").apply { writeText(contents) }
}
