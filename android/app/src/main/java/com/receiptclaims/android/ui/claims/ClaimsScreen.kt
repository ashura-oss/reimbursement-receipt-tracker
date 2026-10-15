package com.receiptclaims.android.ui.claims

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.ui.receipts.ReceiptViewModel
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaimsScreen(
    onBack: () -> Unit,
    viewModel: ClaimsViewModel = hiltViewModel(),
    receiptViewModel: ReceiptViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val receipts by receiptViewModel.receipts.collectAsStateWithLifecycle()
    val categories by receiptViewModel.categories.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    var startDate by rememberSaveable { mutableStateOf("") }
    var endDate by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var pendingFileId by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val targetId = pendingFileId
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && targetId != null && uri != null) {
            scope.launch { viewModel.saveToUri(targetId, uri) }
        }
        pendingFileId = null
    }
    fun identity(receipt: ReceiptEntity) = "${receipt.ownerUserId}:${receipt.clientReceiptId}"
    val alreadyClaimed = state.claims.flatMap { claim -> claim.receipts }.map(::identity).toSet()
    val available = receipts.filter { it.status == ReceiptEntity.STATUS_SAVED && identity(it) !in alreadyClaimed }
    val selectedReceipts = available.filter { identity(it) in selected }
    val selectedRatesReady = selectedReceipts.all { it.exchangeRate != null && it.sgdEquivalent != null }
    val selectedImages = selectedReceipts.map { File(it.imagePath) }
    val selectedImagesReady = selectedImages.all { it.isFile && it.length() in 1..(10L * 1024 * 1024) } && selectedImages.sumOf { if (it.isFile) it.length() else 0L } <= 50L * 1024 * 1024

    Scaffold(topBar = {
        TopAppBar(title = { Text("Claims and reports") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Claims and reports are created and saved on this device.")
            OutlinedTextField(state.claimantName, viewModel::setClaimantName, label = { Text("Claimant name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(title, { title = it }, label = { Text("Claim title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Claim period (optional)")
            OutlinedTextField(startDate, { startDate = it }, label = { Text("Start date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(endDate, { endDate = it }, label = { Text("End date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Choose receipts (${selectedReceipts.size} selected of ${available.size} available)")
            Text("A claim supports up to 500 receipts, 10 MiB per photo, and 50 MiB of photos total.")
            if (!selectedRatesReady && selectedReceipts.isNotEmpty()) Text("Set a manual exchange rate for each selected foreign-currency receipt in My receipts before creating this claim.")
            if (!selectedImagesReady && selectedReceipts.isNotEmpty()) Text("Every selected receipt needs a saved photo within the claim size limits.")
            available.forEach { receipt ->
                val key = identity(receipt)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = key in selected, enabled = key in selected || selected.size < 500, onCheckedChange = { checked ->
                            selected = if (checked) selected + key else selected - key
                        })
                        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${receipt.currency} ${receipt.amount} · ${receipt.receiptDate}")
                            val categoryName = categories.firstOrNull { it.id == receipt.categoryId }?.name ?: "Classification"
                            val displayName = receipt.name?.takeIf(String::isNotBlank) ?: receipt.description?.takeIf(String::isNotBlank)
                            Text(displayName ?: "$categoryName · ${receipt.receiptDate}")
                            if (displayName != null) Text("$categoryName · ${receipt.receiptDate}")
                            receipt.sgdEquivalent?.let { Text("SGD $it") } ?: Text("SGD total unavailable")
                        }
                        if (receipt.imagePath.isNotBlank() && File(receipt.imagePath).exists()) {
                            AsyncImage(model = File(receipt.imagePath), contentDescription = "Receipt image", modifier = Modifier.padding(4.dp).size(64.dp))
                        } else Text("Photo unavailable")
                    }
                }
            }
            if (available.isEmpty()) Text("No unclaimed receipts are available.")
            Button(
                onClick = { viewModel.createClaim(title, startDate, endDate, selectedReceipts) { title = ""; startDate = ""; endDate = ""; selected = emptySet() } },
                enabled = !state.loading && selectedReceipts.isNotEmpty() && selectedRatesReady && selectedImagesReady,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.loading) "Saving…" else "Create claim (${selectedReceipts.size} receipts)") }
            state.error?.let { Text(it) }
            state.message?.let { Text(it) }
            Text("Your claims")
            if (state.claims.isEmpty()) Text("No claims saved yet.")
            state.claims.forEach { localClaim ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(localClaim.claim.title)
                        Text(claimReceiptSummary(localClaim.receipts.size, localClaim.claim.startDate, localClaim.claim.endDate))
                        Button(onClick = { viewModel.generateReport(localClaim) }, enabled = state.generatingClaimId == null) {
                            Text(if (state.generatingClaimId == localClaim.claim.id) "Creating reports…" else "Generate reports")
                        }
                        latestReportsByType(localClaim.files).forEach { file ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${file.type}: ${file.name}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { viewModel.openFile(file) }) { Text("Open") }
                                    OutlinedButton(onClick = { viewModel.shareFile(file) }) { Text("Share") }
                                    OutlinedButton(onClick = {
                                        pendingFileId = file.id
                                        saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = file.mimeType
                                            putExtra(Intent.EXTRA_TITLE, file.name)
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
