package com.receiptclaims.android.ui.claims

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptclaims.android.core.network.ApiService
import com.receiptclaims.android.core.network.ClaimDto
import com.receiptclaims.android.core.network.CreateClaimRequest
import com.receiptclaims.android.core.network.ReportDto
import com.receiptclaims.android.core.network.ReportFileDto
import com.receiptclaims.android.core.security.SessionStore
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.repository.ReceiptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.File
import javax.inject.Inject
import java.time.LocalDate

data class ClaimsUiState(
    val accountUserId: Int? = null,
    val claims: List<ClaimDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val reports: Map<Int, ReportDto> = emptyMap(),
    val generatingClaimId: Int? = null,
    val loadingReportClaimId: Int? = null,
    val downloadingFileId: Int? = null,
    val cachedFiles: Map<Int, CachedReportFile> = emptyMap(),
    val downloadMessage: String? = null
)

data class CachedReportFile(val fileId: Int, val fileName: String, val mimeType: String, val path: String)

@HiltViewModel
class ClaimsViewModel @Inject constructor(
    private val api: ApiService,
    private val receiptRepository: ReceiptRepository,
    private val sessionStore: SessionStore
) : ViewModel() {
    private val _state = MutableStateFlow(ClaimsUiState())
    val state: StateFlow<ClaimsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.snapshotFlow().distinctUntilChanged().collectLatest { snapshot ->
                _state.value = ClaimsUiState(accountUserId = snapshot?.userId)
                if (snapshot != null) load(snapshot)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = sessionStore.snapshot()
            if (snapshot != null) load(snapshot)
        }
    }

    private suspend fun load(snapshot: com.receiptclaims.android.core.security.SessionSnapshot) {
        _state.value = ClaimsUiState(accountUserId = snapshot.userId, loading = true)
        try {
            val authorization = "Bearer ${snapshot.accessToken}"
            val refreshedClaims = api.claims(authorization).data.orEmpty()
            if (sessionStore.snapshot() == snapshot) _state.value = ClaimsUiState(accountUserId = snapshot.userId, claims = refreshedClaims)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (sessionStore.snapshot() == snapshot) _state.value = ClaimsUiState(accountUserId = snapshot.userId, error = error.message ?: "Unable to load claims.")
        }
    }

    fun loadLatestReport(claimId: Int) {
        viewModelScope.launch {
            val snapshot = sessionStore.snapshot() ?: return@launch
            _state.value = _state.value.copy(loadingReportClaimId = claimId, error = null)
            try {
                val report = api.latestReport(claimId, "Bearer ${snapshot.accessToken}").data
                if (sessionStore.snapshot() != snapshot) return@launch
                _state.value = _state.value.copy(loadingReportClaimId = null, reports = if (report == null) _state.value.reports else _state.value.reports + (claimId to report))
                if (report == null) _state.value = _state.value.copy(downloadMessage = "No generated report is available for this claim yet.")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (sessionStore.snapshot() == snapshot) {
                    _state.value = if (error is HttpException && error.code() == 404) {
                        _state.value.copy(loadingReportClaimId = null, downloadMessage = "No generated report is available for this claim yet.")
                    } else {
                        _state.value.copy(loadingReportClaimId = null, error = error.message ?: "Unable to load saved report files.")
                    }
                }
            }
        }
    }

    fun createClaim(title: String, startDate: String, endDate: String, receipts: List<ReceiptEntity>, onCreated: () -> Unit) {
        val ids = receipts.mapNotNull { it.serverId?.toIntOrNull() }
        val start = startDate.trim().takeIf(String::isNotEmpty)
        val end = endDate.trim().takeIf(String::isNotEmpty)
        if (title.isBlank() || ids.isEmpty()) {
            _state.value = _state.value.copy(error = if (title.isBlank()) "Enter a claim title." else "Select at least one uploaded receipt.")
            return
        }
        val validDates = runCatching {
            val startDateValue = start?.let(LocalDate::parse)
            val endDateValue = end?.let(LocalDate::parse)
            startDateValue == null || endDateValue == null || !endDateValue.isBefore(startDateValue)
        }.getOrDefault(false)
        if (!validDates) {
            _state.value = _state.value.copy(error = "Enter valid dates (YYYY-MM-DD); the end date must not precede the start date.")
            return
        }
        viewModelScope.launch {
            val snapshot = sessionStore.snapshot()
            if (snapshot == null || receipts.any { it.ownerUserId != snapshot.userId }) {
                _state.value = _state.value.copy(error = "Your signed-in account changed. Refresh the receipt list and try again.")
                return@launch
            }
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                api.createClaim(CreateClaimRequest(title.trim(), start, end, ids), "Bearer ${snapshot.accessToken}")
                if (sessionStore.snapshot() != snapshot) return@launch
                receiptRepository.markClaimed(snapshot.userId, ids.map(Int::toString))
                _state.value = _state.value.copy(loading = false)
                refresh()
                onCreated()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (sessionStore.snapshot() == snapshot) _state.value = _state.value.copy(loading = false, error = error.message ?: "Unable to create claim.")
            }
        }
    }

    fun generateReport(claimId: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(generatingClaimId = claimId, error = null, downloadMessage = null)
            val snapshot = sessionStore.snapshot() ?: run {
                _state.value = _state.value.copy(generatingClaimId = null, error = "Sign in again before generating reports.")
                return@launch
            }
            try {
                val report = requireNotNull(api.generateReport(claimId, "Bearer ${snapshot.accessToken}").data) { "The report response was empty." }
                if (sessionStore.snapshot() != snapshot) return@launch
                val reportKey = report.report?.claimId?.takeIf { it > 0 } ?: claimId
                _state.value = _state.value.copy(reports = _state.value.reports + (reportKey to report), generatingClaimId = null)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (sessionStore.snapshot() == snapshot) _state.value = _state.value.copy(
                    generatingClaimId = null,
                    error = error.message ?: "Unable to generate the report."
                )
            }
        }
    }

    /** Streams the protected response into app-private cache. No public URL or blob is exposed. */
    fun downloadFile(context: Context, file: ReportFileDto) {
        viewModelScope.launch {
            val snapshot = sessionStore.snapshot()
            if (snapshot == null) {
                _state.value = _state.value.copy(error = "Sign in again before downloading reports.")
                return@launch
            }
            _state.value = _state.value.copy(downloadingFileId = file.id, error = null, downloadMessage = null)
            var target: File? = null
            try {
                val response = api.downloadReportFile(file.id, "Bearer ${snapshot.accessToken}")
                require(response.isSuccessful) { "Download failed (${response.code()})." }
                val body = requireNotNull(response.body()) { "The downloaded file was empty." }
                val targetDir = File(context.cacheDir, "report-downloads").apply { mkdirs() }
                val targetFile = File(targetDir, "${file.id}-${file.fileName}")
                target = targetFile
                writeBody(body, targetFile)
                require(targetFile.length() > 0) { "The downloaded file was empty." }
                if (sessionStore.snapshot() != snapshot) {
                    targetFile.delete()
                    return@launch
                }
                _state.value = _state.value.copy(
                    downloadingFileId = null,
                    cachedFiles = _state.value.cachedFiles + (file.id to CachedReportFile(file.id, file.fileName, file.mimeType, targetFile.absolutePath)),
                    downloadMessage = "${file.fileName} is ready. Open, share, or save it."
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                target?.delete()
                if (sessionStore.snapshot() == snapshot) _state.value = _state.value.copy(
                    downloadingFileId = null,
                    error = error.message ?: "Unable to download ${file.fileName}."
                )
            }
        }
    }

    private suspend fun writeBody(body: ResponseBody, target: File) = withContext(Dispatchers.IO) {
        body.byteStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    fun openFile(context: Context, file: ReportFileDto) = sendFileIntent(context, file, Intent.ACTION_VIEW)

    fun shareFile(context: Context, file: ReportFileDto) = sendFileIntent(context, file, Intent.ACTION_SEND)

    private fun sendFileIntent(context: Context, file: ReportFileDto, action: String) {
        val cached = _state.value.cachedFiles[file.id] ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(cached.path))
        val intent = Intent(action).apply {
            setDataAndType(uri, file.mimeType)
            if (action == Intent.ACTION_SEND) putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, if (action == Intent.ACTION_SEND) "Share report" else "Open report"))
    }

    fun saveToUri(context: Context, fileId: Int, destination: android.net.Uri) {
        val cached = _state.value.cachedFiles[fileId] ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination).use { output ->
                        requireNotNull(output) { "Unable to open destination." }
                        File(cached.path).inputStream().use { input -> input.copyTo(output) }
                    }
                }
            }.onSuccess { _state.value = _state.value.copy(downloadMessage = "${cached.fileName} saved.") }
                .onFailure { if (it is CancellationException) throw it; _state.value = _state.value.copy(error = it.message ?: "Unable to save the file.") }
        }
    }
}
