package com.receiptclaims.android.storage

import android.content.Context
import android.os.StatFs
import androidx.room.withTransaction
import com.receiptclaims.android.data.local.AppDatabase
import com.receiptclaims.android.data.local.ClaimReceiptCrossRef
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.local.ReportFileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ReceiptKey(val ownerUserId: Int, val clientReceiptId: String)

/** ISO date bounds are inclusive. A null bound leaves that side open. */
data class StorageReceiptFilter(
    val startDate: String? = null,
    val endDate: String? = null,
    val categoryId: Int? = null
) {
    init {
        startDate?.let { require(runCatching { java.time.LocalDate.parse(it) }.isSuccess) { "Start date must use YYYY-MM-DD." } }
        endDate?.let { require(runCatching { java.time.LocalDate.parse(it) }.isSuccess) { "End date must use YYYY-MM-DD." } }
        require(startDate == null || endDate == null || startDate <= endDate) { "Start date must not be after end date." }
    }
}

data class StorageDeletionPreview internal constructor(
    val selectedKeys: Set<ReceiptKey>,
    val receipts: Int,
    val claims: Int,
    val reports: Int,
    val fileCount: Int,
    val receiptNames: List<String>,
    val claimTitles: List<String>,
    internal val claimIds: Set<Long>,
    internal val reportIds: Set<Long>,
    internal val reclaimablePaths: Set<String>
)

data class StorageDeletionResult(
    val receiptsDeleted: Int,
    val claimsDeleted: Int,
    val reportsDeleted: Int,
    val filesDeleted: Int,
    val bytesReclaimed: Long,
    val cleanupFailures: Int,
    val warnings: List<String> = emptyList()
)

data class StorageUsage(
    val databaseBytes: Long,
    val receiptPhotoBytes: Long,
    val reportFileBytes: Long,
    val availableBytes: Long
) {
    val managedBytes: Long get() = databaseBytes + receiptPhotoBytes + reportFileBytes
}

class StoragePreviewChangedException : IllegalStateException("Storage changed after the preview. Review the updated counts before deleting.")

@Singleton
class StorageRepository @Inject constructor(
    private val database: AppDatabase,
    @ApplicationContext private val context: Context
) {
    suspend fun previewDelete(keys: Set<ReceiptKey>): StorageDeletionPreview = withContext(Dispatchers.IO) {
        validateKeys(keys)
        database.withTransaction { computePreview(keys.toSet()) }
    }

    /** Rechecks the complete impact inside the delete transaction and rejects stale previews. */
    suspend fun deleteReceipts(keys: Set<ReceiptKey>, expectedPreview: StorageDeletionPreview): StorageDeletionResult = withContext(Dispatchers.IO) {
        validateKeys(keys)
        require(keys == expectedPreview.selectedKeys) { "The delete selection does not match its preview." }
        val pathsToClean = mutableSetOf<String>()
        val deletedCounts = database.withTransaction {
            val currentPreview = computePreview(keys.toSet())
            if (currentPreview != expectedPreview) throw StoragePreviewChangedException()

            val receiptDao = database.receiptDao()
            val claimDao = database.claimDao()
            val reportDao = database.reportFileDao()
            currentPreview.claimIds.forEach { reportDao.deleteForClaim(it) }
            currentPreview.claimIds.forEach { claimDao.deleteById(it) }
            keys.forEach { receiptDao.deleteByKey(it.ownerUserId, it.clientReceiptId) }

            val survivingPaths = buildSet {
                receiptDao.allReceipts().forEach { it.imagePath.canonicalManagedPath()?.let(::add) }
                reportDao.all().forEach { it.path.canonicalManagedPath()?.let(::add) }
            }
            pathsToClean += currentPreview.reclaimablePaths.filterNot { it in survivingPaths }
            currentPreview
        }

        var deletedFiles = 0
        var bytesReclaimed = 0L
        val failures = mutableListOf<String>()
        pathsToClean.forEach { path ->
            val file = safeManagedFile(path)
            if (file == null || !file.exists()) return@forEach
            val size = file.length()
            if (file.delete()) {
                deletedFiles++
                bytesReclaimed += size
            } else failures += "A private receipt or report file could not be removed; storage use remains higher than the deleted-record count suggests."
        }
        StorageDeletionResult(
            receiptsDeleted = deletedCounts.receipts,
            claimsDeleted = deletedCounts.claims,
            reportsDeleted = deletedCounts.reports,
            filesDeleted = deletedFiles,
            bytesReclaimed = bytesReclaimed,
            cleanupFailures = failures.size,
            warnings = failures.distinct()
        )
    }

    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val (receiptPaths, reportPaths) = database.withTransaction {
            database.receiptDao().allReceipts().mapNotNull { it.imagePath.takeIf(String::isNotBlank) }.toSet() to
                database.reportFileDao().all().mapNotNull { it.path.takeIf(String::isNotBlank) }.toSet()
        }
        val receiptBytes = receiptPaths.mapNotNull { safeManagedFile(it)?.absolutePath }.distinct().sumOf { File(it).takeIf(File::isFile)?.length() ?: 0L }
        val reportBytes = reportPaths.mapNotNull { safeManagedFile(it)?.absolutePath }.distinct().sumOf { File(it).takeIf(File::isFile)?.length() ?: 0L }
        val dbPath = database.openHelper.writableDatabase.path
        val databaseBytes = listOfNotNull(dbPath, dbPath?.let { "$it-wal" }, dbPath?.let { "$it-shm" })
            .distinct().sumOf { File(it).takeIf(File::isFile)?.length() ?: 0L }
        val stat = StatFs(context.filesDir.absolutePath)
        StorageUsage(databaseBytes, receiptBytes, reportBytes, stat.availableBlocksLong * stat.blockSizeLong)
    }

    private suspend fun computePreview(keys: Set<ReceiptKey>): StorageDeletionPreview {
        val receipts = database.receiptDao().allReceipts()
        val links = database.claimDao().allLinks()
        val selectedRows = receipts.filter { ReceiptKey(it.ownerUserId, it.clientReceiptId) in keys }
        val selected = selectedRows.mapTo(mutableSetOf()) { ReceiptKey(it.ownerUserId, it.clientReceiptId) }
        val claimIds = links.asSequence()
            .filter { ReceiptKey(it.receiptOwnerUserId, it.receiptClientReceiptId) in selected }
            .map { it.claimId }
            .toSet()
        val reports = database.reportFileDao().all().filter { it.claimId in claimIds }
        val allClaims = database.claimDao().all().associateBy { it.id }
        val existingClaimIds = claimIds.filterTo(mutableSetOf()) { it in allClaims }
        val requestedPaths = buildSet {
            selectedRows.mapNotNullTo(this) { it.imagePath.canonicalManagedPath() }
            reports.mapNotNullTo(this) { it.path.canonicalManagedPath() }
        }
        val survivingPaths = buildSet {
            receipts.filterNot { ReceiptKey(it.ownerUserId, it.clientReceiptId) in selected }
                .mapNotNullTo(this) { it.imagePath.canonicalManagedPath() }
            database.reportFileDao().all().filterNot { it in reports }
                .mapNotNullTo(this) { it.path.canonicalManagedPath() }
        }
        val reclaimable = requestedPaths.asSequence()
            .filterNot { it in survivingPaths }
            .mapNotNull { safeManagedFile(it)?.takeIf(File::isFile)?.absolutePath }
            .toSet()
        return StorageDeletionPreview(
            selectedKeys = keys.toSet(),
            receipts = selectedRows.size,
            claims = existingClaimIds.size,
            reports = reports.size,
            fileCount = reclaimable.size,
            receiptNames = selectedRows.map { it.name?.takeIf(String::isNotBlank) ?: it.description?.takeIf(String::isNotBlank) ?: "Receipt ${it.receiptDate}" },
            claimTitles = existingClaimIds.sorted().mapNotNull { allClaims[it]?.title },
            claimIds = existingClaimIds,
            reportIds = reports.mapTo(mutableSetOf()) { it.id },
            reclaimablePaths = reclaimable
        )
    }

    private fun validateKeys(keys: Set<ReceiptKey>) {
        require(keys.all { it.ownerUserId >= 0 && it.clientReceiptId.isNotBlank() }) { "A selected receipt key is invalid." }
    }

    /** Return only paths inside the two app-private directories this feature owns. */
    private fun safeManagedFile(path: String): File? = runCatching {
        if (path.isBlank()) return null
        val candidate = File(path).canonicalFile
        val roots = listOf(File(context.filesDir, "receipt-images"), File(context.filesDir, "reports")).map { it.canonicalFile }
        roots.firstOrNull { candidate.parentFile?.toPath()?.startsWith(it.toPath()) == true }?.let { candidate }
    }.getOrNull()

    private fun String.canonicalManagedPath(): String? = safeManagedFile(this)?.absolutePath
}

/** Pure inclusive date/category filtering; suitable for computing the current “select all filtered” set. */
fun filterReceipts(receipts: List<ReceiptEntity>, filter: StorageReceiptFilter): List<ReceiptEntity> = receipts.filter { receipt ->
    (filter.categoryId == null || receipt.categoryId == filter.categoryId) &&
        (filter.startDate == null || receipt.receiptDate >= filter.startDate) &&
        (filter.endDate == null || receipt.receiptDate <= filter.endDate)
}

fun filteredReceiptKeys(receipts: List<ReceiptEntity>, filter: StorageReceiptFilter): Set<ReceiptKey> =
    filterReceipts(receipts, filter).mapTo(mutableSetOf()) { ReceiptKey(it.ownerUserId, it.clientReceiptId) }
