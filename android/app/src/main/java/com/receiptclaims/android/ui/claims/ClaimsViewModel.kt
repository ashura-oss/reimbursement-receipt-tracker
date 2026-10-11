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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.ResponseBody
import java.io.File
import javax.inject.Inject

data class ClaimsUiState(
    val claims: List<ClaimDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val reports: Map<Int, ReportDto> = emptyMap(),
    val generatingClaimId: Int? = null,
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

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                api.claims().data.orEmpty().map { listed ->
                    runCatching { api.claim(listed.id).data ?: listed }.getOrDefault(listed)
                }
            }
                .onSuccess { _state.value = ClaimsUiState(claims = it) }
                .onFailure { _state.value = ClaimsUiState(error = it.message ?: "Unable to load claims.") }
        }
    }

    fun createClaim(title: String, receipts: List<ReceiptEntity>, onCreated: () -> Unit) {
        val ids = receipts.mapNotNull { it.serverId?.toIntOrNull() }
        if (title.isBlank() || ids.isEmpty()) {
            _state.value = _state.value.copy(error = "Upload at least one receipt before creating a claim.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { api.createClaim(CreateClaimRequest(title.trim(), receiptIds = ids)) }
                .onSuccess {
                    receiptRepository.markClaimed(ids.map(Int::toString))
                    _state.value = _state.value.copy(loading = false)
                    refresh()
                    onCreated()
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Unable to create claim.") }
        }
    }

    fun generateReport(claimId: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(generatingClaimId = claimId, error = null, downloadMessage = null)
            runCatching { requireNotNull(api.generateReport(claimId).data) { "The report response was empty." } }
                .onSuccess { report ->
                    val reportKey = report.report?.claimId?.takeIf { it > 0 } ?: claimId
                    _state.value = _state.value.copy(
                        reports = _state.value.reports + (reportKey to report),
                        generatingClaimId = null
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        generatingClaimId = null,
                        error = error.message ?: "Unable to generate the report."
                    )
                }
        }
    }

    /** Streams the protected response into app-private cache. No public URL or blob is exposed. */
    fun downloadFile(context: Context, file: ReportFileDto) {
        viewModelScope.launch {
            _state.value = _state.value.copy(downloadingFileId = file.id, error = null, downloadMessage = null)
            runCatching {
                requireNotNull(sessionStore.accessToken()) { "Sign in again before downloading reports." }
                val response = api.downloadReportFile(file.id)
                require(response.isSuccessful) { "Download failed (${response.code()})." }
                val body = requireNotNull(response.body()) { "The downloaded file was empty." }
                val targetDir = File(context.cacheDir, "report-downloads").apply { mkdirs() }
                val target = File(targetDir, "${file.id}-${file.fileName}")
                writeBody(body, target)
                require(target.length() > 0) { "The downloaded file was empty." }
                CachedReportFile(file.id, file.fileName, file.mimeType, target.absolutePath)
            }.onSuccess {
                _state.value = _state.value.copy(
                    downloadingFileId = null,
                    cachedFiles = _state.value.cachedFiles + (file.id to it),
                    downloadMessage = "${file.fileName} is ready. Open, share, or save it."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
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
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "Unable to save the file.") }
        }
    }
}
