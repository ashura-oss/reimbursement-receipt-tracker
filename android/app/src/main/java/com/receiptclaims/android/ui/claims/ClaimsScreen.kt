package com.receiptclaims.android.ui.claims

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.local.ClaimDetails
import com.receiptclaims.android.data.local.SignatureStroke
import com.receiptclaims.android.data.local.NormalizedPoint
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
    var additionalDetails by rememberSaveable { mutableStateOf(false) }
    var claimDetails by rememberSaveable(stateSaver = claimDetailsSaver) { mutableStateOf(ClaimDetails()) }
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
            OutlinedButton(onClick = { additionalDetails = !additionalDetails }, modifier = Modifier.fillMaxWidth()) { Text(if (additionalDetails) "Hide claim form details" else "Add claim form details (optional)") }
            if (additionalDetails) {
                Text("These fields are optional and saved with this claim. Staff position defaults from Staff details.")
                OutlinedTextField(claimDetails.position ?: state.staffProfile.position, { claimDetails = claimDetails.copy(position = it.take(120)) }, label = { Text("Position") }, modifier = Modifier.fillMaxWidth())
                var paymentMenu by rememberSaveable { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = paymentMenu, onExpandedChange = { paymentMenu = it }) {
                    OutlinedTextField(claimDetails.paymentMethod ?: "Not selected", {}, readOnly = true, label = { Text("Payment method (optional)") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(paymentMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    DropdownMenu(paymentMenu, onDismissRequest = { paymentMenu = false }) {
                        listOf("Not selected", "Cash", "Cheque", "PayNow").forEach { method -> DropdownMenuItem(text = { Text(method) }, onClick = { claimDetails = claimDetails.copy(paymentMethod = method.takeUnless { it == "Not selected" }); paymentMenu = false }) }
                    }
                }
                if (claimDetails.paymentMethod.equals("Cheque", true)) OutlinedTextField(claimDetails.chequeNumber.orEmpty(), { claimDetails = claimDetails.copy(chequeNumber = it.take(80)) }, label = { Text("Cheque number (optional)") }, modifier = Modifier.fillMaxWidth())
                if (claimDetails.paymentMethod == "PayNow") OutlinedTextField(claimDetails.paynowReference.orEmpty(), { claimDetails = claimDetails.copy(paynowReference = it.take(120)) }, label = { Text("PayNow reference (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(claimDetails.cashAdvance.orEmpty(), { claimDetails = claimDetails.copy(cashAdvance = it.take(64)) }, label = { Text("Cash advance (SGD, optional)") }, supportingText = { Text("Added as shown in the report template; this is not deducted.") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(claimDetails.submittedBy.orEmpty(), { claimDetails = claimDetails.copy(submittedBy = it.take(120)) }, label = { Text("Submitted by") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(claimDetails.submittedDate.orEmpty(), { claimDetails = claimDetails.copy(submittedDate = it.take(10)) }, label = { Text("Submitted date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                SignatureInput("Submitted signature (optional)", claimDetails.submittedSignature) { claimDetails = claimDetails.copy(submittedSignature = it) }
                OutlinedTextField(claimDetails.checkedBy.orEmpty(), { claimDetails = claimDetails.copy(checkedBy = it.take(120)) }, label = { Text("Checked by") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(claimDetails.checkedDate.orEmpty(), { claimDetails = claimDetails.copy(checkedDate = it.take(10)) }, label = { Text("Checked date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                SignatureInput("Checked signature (optional)", claimDetails.checkedSignature) { claimDetails = claimDetails.copy(checkedSignature = it) }
                OutlinedTextField(claimDetails.approvedBy.orEmpty(), { claimDetails = claimDetails.copy(approvedBy = it.take(120)) }, label = { Text("Approved by") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(claimDetails.approvedDate.orEmpty(), { claimDetails = claimDetails.copy(approvedDate = it.take(10)) }, label = { Text("Approved date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                SignatureInput("Approved signature (optional)", claimDetails.approvedSignature) { claimDetails = claimDetails.copy(approvedSignature = it) }
                OutlinedTextField(claimDetails.receivedBy.orEmpty(), { claimDetails = claimDetails.copy(receivedBy = it.take(120)) }, label = { Text("Received by") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(claimDetails.receivedDate.orEmpty(), { claimDetails = claimDetails.copy(receivedDate = it.take(10)) }, label = { Text("Received date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                SignatureInput("Received signature (optional)", claimDetails.receivedSignature) { claimDetails = claimDetails.copy(receivedSignature = it) }
            }
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
                onClick = { viewModel.createClaim(title, startDate, endDate, selectedReceipts, claimDetails) { title = ""; startDate = ""; endDate = ""; selected = emptySet(); claimDetails = ClaimDetails() } },
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

@Composable
private fun SignatureInput(label: String, strokes: List<SignatureStroke>, onChange: (List<SignatureStroke>) -> Unit) {
    var active by remember { mutableStateOf<SignatureStroke?>(null) }
    val latestStrokes by rememberUpdatedState(strokes)
    val latestOnChange by rememberUpdatedState(onChange)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(5f).background(Color.White)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { point -> active = SignatureStroke(listOf(NormalizedPoint((point.x / size.width).coerceIn(0f, 1f), (point.y / size.height).coerceIn(0f, 1f)))) },
                        onDrag = { change, _ ->
                            change.consume()
                            val point = change.position
                            active?.let { stroke -> if (stroke.points.size < 500 && latestStrokes.sumOf { it.points.size } + stroke.points.size < 2_000) active = stroke.copy(points = stroke.points + NormalizedPoint((point.x / size.width).coerceIn(0f, 1f), (point.y / size.height).coerceIn(0f, 1f))) }
                        },
                        onDragEnd = { active?.let { latestOnChange((latestStrokes + it).takeLast(20)) }; active = null },
                        onDragCancel = { active = null }
                    )
                }
        ) {
            (strokes + listOfNotNull(active)).forEach { stroke ->
                stroke.points.zipWithNext().forEach { (a, b) ->
                    drawLine(Color.Black, Offset(a.x * size.width, a.y * size.height), Offset(b.x * size.width, b.y * size.height), strokeWidth = 3.dp.toPx())
                }
            }
        }
        OutlinedButton(onClick = { onChange(emptyList()) }) { Text("Clear") }
    }
}

private val claimDetailsSaver = Saver<ClaimDetails, String>(
    save = { com.receiptclaims.android.data.local.ReportDetailsCodec.encodeClaimDraft(it) },
    restore = { com.receiptclaims.android.data.local.ReportDetailsCodec.decodeClaimDraft(it) }
)
