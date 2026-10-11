package com.receiptclaims.android.ui.claims

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.ui.receipts.ReceiptViewModel
import java.io.File

private val ReceiptSelectionSaver = Saver<androidx.compose.runtime.MutableState<Set<String>>, ArrayList<String>>(
    save = { ArrayList(it.value) },
    restore = { androidx.compose.runtime.mutableStateOf(it.toSet()) }
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ClaimsScreen(
    onBack: () -> Unit,
    viewModel: ClaimsViewModel = hiltViewModel(),
    receiptViewModel: ReceiptViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val receipts by receiptViewModel.receipts.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    var startDate by rememberSaveable { mutableStateOf("") }
    var endDate by rememberSaveable { mutableStateOf("") }
    val selectedReceiptIdsState = rememberSaveable(saver = ReceiptSelectionSaver) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedReceiptIds by selectedReceiptIdsState
    var pendingSaveFileId by rememberSaveable { mutableStateOf<Int?>(null) }
    var previousAccountId by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(state.accountUserId) {
        val currentAccountId = state.accountUserId
        if (currentAccountId != null && previousAccountId != null && currentAccountId != previousAccountId) {
            title = ""
            startDate = ""
            endDate = ""
            selectedReceiptIds = emptySet()
        }
        if (currentAccountId != null) previousAccountId = currentAccountId
    }
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val fileId = pendingSaveFileId
        val destination = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && fileId != null && destination != null) {
            viewModel.saveToUri(context, fileId, destination)
        }
        pendingSaveFileId = null
    }
    val claimedReceiptIds = state.claims.flatMap { claim ->
        claim.receipts.orEmpty().map { it.id.toString() } + claim.receiptIds.orEmpty()
    }.toSet()
    val availableReceipts = receipts.filter {
        it.serverId != null &&
            it.status == ReceiptEntity.STATUS_UPLOADED &&
            it.serverId !in claimedReceiptIds
    }
    val selectedReceipts = availableReceipts.filter { it.clientReceiptId in selectedReceiptIds }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Claims and reports") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(title, { title = it }, label = { Text("Claim title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startDate, { startDate = it }, label = { Text("Start date (optional)") }, placeholder = { Text("YYYY-MM-DD") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(endDate, { endDate = it }, label = { Text("End date (optional)") }, placeholder = { Text("YYYY-MM-DD") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Text("Select receipts (${selectedReceipts.size} selected of ${availableReceipts.size} available)")
            availableReceipts.forEach { receipt ->
                val selected = receipt.clientReceiptId in selectedReceiptIds
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(
                            modifier = Modifier.semantics {
                                contentDescription = "Select receipt ${receipt.description?.takeIf(String::isNotBlank) ?: receipt.serverId}, ${receipt.currency} ${receipt.amount}, dated ${receipt.receiptDate}"
                            },
                            checked = selected,
                            onCheckedChange = { checked ->
                                selectedReceiptIds = if (checked) selectedReceiptIds + receipt.clientReceiptId
                                else selectedReceiptIds - receipt.clientReceiptId
                            }
                        )
                        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${receipt.currency} ${receipt.amount} · ${receipt.receiptDate}")
                            Text(receipt.description?.takeIf(String::isNotBlank) ?: "Receipt ${receipt.serverId}")
                        }
                        if (receipt.imagePath.isNotBlank() && File(receipt.imagePath).exists()) {
                            AsyncImage(
                                model = File(receipt.imagePath),
                                contentDescription = "Receipt image",
                                modifier = Modifier.padding(4.dp).size(72.dp)
                            )
                        }
                    }
                }
            }
            if (availableReceipts.isEmpty()) Text("No unclaimed uploaded receipts are available.")
            Button(
                onClick = {
                    viewModel.createClaim(title, startDate, endDate, selectedReceipts) {
                        title = ""
                        startDate = ""
                        endDate = ""
                        selectedReceiptIds = emptySet()
                    }
                },
                enabled = !state.loading && selectedReceipts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.loading) "Creating…" else "Create claim (${selectedReceipts.size} receipts)") }
            state.error?.let { Text(it) }
            state.downloadMessage?.let { Text(it) }
            Text("Your claims")
            TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text(if (state.loading) "Refreshing claims…" else "Refresh claims") }
            if (state.error != null && state.claims.isEmpty()) {
                OutlinedButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("Retry loading claims") }
            }
            state.claims.forEach { claim ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(claim.title?.takeIf(String::isNotBlank) ?: "Claim #${claim.id}")
                        Text("Status: ${claim.status}")
                        Button(
                            onClick = { viewModel.generateReport(claim.id) },
                            enabled = state.generatingClaimId == null && !state.loading
                        ) {
                            Text(if (state.generatingClaimId == claim.id) "Generating…" else "Generate report")
                        }
                        if (state.reports[claim.id] == null) {
                            OutlinedButton(
                                onClick = { viewModel.loadLatestReport(claim.id) },
                                enabled = state.loadingReportClaimId == null && !state.loading
                            ) { Text(if (state.loadingReportClaimId == claim.id) "Loading saved files…" else "Load saved report files") }
                        }
                        state.reports[claim.id]?.let { report ->
                            Text("Report ${report.report?.status ?: "available"}")
                            report.files.forEach { file ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${file.fileType}: ${file.fileName}")
                                    Button(
                                        onClick = { viewModel.downloadFile(context, file) },
                                        enabled = state.downloadingFileId == null
                                    ) { Text(if (state.downloadingFileId == file.id) "Downloading…" else "Download") }
                                    if (state.cachedFiles[file.id] != null) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            OutlinedButton(onClick = { viewModel.openFile(context, file) }) { Text("Open") }
                                            OutlinedButton(onClick = { viewModel.shareFile(context, file) }) { Text("Share") }
                                            OutlinedButton(onClick = {
                                                pendingSaveFileId = file.id
                                                saveFileLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                                    addCategory(Intent.CATEGORY_OPENABLE)
                                                    type = file.mimeType
                                                    putExtra(Intent.EXTRA_TITLE, file.fileName)
                                                })
                                            }) { Text("Save as…") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
