package com.receiptclaims.android.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.receiptclaims.android.data.local.AppDatabase
import com.receiptclaims.android.data.local.CategoryEntity
import com.receiptclaims.android.data.local.ClaimEntity
import com.receiptclaims.android.data.local.ClaimReceiptCrossRef
import com.receiptclaims.android.data.local.ExpenseAccounts
import com.receiptclaims.android.data.local.LocalSettingEntity
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.local.ReportFileEntity
import com.receiptclaims.android.storage.ReceiptKey
import com.receiptclaims.android.storage.StorageRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var sourceDb: AppDatabase
    private lateinit var targetDb: AppDatabase

    @Before fun setUp() {
        sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        targetDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { sourceDb.close(); targetDb.close() }

    @Test fun exportRestoreAndRepeatedRestorePreserveRecordsAndFiles() = runBlocking<Unit> {
        val photoBytes = "receipt photo bytes".toByteArray()
        val reportBytes = "generated report bytes".toByteArray()
        val photo = File(context.filesDir, "backup-test-${UUID.randomUUID()}.jpg").apply { writeBytes(photoBytes) }
        val report = File(context.filesDir, "backup-test-${UUID.randomUUID()}.pdf").apply { writeBytes(reportBytes) }
        sourceDb.categoryDao().upsertAll(listOf(CategoryEntity(7, "Travel", "TRV")))
        sourceDb.receiptDao().upsert(ReceiptEntity("receipt-a", 0, amount = "42.50", currency = "SGD", receiptDate = "2026-10-12", categoryId = 7, name = "Taxi", expenseAccountId = "meals_allowances", imagePath = photo.absolutePath))
        sourceDb.receiptDao().upsert(ReceiptEntity("receipt-small-fx", 0, amount = "1.00", currency = "USD", exchangeRate = "0.00000001", sgdEquivalent = "0.00", receiptDate = "2026-10-13", categoryId = 7))
        sourceDb.claimDao().upsert(ClaimEntity(id = 12, title = "October trip", startDate = "2026-10-01", endDate = "2026-10-31", claimantName = "Alex", createdAt = 100))
        sourceDb.claimDao().insertLinks(listOf(ClaimReceiptCrossRef(12, 0, "receipt-a")))
        sourceDb.reportFileDao().upsert(ReportFileEntity(claimId = 12, type = "PDF", name = "claim.pdf", mimeType = "application/pdf", path = report.absolutePath))
        sourceDb.localSettingDao().upsertAll(listOf(LocalSettingEntity("currency", "SGD"), LocalSettingEntity("authToken", "never-back-this-up")))

        val archive = File(context.cacheDir, "backup-${UUID.randomUUID()}.zip")
        val secondArchive = File(context.cacheDir, "backup-${UUID.randomUUID()}.zip")
        BackupService(sourceDb, context).exportTo(Uri.fromFile(archive))
        BackupService(sourceDb, context).exportTo(Uri.fromFile(secondArchive))
        assertTrue(archive.isFile)
        val service = BackupService(targetDb, context)
        service.importFrom(Uri.fromFile(archive))
        service.importFrom(Uri.fromFile(archive))
        service.importFrom(Uri.fromFile(secondArchive))

        assertEquals(2, targetDb.receiptDao().allReceipts().size)
        assertEquals(1, targetDb.claimDao().all().size)
        assertEquals(1, targetDb.claimDao().allLinks().size)
        assertEquals(1, targetDb.reportFileDao().all().size)
        assertEquals("SGD", targetDb.localSettingDao().get("currency"))
        assertEquals(null, targetDb.localSettingDao().get("authToken"))
        val restoredReceipts = targetDb.receiptDao().allReceipts()
        assertTrue(File(restoredReceipts.single { it.clientReceiptId == "receipt-a" }.imagePath).readBytes().contentEquals(photoBytes))
        assertEquals("0.00000001", restoredReceipts.single { it.clientReceiptId == "receipt-small-fx" }.exchangeRate)
        assertEquals("0.00", restoredReceipts.single { it.clientReceiptId == "receipt-small-fx" }.sgdEquivalent)
        assertEquals("Taxi", restoredReceipts.single { it.clientReceiptId == "receipt-a" }.name)
        assertEquals("meals_allowances", restoredReceipts.single { it.clientReceiptId == "receipt-a" }.expenseAccountId)
        assertTrue(File(targetDb.reportFileDao().all().single().path).readBytes().contentEquals(reportBytes))
        photo.delete(); report.delete(); archive.delete(); secondArchive.delete()
    }

    @Test fun optionalExpenseAccountRestoresAndUnknownIdsAreRejected() = runBlocking<Unit> {
        assertTrue(ExpenseAccounts.find("meals_allowances") != null)
        sourceDb.categoryDao().upsertAll(listOf(CategoryEntity(2, "Meals")))
        sourceDb.receiptDao().upsert(ReceiptEntity("accounted", 0, amount = "8.00", receiptDate = "2026-10-14", categoryId = 2, expenseAccountId = "meals_allowances"))
        val validArchive = File(context.cacheDir, "account-${UUID.randomUUID()}.zip")
        val legacyArchive = File(context.cacheDir, "account-legacy-${UUID.randomUUID()}.zip")
        val unknownArchive = File(context.cacheDir, "account-unknown-${UUID.randomUUID()}.zip")
        BackupService(sourceDb, context).exportTo(Uri.fromFile(validArchive))
        rewriteReceiptAccountId(validArchive, legacyArchive, null)
        rewriteReceiptAccountId(validArchive, unknownArchive, "not-a-catalog-id")

        val unknownFailure = runCatching { BackupService(targetDb, context).importFrom(Uri.fromFile(unknownArchive)) }.exceptionOrNull()
        assertTrue("Unknown expense account IDs must be rejected", unknownFailure != null)
        assertTrue(targetDb.receiptDao().allReceipts().isEmpty())

        BackupService(targetDb, context).importFrom(Uri.fromFile(legacyArchive))
        assertEquals(null, targetDb.receiptDao().allReceipts().single().expenseAccountId)
        BackupService(targetDb, context).importFrom(Uri.fromFile(validArchive))
        assertEquals(setOf(null, "meals_allowances"), targetDb.receiptDao().allReceipts().map { it.expenseAccountId }.toSet())
        validArchive.delete(); legacyArchive.delete(); unknownArchive.delete()
    }

    @Test fun conflictingReceiptIsRemappedAndUnsafeArchiveDoesNotChangeDatabase() = runBlocking<Unit> {
        sourceDb.categoryDao().upsertAll(listOf(CategoryEntity(1, "Meals")))
        sourceDb.receiptDao().upsert(ReceiptEntity("same-id", 0, amount = "9.00", receiptDate = "2026-10-10", categoryId = 1))
        sourceDb.claimDao().upsert(ClaimEntity(id = 1, title = "Source claim", createdAt = 100))
        sourceDb.claimDao().insertLinks(listOf(ClaimReceiptCrossRef(1, 0, "same-id")))
        targetDb.categoryDao().upsertAll(listOf(CategoryEntity(1, "Meals")))
        targetDb.receiptDao().upsert(ReceiptEntity("same-id", 0, amount = "99.00", receiptDate = "2026-10-10", categoryId = 1))
        targetDb.claimDao().upsert(ClaimEntity(id = 1, title = "Source claim", createdAt = 100))

        val archive = File(context.cacheDir, "collision-${UUID.randomUUID()}.zip")
        val secondArchive = File(context.cacheDir, "collision-${UUID.randomUUID()}.zip")
        BackupService(sourceDb, context).exportTo(Uri.fromFile(archive))
        BackupService(sourceDb, context).exportTo(Uri.fromFile(secondArchive))
        val targetService = BackupService(targetDb, context)
        targetService.importFrom(Uri.fromFile(archive))
        targetService.importFrom(Uri.fromFile(secondArchive))
        val rows = targetDb.receiptDao().allReceipts()
        assertEquals(2, rows.size)
        assertEquals("99.00", rows.single { it.clientReceiptId == "same-id" }.amount)
        val imported = rows.single { it.clientReceiptId != "same-id" }
        assertNotEquals("same-id", imported.clientReceiptId)
        assertEquals(imported.clientReceiptId, targetDb.claimDao().allLinks().single().receiptClientReceiptId)
        assertEquals(2, targetDb.claimDao().all().size)

        val before = targetDb.receiptDao().allReceipts()
        val malicious = File(context.cacheDir, "malicious-${UUID.randomUUID()}.zip")
        ZipOutputStream(malicious.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.txt")); zip.write("bad".toByteArray()); zip.closeEntry()
        }
        val failure = runCatching { BackupService(targetDb, context).importFrom(Uri.fromFile(malicious)) }.exceptionOrNull()
        assertTrue(failure != null)
        assertEquals(before, targetDb.receiptDao().allReceipts())
        assertTrue(!File(context.cacheDir, "escape.txt").exists())
        archive.delete(); secondArchive.delete(); malicious.delete()
    }

    @Test fun receiptAlreadyClaimedElsewhereRejectsWholeRestore() = runBlocking<Unit> {
        sourceDb.categoryDao().upsertAll(listOf(CategoryEntity(4, "Meals")))
        sourceDb.receiptDao().upsert(ReceiptEntity("shared-receipt", 0, amount = "12.00", receiptDate = "2026-10-11", categoryId = 4, createdAt = 1_000))
        sourceDb.claimDao().upsert(ClaimEntity(id = 20, title = "Incoming claim", createdAt = 200))
        sourceDb.claimDao().insertLinks(listOf(ClaimReceiptCrossRef(20, 0, "shared-receipt")))
        sourceDb.reportFileDao().upsert(ReportFileEntity(claimId = 20, type = "PDF", name = "incoming.pdf", mimeType = "application/pdf", path = ""))

        targetDb.categoryDao().upsertAll(listOf(CategoryEntity(4, "Meals")))
        targetDb.receiptDao().upsert(ReceiptEntity("shared-receipt", 0, amount = "12.00", receiptDate = "2026-10-11", categoryId = 4, createdAt = 1_000))
        targetDb.claimDao().upsert(ClaimEntity(id = 10, title = "Existing claim", createdAt = 100))
        targetDb.claimDao().insertLinks(listOf(ClaimReceiptCrossRef(10, 0, "shared-receipt")))

        val archive = File(context.cacheDir, "claim-collision-${UUID.randomUUID()}.zip")
        BackupService(sourceDb, context).exportTo(Uri.fromFile(archive))
        val failure = runCatching { BackupService(targetDb, context).importFrom(Uri.fromFile(archive)) }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(listOf(10L), targetDb.claimDao().all().map { it.id })
        assertEquals(listOf(ClaimReceiptCrossRef(10, 0, "shared-receipt")), targetDb.claimDao().allLinks())
        assertTrue(targetDb.reportFileDao().all().isEmpty())
        assertEquals("12.00", targetDb.receiptDao().allReceipts().single().amount)
        archive.delete()
    }

    @Test fun deletingRestoredDataDoesNotPreventRestoringTheSameArchiveAgain() = runBlocking<Unit> {
        sourceDb.categoryDao().upsertAll(listOf(CategoryEntity(5, "Travel")))
        val sourcePhoto = File(context.filesDir, "restore-after-delete-${UUID.randomUUID()}.jpg").apply { writeText("photo") }
        val sourceReport = File(context.filesDir, "restore-after-delete-${UUID.randomUUID()}.pdf").apply { writeText("report") }
        sourceDb.receiptDao().upsert(ReceiptEntity("recoverable", 0, amount = "15.00", receiptDate = "2026-10-12", categoryId = 5, name = "Taxi", imagePath = sourcePhoto.absolutePath))
        sourceDb.claimDao().upsert(ClaimEntity(id = 50, title = "Recoverable claim", createdAt = 500))
        sourceDb.claimDao().insertLinks(listOf(ClaimReceiptCrossRef(50, 0, "recoverable")))
        sourceDb.reportFileDao().upsert(ReportFileEntity(claimId = 50, type = "PDF", name = "claim.pdf", mimeType = "application/pdf", path = sourceReport.absolutePath))
        val archive = File(context.cacheDir, "recover-${UUID.randomUUID()}.zip")
        BackupService(sourceDb, context).exportTo(Uri.fromFile(archive))

        val backupService = BackupService(targetDb, context)
        backupService.importFrom(Uri.fromFile(archive))
        val importedReceipt = targetDb.receiptDao().allReceipts().single()
        val importedReport = targetDb.reportFileDao().all().single()
        assertTrue(File(importedReceipt.imagePath).exists())
        assertTrue(File(importedReport.path).exists())

        val storage = StorageRepository(targetDb, context)
        val keys = setOf(ReceiptKey(0, "recoverable"))
        storage.deleteReceipts(keys, storage.previewDelete(keys))
        assertTrue(targetDb.receiptDao().allReceipts().isEmpty())
        assertTrue(targetDb.claimDao().all().isEmpty())
        assertTrue(targetDb.reportFileDao().all().isEmpty())
        assertFalse(File(importedReceipt.imagePath).exists())
        assertFalse(File(importedReport.path).exists())

        backupService.importFrom(Uri.fromFile(archive))
        assertEquals(1, targetDb.receiptDao().allReceipts().size)
        assertEquals(1, targetDb.claimDao().all().size)
        assertEquals(1, targetDb.claimDao().allLinks().size)
        assertEquals(1, targetDb.reportFileDao().all().size)
        assertTrue(File(targetDb.receiptDao().allReceipts().single().imagePath).readText() == "photo")
        assertTrue(File(targetDb.reportFileDao().all().single().path).readText() == "report")
        archive.delete(); sourcePhoto.delete(); sourceReport.delete()
    }

    private fun rewriteReceiptAccountId(source: File, destination: File, value: String?) {
        ZipInputStream(source.inputStream()).use { input ->
            ZipOutputStream(destination.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "manifest.json") {
                        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                        val receipt = manifest.getJSONArray("receipts").getJSONObject(0)
                        if (value == null) receipt.remove("expenseAccountId") else receipt.put("expenseAccountId", value)
                        output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    } else output.write(bytes)
                    output.closeEntry()
                    input.closeEntry()
                }
            }
        }
    }
}
