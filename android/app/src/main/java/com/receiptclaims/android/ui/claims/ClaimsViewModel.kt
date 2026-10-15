package com.receiptclaims.android.ui.claims

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.receiptclaims.android.data.local.AppDatabase
import com.receiptclaims.android.data.local.ClaimEntity
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.local.ReportFileEntity
import com.receiptclaims.android.data.local.LocalSettingEntity
import com.receiptclaims.android.data.repository.ReceiptRepository
import com.receiptclaims.android.reports.LocalReportGenerator
import com.receiptclaims.android.reports.ReportClaim
import com.receiptclaims.android.reports.ReportReceipt
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class LocalClaimUi(val claim: ClaimEntity, val receipts: List<ReceiptEntity>, val files: List<ReportFileEntity>)
data class ClaimsUiState(
    val claims: List<LocalClaimUi> = emptyList(),
    val claimantName: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val generatingClaimId: Long? = null
)

@HiltViewModel
class ClaimsViewModel @Inject constructor(
    private val database: AppDatabase,
    private val receiptRepository: ReceiptRepository,
    private val reportGenerator: LocalReportGenerator,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(ClaimsUiState())
    val state: StateFlow<ClaimsUiState> = _state.asStateFlow()
    private var saveClaimantJob: Job? = null

    init {
        viewModelScope.launch {
            database.localSettingDao().get(CLAIMANT_SETTING)?.let { _state.value = _state.value.copy(claimantName = it) }
            database.claimDao().observeAll().collect { claims ->
                val rows = claims.map { claim ->
                    LocalClaimUi(claim, receiptRepository.receiptsForClaim(claim.id), database.reportFileDao().forClaim(claim.id))
                }
                _state.value = _state.value.copy(claims = rows)
            }
        }
    }

    fun setClaimantName(name: String) {
        _state.value = _state.value.copy(claimantName = name.take(120))
        saveClaimantJob?.cancel()
        saveClaimantJob = viewModelScope.launch {
            delay(300)
            if (_state.value.claimantName == name.take(120)) {
                database.localSettingDao().upsert(LocalSettingEntity(CLAIMANT_SETTING, name.trim().take(120)))
            }
        }
    }

    fun createClaim(title: String, startDate: String, endDate: String, receipts: List<ReceiptEntity>, onCreated: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            database.localSettingDao().upsert(LocalSettingEntity(CLAIMANT_SETTING, _state.value.claimantName.trim()))
            runCatching { receiptRepository.createClaim(title, startDate, endDate, receipts, _state.value.claimantName) }
                .onSuccess { _state.value = _state.value.copy(loading = false, message = "Claim saved on this device."); onCreated() }
                .onFailure { error -> if (error is CancellationException) throw error; _state.value = _state.value.copy(loading = false, error = error.message ?: "Unable to save claim.") }
        }
    }

    fun generateReport(claim: LocalClaimUi) {
        viewModelScope.launch {
            _state.value = _state.value.copy(generatingClaimId = claim.claim.id, error = null, message = null)
            var generatedFiles = emptyList<File>()
            var metadataSaved = false
            try {
                require(claim.receipts.isNotEmpty()) { "This claim has no receipt details on this device." }
                require(claim.receipts.all { it.sgdEquivalent != null && it.exchangeRate != null }) {
                    "A receipt has no saved SGD exchange rate. Add the rate manually before generating this report."
                }
                val categories = database.categoryDao().all().associateBy { it.id }
                val reportClaim = ReportClaim(
                    id = claim.claim.id,
                    title = claim.claim.title,
                    startDate = claim.claim.startDate,
                    endDate = claim.claim.endDate,
                    claimantName = claim.claim.claimantName,
                    receipts = claim.receipts.map { receipt ->
                        val category = categories[receipt.categoryId]
                        ReportReceipt(
                            key = "${receipt.ownerUserId}:${receipt.clientReceiptId}",
                            amount = receipt.amount,
                            currency = receipt.currency,
                            exchangeRate = requireNotNull(receipt.exchangeRate),
                            sgdEquivalent = requireNotNull(receipt.sgdEquivalent),
                            date = receipt.receiptDate,
                            category = category?.name ?: "Category ${receipt.categoryId}",
                            description = receipt.description,
                            note = receipt.note,
                            imagePath = receipt.imagePath,
                            name = receipt.name
                        )
                    }
                )
                val generated = reportGenerator.generate(reportClaim)
                generatedFiles = generated.map { it.file }
                val reportsRoot = File(context.filesDir, "reports").canonicalFile
                val rows = generated.map { output ->
                    val file = output.file.canonicalFile
                    require(file.isFile && file.path.startsWith(reportsRoot.path + File.separator)) { "Generated report is outside private report storage." }
                    ReportFileEntity(claimId = claim.claim.id, type = output.type, name = output.name, mimeType = output.mimeType, path = file.absolutePath)
                }
                database.withTransaction { database.reportFileDao().upsertAll(rows) }
                metadataSaved = true
                val updatedClaims = _state.value.claims.map { existing ->
                    if (existing.claim.id == claim.claim.id) existing.copy(files = database.reportFileDao().forClaim(claim.claim.id)) else existing
                }
                _state.value = _state.value.copy(claims = updatedClaims, generatingClaimId = null, message = "Reports saved on this device.")
            } catch (error: Throwable) {
                if (!metadataSaved && generatedFiles.isNotEmpty()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        generatedFiles.mapNotNull { it.parentFile }.distinct().forEach { directory ->
                            // Each generation has a fresh directory; remove only if no stored row references it.
                            val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: return@forEach
                            val referenced = runCatching {
                                database.reportFileDao().all().any { stored ->
                                    runCatching { File(stored.path).canonicalPath }.getOrNull()?.startsWith(canonical + File.separator) == true
                                }
                            }.getOrDefault(true)
                            if (!referenced) directory.deleteRecursively()
                        }
                    }
                }
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(generatingClaimId = null, error = error.message ?: "Unable to generate report.")
            }
        }
    }

    fun openFile(file: ReportFileEntity) = shareIntent(file, Intent.ACTION_VIEW)
    fun shareFile(file: ReportFileEntity) = shareIntent(file, Intent.ACTION_SEND)
    private fun shareIntent(file: ReportFileEntity, action: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(file.path))
            val intent = Intent(action).apply {
                setDataAndType(uri, file.mimeType)
                if (action == Intent.ACTION_SEND) putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, if (action == Intent.ACTION_SEND) "Share report" else "Open report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error ->
            _state.value = _state.value.copy(error = error.message ?: "Unable to open this report.")
        }
    }

    suspend fun saveToUri(fileId: Long, destination: android.net.Uri) {
        runCatching {
            val file = requireNotNull(database.reportFileDao().find(fileId)) { "This report is no longer available on this device." }
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destination).use { output ->
                    requireNotNull(output) { "Unable to open destination." }
                    File(file.path).inputStream().use { it.copyTo(output) }
                }
            }
            file.name
        }.onSuccess { name -> _state.value = _state.value.copy(message = "$name saved.", error = null) }
            .onFailure { error -> if (error is CancellationException) throw error; _state.value = _state.value.copy(error = error.message ?: "Unable to save this report.") }
    }

    companion object { private const val CLAIMANT_SETTING = "claimantName" }
}
