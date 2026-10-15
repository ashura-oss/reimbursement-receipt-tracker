package com.receiptclaims.android.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.room.withTransaction
import com.receiptclaims.android.data.local.AppDatabase
import com.receiptclaims.android.data.local.CategoryEntity
import com.receiptclaims.android.data.local.ClaimEntity
import com.receiptclaims.android.data.local.ClaimReceiptCrossRef
import com.receiptclaims.android.data.local.LocalSettingEntity
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.local.ReportFileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BackupResult(val message: String, val warnings: List<String> = emptyList())

/** Portable, logical backup of user data. Credentials and backend/session state are deliberately excluded. */
@Singleton
class BackupService @Inject constructor(
    private val database: AppDatabase,
    @ApplicationContext private val context: Context
) {
    suspend fun exportTo(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val snapshot = database.withTransaction {
            Snapshot(
                receipts = database.receiptDao().allReceipts(),
                categories = database.categoryDao().all(),
                claims = database.claimDao().all(),
                links = database.claimDao().allLinks(),
                reports = database.reportFileDao().all(),
                settings = database.localSettingDao().all().filterNot { isSensitiveKey(it.key) || isImportMarker(it.key) }
            )
        }
        require(snapshot.receipts.size <= MAX_RECORDS && snapshot.categories.size <= MAX_RECORDS && snapshot.claims.size <= MAX_RECORDS && snapshot.links.size <= MAX_RECORDS && snapshot.reports.size <= MAX_RECORDS && snapshot.settings.size <= MAX_RECORDS) {
            "Backup contains too many records."
        }
        var missing = 0
        val receiptFiles = mutableMapOf<String, File>()
        val reportFiles = mutableMapOf<String, File>()
        val receiptJson = JSONArray()
        snapshot.receipts.forEachIndexed { index, row ->
            val archivePath = "photos/receipt-$index.bin"
            val file = safePrivateFile(row.imagePath)
            val available = file?.takeIf(File::isFile)
            require(available == null || available.length() <= MAX_IMAGE_BYTES) { "A receipt photo exceeds the 10 MiB per-file backup limit." }
            if (available == null) missing++ else {
                receiptFiles[archivePath] = available
            }
            receiptJson.put(row.toJson(archivePath.takeIf { available != null }, available == null))
        }
        val reportJson = JSONArray()
        snapshot.reports.forEachIndexed { index, row ->
            val archivePath = "reports/report-$index.bin"
            val file = safePrivateFile(row.path)
            val available = file?.takeIf(File::isFile)
            require(available == null || available.length() <= MAX_REPORT_BYTES) { "A report file exceeds the 50 MiB per-file backup limit." }
            if (available == null) missing++ else {
                reportFiles[archivePath] = available
            }
            reportJson.put(row.toJson(archivePath.takeIf { available != null }, available == null))
        }
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("backupId", UUID.randomUUID().toString())
            put("receipts", receiptJson)
            put("categories", JSONArray().also { array -> snapshot.categories.forEach { array.put(it.toJson()) } })
            put("claims", JSONArray().also { array -> snapshot.claims.forEach { array.put(it.toJson()) } })
            put("claimReceipts", JSONArray().also { array -> snapshot.links.forEach { array.put(it.toJson()) } })
            put("reports", reportJson)
            put("settings", JSONArray().also { array -> snapshot.settings.forEach { array.put(it.toJson()) } })
        }
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Backup manifest exceeds the 8 MiB limit." }
        require(receiptFiles.size + reportFiles.size + 1 <= MAX_ENTRIES) { "Backup contains too many files." }
        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Could not open the selected backup destination." }
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            putEntry(zip, MANIFEST, manifestBytes)
            (receiptFiles + reportFiles).forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                FileInputStream(file).use { it.copyTo(zip, BUFFER_SIZE) }
                zip.closeEntry()
            }
        }
        BackupResult("Backup saved with ${snapshot.receipts.size} receipts, ${snapshot.claims.size} claims, and ${receiptFiles.size + reportFiles.size} files.", if (missing > 0) listOf("$missing receipt or report file(s) were missing or exceeded the file size limit; their records were preserved.") else emptyList())
    }

    suspend fun importFrom(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val stage = File(context.cacheDir, "backup-stage/${UUID.randomUUID()}").apply { check(mkdirs()) { "Could not create a temporary restore folder." } }
        val createdFiles = mutableListOf<File>()
        try {
            val entries = unpackAndValidate(uri, stage)
            val manifest = JSONObject(File(stage, MANIFEST).readText(Charsets.UTF_8))
            val incoming = parseManifest(manifest, entries)
            val targetPaths = mutableMapOf<String, String>()
            val referencedFiles = incoming.receipts.mapNotNull { it.archivePath }.plus(incoming.reports.mapNotNull { it.archivePath }).distinct()
            referencedFiles.forEach { archivePath ->
                val staged = File(stage, archivePath)
                check(staged.isFile) { "A referenced backup file is missing." }
                val targetDir = if (archivePath.startsWith("photos/")) File(context.filesDir, "receipt-images") else File(context.filesDir, "reports")
                check(targetDir.mkdirs() || targetDir.isDirectory) { "Could not prepare private restore storage." }
                val target = File(targetDir, "${UUID.randomUUID()}.bin")
                createdFiles += target
                staged.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                targetPaths[archivePath] = target.absolutePath
            }

            var addedReceipts = 0
            var addedClaims = 0
            val persistedPaths = mutableSetOf<String>()
            var warnings = incoming.warnings.toMutableList()
            database.withTransaction {
                val receiptDao = database.receiptDao()
                val categoryDao = database.categoryDao()
                val claimDao = database.claimDao()
                val reportDao = database.reportFileDao()
                val settingDao = database.localSettingDao()
                val currentCategories = categoryDao.all().associateBy { it.id }.toMutableMap()
                val categoryMap = mutableMapOf<Int, Int>()
                incoming.categories.forEach { imported ->
                    val current = currentCategories[imported.id]
                    if (current == null) {
                        categoryMap[imported.id] = imported.id
                        categoryDao.upsertAll(listOf(imported))
                        currentCategories[imported.id] = imported
                    } else if (current == imported) categoryMap[imported.id] = imported.id
                    else {
                        val equivalent = currentCategories.values.firstOrNull { it.name == imported.name && it.accountCode == imported.accountCode }
                        if (equivalent != null) {
                            categoryMap[imported.id] = equivalent.id
                            return@forEach
                        }
                        require(currentCategories.keys.maxOrNull() != Int.MAX_VALUE) { "There is no free category ID for this backup." }
                        val newId = (currentCategories.keys.maxOrNull() ?: 0) + 1
                        val remapped = imported.copy(id = newId)
                        categoryDao.upsertAll(listOf(remapped))
                        currentCategories[newId] = remapped
                        categoryMap[imported.id] = newId
                    }
                }

                val receiptIdMap = mutableMapOf<Pair<Int, String>, String>()
                incoming.receipts.forEach { imported ->
                    val key = imported.entity.ownerUserId to imported.entity.clientReceiptId
                    val targetImage = imported.archivePath?.let { targetPaths[it] } ?: ""
                    val candidate = imported.entity.copy(categoryId = categoryMap.getValue(imported.entity.categoryId), imagePath = targetImage)
                    val current = receiptDao.findByKey(imported.entity.ownerUserId, imported.entity.clientReceiptId)
                    if (current == null) {
                        receiptDao.upsert(candidate)
                        targetImage.takeIf(String::isNotBlank)?.let(persistedPaths::add)
                        receiptIdMap[key] = candidate.clientReceiptId
                        addedReceipts++
                    } else if (sameReceipt(current, candidate, imported.archivePath?.let { targetPaths[it] })) {
                        receiptIdMap[key] = current.clientReceiptId
                    } else {
                        val signature = receiptSignature(candidate, imported.archivePath?.let { targetPaths[it] })
                        val remappedId = "${imported.entity.clientReceiptId}-restore-${signature.take(24)}"
                        val remapped = candidate.copy(clientReceiptId = remappedId)
                        val previousImport = receiptDao.findByKey(imported.entity.ownerUserId, remappedId)
                        if (previousImport == null) {
                            receiptDao.upsert(remapped)
                            targetImage.takeIf(String::isNotBlank)?.let(persistedPaths::add)
                            addedReceipts++
                        } else require(sameReceipt(previousImport, remapped, imported.archivePath?.let { targetPaths[it] })) {
                            "A restored receipt identity conflicts with different saved data. No records were changed."
                        }
                        receiptIdMap[key] = remappedId
                    }
                }

                val importedMembership = incoming.links.groupBy { it.claimId }.mapValues { (_, links) ->
                    links.map { it.receiptOwnerUserId to receiptIdMap.getValue(it.receiptOwnerUserId to it.receiptClientReceiptId) }.toSet()
                }
                val claimIdMap = mutableMapOf<Long, Long>()
                incoming.claims.forEach { imported ->
                    val current = claimDao.find(imported.id)
                    val membership = importedMembership[imported.id].orEmpty()
                    val currentMembership = current?.let { claimDao.linksForClaim(it.id).map { link -> link.receiptOwnerUserId to link.receiptClientReceiptId }.toSet() }
                    if (current == null) {
                        claimDao.upsert(imported)
                        claimIdMap[imported.id] = imported.id
                        addedClaims++
                    } else if (current == imported && currentMembership == membership) claimIdMap[imported.id] = current.id
                    else {
                        val signature = claimSignature(imported, membership)
                        val newId = stableLong("${imported.id}:$signature")
                        val existing = claimDao.find(newId)
                        if (existing == null) {
                            claimDao.upsert(imported.copy(id = newId))
                            claimIdMap[imported.id] = newId
                            addedClaims++
                        } else {
                            val existingMembership = claimDao.linksForClaim(existing.id).map { it.receiptOwnerUserId to it.receiptClientReceiptId }.toSet()
                            require(existing == imported.copy(id = newId) && existingMembership == membership) {
                                "A restored claim identity conflicts with different saved data. No records were changed."
                            }
                            claimIdMap[imported.id] = newId
                        }
                    }
                }
                incoming.links.forEach { link ->
                    val remappedReceiptId = receiptIdMap.getValue(link.receiptOwnerUserId to link.receiptClientReceiptId)
                    val remappedClaimId = claimIdMap.getValue(link.claimId)
                    val existingLink = claimDao.allLinks().firstOrNull { it.receiptOwnerUserId == link.receiptOwnerUserId && it.receiptClientReceiptId == remappedReceiptId }
                    if (existingLink == null) claimDao.insertLinks(listOf(link.copy(claimId = remappedClaimId, receiptClientReceiptId = remappedReceiptId)))
                    else require(existingLink.claimId == remappedClaimId) {
                        "A receipt in this backup is already assigned to a different local claim. No records were changed."
                    }
                }

                val existingReports = reportDao.all()
                incoming.reports.forEach { imported ->
                    val remappedClaimId = claimIdMap.getValue(imported.entity.claimId)
                    val restoredPath = imported.archivePath?.let { targetPaths[it] } ?: ""
                    val equivalent = existingReports.any { row ->
                        row.claimId == remappedClaimId && row.type == imported.entity.type && row.name == imported.entity.name && row.mimeType == imported.entity.mimeType &&
                            (restoredPath.isEmpty() || (row.path.isNotEmpty() && safePrivateFile(row.path)?.let(::sha256) == sha256(File(restoredPath))))
                    }
                    if (!equivalent) {
                        reportDao.upsert(imported.entity.copy(id = 0, claimId = remappedClaimId, path = restoredPath))
                        restoredPath.takeIf(String::isNotBlank)?.let(persistedPaths::add)
                    }
                }
                val existingKeys = settingDao.all().map { it.key }.toSet()
                settingDao.upsertAll(incoming.settings.filterNot { it.key in existingKeys })
            }
            createdFiles.filterNot { it.absolutePath in persistedPaths }.forEach { it.delete() }
            BackupResult("Restore merged ${addedReceipts} receipt(s) and ${addedClaims} claim(s).", warnings.distinct())
        } catch (error: Throwable) {
            createdFiles.forEach { it.delete() }
            throw error
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun safePrivateFile(path: String): File? = runCatching {
        if (path.isBlank()) return null
        val source = File(path).canonicalFile
        val roots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
        if (roots.none { source.toPath().startsWith(it.toPath()) }) null else source
    }.getOrNull()

    private fun unpackAndValidate(uri: Uri, stage: File): Set<String> {
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open the selected backup." }
        val found = linkedSetOf<String>()
        var total = 0L
        val stat = StatFs(context.filesDir.absolutePath)
        val freeAtStart = stat.availableBlocksLong * stat.blockSizeLong
        val restoreBudget = restoreStagingBudgetBytes(freeAtStart)
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ENTRIES) { "Backup contains too many files." }
                val name = entry.name
                require(isSafeEntry(name) && !entry.isDirectory) { "Backup contains an unsafe file path." }
                require(found.add(name)) { "Backup contains duplicate file entries." }
                val dest = File(stage, name).canonicalFile
                require(dest.toPath().startsWith(stage.canonicalFile.toPath())) { "Backup contains a path outside its staging folder." }
                dest.parentFile?.mkdirs()
                var size = 0L
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size += read
                        total += read
                        require(total <= restoreBudget) { "Not enough free device storage to stage this restore safely." }
                        if (name == MANIFEST) require(size <= MAX_MANIFEST_BYTES) { "Backup manifest is too large." }
                        if (name.startsWith("photos/")) require(size <= MAX_IMAGE_BYTES) { "A receipt image exceeds the 10 MiB limit." }
                        if (name.startsWith("reports/")) require(size <= MAX_REPORT_BYTES) { "A report file exceeds the 50 MiB limit." }
                        output.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        require(MANIFEST in found) { "This file is not a supported receipt backup." }
        return found
    }

    private fun parseManifest(json: JSONObject, archiveEntries: Set<String>): Incoming {
        require(json.getString("format") == FORMAT && json.getInt("version") == VERSION) { "This backup format is not supported." }
        val backupId = json.getString("backupId")
        require(runCatching { UUID.fromString(backupId) }.isSuccess) { "Backup identifier is invalid." }
        val warnings = mutableListOf<String>()
        fun array(key: String) = json.getJSONArray(key)
        val categories = array("categories").toObjects { CategoryEntity(it.getInt("id"), it.getString("name"), it.optNullableString("accountCode")) }
        require(categories.size <= MAX_RECORDS && categories.map { it.id }.distinct().size == categories.size && categories.all { it.id >= 0 && it.name.isNotBlank() && it.name.length <= 100 && (it.accountCode?.length ?: 0) <= 100 }) { "Backup contains invalid or duplicate categories." }
        val categoryIds = categories.map { it.id }.toSet()
        val receiptArray = array("receipts")
        require(receiptArray.length() <= MAX_RECORDS) { "Backup contains too many receipts." }
        val receipts = receiptArray.toObjects { item ->
            val entity = item.toReceipt()
            require(entity.ownerUserId >= 0 && entity.clientReceiptId.isNotBlank() && entity.clientReceiptId.length <= 200 && entity.currency.matches(Regex("[A-Z]{3}")) && entity.createdAt >= 0 && (entity.name?.length ?: 0) <= 120) { "Backup contains an invalid receipt." }
            val amount = validPositiveMoney(entity.amount, MAX_AMOUNT, 6, "receipt amount")
            val exchangeRate = entity.exchangeRate?.let { validPositiveMoney(it, MAX_RATE, 8, "exchange rate") }
            val sgdEquivalent = entity.sgdEquivalent?.let { validNonNegativeMoney(it, MAX_SGD_EQUIVALENT, 6, "SGD amount") }
            if (exchangeRate != null && sgdEquivalent != null) {
                val expected = amount.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP)
                require(expected.compareTo(sgdEquivalent.setScale(2, RoundingMode.HALF_UP)) == 0) { "Backup contains inconsistent exchange-rate data." }
            }
            require(entity.status == ReceiptEntity.STATUS_SAVED || entity.status == ReceiptEntity.STATUS_CLAIMED) { "Backup contains an unknown receipt status." }
            // Older rows may predate the UI's 2,000-character limit; the manifest cap bounds legacy text.
            require((entity.description?.length ?: 0).toLong() <= MAX_MANIFEST_BYTES && (entity.note?.length ?: 0).toLong() <= MAX_MANIFEST_BYTES && (entity.errorMessage?.length ?: 0).toLong() <= MAX_MANIFEST_BYTES) { "Backup contains oversized receipt text." }
            validateDate(entity.receiptDate)
            require(entity.categoryId in categoryIds) { "A receipt references a missing category." }
            val ref = item.optNullableString("file")
            val missing = item.optBoolean("fileMissing", false)
            if (ref != null) validateReference(ref, "photos/", archiveEntries)
            else if (!missing) require(entity.imagePath.isBlank()) { "Receipt image reference is missing." }
            if (missing) warnings += "Some receipt photos were unavailable in the backup."
            IncomingReceipt(entity, ref, missing)
        }
        val claims = array("claims").toObjects { it.toClaim() }
        require(claims.size <= MAX_RECORDS && claims.map { it.id }.distinct().size == claims.size && claims.all { it.id != 0L && it.title.isNotBlank() && it.title.length <= 120 && it.claimantName.length <= 200 }) { "Backup contains invalid or duplicate claims." }
        claims.forEach {
            validateDate(it.startDate)
            validateDate(it.endDate)
            require(it.createdAt >= 0 && (it.startDate == null || it.endDate == null || !LocalDate.parse(it.endDate).isBefore(LocalDate.parse(it.startDate)))) { "Backup contains invalid claim dates." }
        }
        val claimIds = claims.map { it.id }.toSet()
        val receiptKeys = receipts.map { it.entity.ownerUserId to it.entity.clientReceiptId }
        require(receiptKeys.distinct().size == receiptKeys.size) { "Backup contains duplicate receipts." }
        val links = array("claimReceipts").toObjects { it.toLink() }
        require(links.size <= MAX_RECORDS) { "Backup contains too many claim links." }
        require(links.all { it.claimId in claimIds && (it.receiptOwnerUserId to it.receiptClientReceiptId) in receiptKeys } && links.map { it.receiptOwnerUserId to it.receiptClientReceiptId }.distinct().size == links.size) { "Backup contains a broken claim-to-receipt reference." }
        val reports = array("reports").toObjects { item ->
            val row = item.toReport()
            require(row.id >= 0 && row.createdAt >= 0 && row.claimId in claimIds && row.name.isNotBlank() && row.name.length <= 255 && row.type.isNotBlank() && row.type.length <= 50 && row.mimeType.isNotBlank() && row.mimeType.length <= 120) { "Backup contains an invalid report record." }
            val ref = item.optNullableString("file")
            val missing = item.optBoolean("fileMissing", false)
            if (ref != null) validateReference(ref, "reports/", archiveEntries)
            else if (!missing) require(row.path.isBlank()) { "Report file reference is missing." }
            if (missing) warnings += "Some generated report files were unavailable in the backup."
            IncomingReport(row, ref)
        }
        require(reports.size <= MAX_RECORDS) { "Backup contains too many report records." }
        val settings = array("settings").toObjects { LocalSettingEntity(it.getString("key"), it.getString("value")) }
        require(settings.size <= MAX_RECORDS && settings.all { it.key.length <= 200 && it.value.length <= MAX_SETTING_VALUE }) { "Backup contains oversized settings." }
        require(settings.map { it.key }.distinct().size == settings.size && settings.all { it.key.isNotBlank() && it.key.length <= 200 && it.value.length <= MAX_SETTING_VALUE } && settings.none { isSensitiveKey(it.key) || isImportMarker(it.key) }) { "Backup contains invalid or sensitive settings." }
        val referenced = (receipts.mapNotNull { it.archivePath } + reports.mapNotNull { it.archivePath } + MANIFEST).toSet()
        require(archiveEntries == referenced) { "Backup contains unreferenced or unexpected files." }
        return Incoming(backupId, receipts, categories, claims, links, reports, settings, warnings)
    }

    private fun validateReference(ref: String, prefix: String, entries: Set<String>) {
        require(ref.startsWith(prefix) && isSafeEntry(ref) && ref in entries) { "Backup contains an invalid file reference." }
    }

    private fun validPositiveMoney(value: String, max: BigDecimal, maxScale: Int, label: String): BigDecimal {
        require(value.length <= 64 && value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "Backup contains an invalid $label." }
        val number = value.toBigDecimalOrNull()
        require(number != null && number > BigDecimal.ZERO && number <= max && number.scale().coerceAtLeast(0) <= maxScale) { "Backup contains an invalid $label." }
        return number
    }

    private fun validNonNegativeMoney(value: String, max: BigDecimal, maxScale: Int, label: String): BigDecimal {
        require(value.length <= 64 && value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "Backup contains an invalid $label." }
        val number = value.toBigDecimalOrNull()
        require(number != null && number >= BigDecimal.ZERO && number <= max && number.scale().coerceAtLeast(0) <= maxScale) { "Backup contains an invalid $label." }
        return number
    }

    private fun isSafeEntry(name: String): Boolean = name.isNotBlank() && !name.startsWith('/') && '\\' !in name && name.split('/').all { it.isNotBlank() && it != "." && it != ".." } && name.none { it == '\u0000' }

    private fun validateDate(value: String?) {
        if (value == null) return
        try { LocalDate.parse(value) } catch (_: DateTimeParseException) { throw IllegalArgumentException("Backup contains an invalid date.") }
    }

    private fun sameReceipt(current: ReceiptEntity, candidate: ReceiptEntity, candidateFilePath: String?): Boolean =
        current.copy(imagePath = "") == candidate.copy(imagePath = "") &&
            ((candidateFilePath.isNullOrBlank() && (current.imagePath.isBlank() || safePrivateFile(current.imagePath)?.isFile == true)) ||
                (candidateFilePath != null && safePrivateFile(current.imagePath)?.let(::sha256) == sha256(File(candidateFilePath))))

    private fun receiptSignature(candidate: ReceiptEntity, imagePath: String?): String = sha256(
        (candidate.copy(clientReceiptId = "", imagePath = "").toString() + ":" + imagePath?.let { sha256(File(it)) }).toByteArray(Charsets.UTF_8)
    )

    private fun claimSignature(claim: ClaimEntity, membership: Set<Pair<Int, String>>): String = sha256(
        (claim.copy(id = 0).toString() + ":" + membership.sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second }).joinToString("|"))
            .toByteArray(Charsets.UTF_8)
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun stableLong(value: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        var number = 0L
        repeat(8) { number = (number shl 8) or (bytes[it].toLong() and 0xff) }
        return -(number and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private fun sha256(file: File): String? = runCatching {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun isSensitiveKey(key: String): Boolean = listOf("token", "password", "secret", "auth", "session", "credential", "backend", "endpoint", "url").any { key.contains(it, true) }
    private fun isImportMarker(key: String): Boolean = key.startsWith("__backup_imported_")

    private data class Snapshot(val receipts: List<ReceiptEntity>, val categories: List<CategoryEntity>, val claims: List<ClaimEntity>, val links: List<ClaimReceiptCrossRef>, val reports: List<ReportFileEntity>, val settings: List<LocalSettingEntity>)
    private data class IncomingReceipt(val entity: ReceiptEntity, val archivePath: String?, val missing: Boolean)
    private data class IncomingReport(val entity: ReportFileEntity, val archivePath: String?)
    private data class Incoming(val backupId: String, val receipts: List<IncomingReceipt>, val categories: List<CategoryEntity>, val claims: List<ClaimEntity>, val links: List<ClaimReceiptCrossRef>, val reports: List<IncomingReport>, val settings: List<LocalSettingEntity>, val warnings: List<String>)

    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).takeUnless { it == "" }
    private inline fun <T> JSONArray.toObjects(parse: (JSONObject) -> T): List<T> = (0 until length()).map { parse(getJSONObject(it)) }
    private fun ReceiptEntity.toJson(file: String?, missing: Boolean) = JSONObject().apply {
        put("clientReceiptId", clientReceiptId); put("ownerUserId", ownerUserId); put("serverId", serverId); put("amount", amount); put("currency", currency)
        put("exchangeRate", exchangeRate); put("sgdEquivalent", sgdEquivalent); put("receiptDate", receiptDate); put("categoryId", categoryId)
        put("name", name); put("description", description); put("note", note); put("imagePath", ""); put("status", status); put("errorMessage", errorMessage); put("createdAt", createdAt)
        put("file", file); put("fileMissing", missing)
    }
    private fun JSONObject.toReceipt() = ReceiptEntity(
        clientReceiptId = getString("clientReceiptId"), ownerUserId = getInt("ownerUserId"), serverId = optNullableString("serverId"), amount = getString("amount"), currency = getString("currency"),
        exchangeRate = optNullableString("exchangeRate"), sgdEquivalent = optNullableString("sgdEquivalent"), receiptDate = getString("receiptDate"), categoryId = getInt("categoryId"),
        name = optNullableString("name"), description = optNullableString("description"), note = optNullableString("note"), imagePath = "", status = optString("status", ReceiptEntity.STATUS_SAVED), errorMessage = optNullableString("errorMessage"), createdAt = getLong("createdAt")
    )
    private fun CategoryEntity.toJson() = JSONObject().put("id", id).put("name", name).put("accountCode", accountCode)
    private fun ClaimEntity.toJson() = JSONObject().put("id", id).put("title", title).put("startDate", startDate).put("endDate", endDate).put("claimantName", claimantName).put("createdAt", createdAt)
    private fun JSONObject.toClaim() = ClaimEntity(id = getLong("id"), title = getString("title"), startDate = optNullableString("startDate"), endDate = optNullableString("endDate"), claimantName = getString("claimantName"), createdAt = getLong("createdAt"))
    private fun ClaimReceiptCrossRef.toJson() = JSONObject().put("claimId", claimId).put("receiptOwnerUserId", receiptOwnerUserId).put("receiptClientReceiptId", receiptClientReceiptId)
    private fun JSONObject.toLink() = ClaimReceiptCrossRef(getLong("claimId"), getInt("receiptOwnerUserId"), getString("receiptClientReceiptId"))
    private fun ReportFileEntity.toJson(file: String?, missing: Boolean) = JSONObject().put("id", id).put("claimId", claimId).put("type", type).put("name", name).put("mimeType", mimeType).put("path", "").put("createdAt", createdAt).put("file", file).put("fileMissing", missing)
    private fun JSONObject.toReport() = ReportFileEntity(id = getLong("id"), claimId = getLong("claimId"), type = getString("type"), name = getString("name"), mimeType = getString("mimeType"), path = "", createdAt = getLong("createdAt"))
    private fun LocalSettingEntity.toJson() = JSONObject().put("key", key).put("value", value)

    private fun putEntry(zip: ZipOutputStream, name: String, contents: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(contents); zip.closeEntry() }

    companion object {
        private const val FORMAT = "receipt-claims-local-backup"
        private const val VERSION = 1
        private const val MANIFEST = "manifest.json"
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_ENTRIES = 10_000
        private const val MAX_RECORDS = 10_000
        private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
        private const val MAX_SETTING_VALUE = 16_384
        private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        private const val MAX_REPORT_BYTES = 50L * 1024 * 1024
        private val MAX_AMOUNT = BigDecimal("1000000000")
        private val MAX_RATE = BigDecimal("1000000")
        private val MAX_SGD_EQUIVALENT = BigDecimal("1000000000000000")
    }
}

/** Leaves headroom for a safe restore while accounting for both staged and final private copies. */
fun restoreStagingBudgetBytes(availableBytes: Long): Long {
    val reserve = minOf(16L * 1024 * 1024, availableBytes / 10)
    return ((availableBytes - reserve).coerceAtLeast(0L)) / 2
}
