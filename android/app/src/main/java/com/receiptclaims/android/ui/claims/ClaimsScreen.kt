package com.receiptclaims.android.ui.claims

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.receiptclaims.android.ui.receipts.ReceiptViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaimsScreen(
    onBack: () -> Unit,
    viewModel: ClaimsViewModel = hiltViewModel(),
    receiptViewModel: ReceiptViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val receipts by receiptViewModel.receipts.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf("") }
    var pendingSaveFileId by remember { mutableStateOf<Int?>(null) }
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
    val uploadedReceipts = receipts.filter {
        it.serverId != null &&
            it.status == com.receiptclaims.android.data.local.ReceiptEntity.STATUS_UPLOADED &&
            it.serverId !in claimedReceiptIds
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Claims and reports") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Claim title") }, modifier = Modifier.fillMaxWidth())
            Text("Uploaded receipts available: ${uploadedReceipts.size}")
            Button(onClick = { viewModel.createClaim(title, uploadedReceipts, {}) }, modifier = Modifier.fillMaxWidth()) { Text("Create claim from uploaded receipts") }
            state.error?.let { Text(it) }
            state.downloadMessage?.let { Text(it) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.claims, key = { it.id }) { claim ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Claim #${claim.id}")
                            Text("Status: ${claim.status}")
                            Button(
                                onClick = { viewModel.generateReport(claim.id) },
                                enabled = state.generatingClaimId == null
                            ) {
                                Text(if (state.generatingClaimId == claim.id) "Generating…" else "Generate report")
                            }
                            state.reports[claim.id]?.let { report ->
                                Text("Report ${report.report?.status ?: "available"}")
                                report.files.forEach { file ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("${file.fileType}: ${file.fileName}")
                                        Button(
                                            onClick = { viewModel.downloadFile(context, file) },
                                            enabled = state.downloadingFileId == null
                                        ) {
                                            Text(if (state.downloadingFileId == file.id) "Downloading…" else "Download")
                                        }
                                        if (state.cachedFiles[file.id] != null) {
                                            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
}
