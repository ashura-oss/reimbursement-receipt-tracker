package com.receiptclaims.android.storage

import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.receiptclaims.android.data.local.ExpenseAccounts
import com.receiptclaims.android.data.local.ReceiptEntity
import com.receiptclaims.android.data.local.ReportDetailsCodec
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var fromDate by rememberSaveable { mutableStateOf("") }
    var toDate by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<Int?>(null) }
    var appliedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var appliedTo by rememberSaveable { mutableStateOf<String?>(null) }
    var appliedCategory by rememberSaveable { mutableStateOf<Int?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var filterError by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCodes by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var detail by remember { mutableStateOf<ReceiptEntity?>(null) }
    var zoomImage by remember { mutableStateOf<File?>(null) }

    fun key(receipt: ReceiptEntity) = ReceiptKey(receipt.ownerUserId, receipt.clientReceiptId)
    fun code(receiptKey: ReceiptKey) = "${receiptKey.ownerUserId}|${receiptKey.clientReceiptId}"
    val appliedFilter = StorageReceiptFilter(appliedFrom, appliedTo, appliedCategory)
    val visible = filterReceipts(state.receipts, appliedFilter)
    val visibleCodes = visible.mapTo(linkedSetOf()) { code(key(it)) }
    LaunchedEffect(appliedFilter, state.receipts) { selectedCodes = selectedCodes.filter { it in visibleCodes } }

    val preview = state.deletePreview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeletePreview() },
            title = { Text("Delete selected receipts?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This removes ${preview.receipts} receipt(s), ${preview.claims} affected claim(s), ${preview.reports} saved report(s), and up to ${preview.fileCount} private file(s). Other receipts stay saved and can be used in new claims.")
                    preview.receiptNames.take(8).forEach { Text("• $it") }
                    if (preview.receiptNames.size > 8) Text("and ${preview.receiptNames.size - 8} more receipt(s)")
                    if (preview.claimTitles.isNotEmpty()) {
                        Text("Affected claims:")
                        preview.claimTitles.take(8).forEach { Text("• $it") }
                        if (preview.claimTitles.size > 8) Text("and ${preview.claimTitles.size - 8} more claim(s)")
                    }
                    Text("Claims that include a selected receipt are removed as a whole. Their other receipts remain saved and reusable.")
                }
            },
            confirmButton = { TextButton(enabled = !state.working, onClick = viewModel::confirmDelete) { Text(if (state.working) "Deleting…" else "Delete") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDeletePreview) { Text("Cancel") } }
        )
    }
    detail?.let { receipt ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(receipt.name?.takeIf(String::isNotBlank) ?: "Receipt · ${receipt.receiptDate}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${receipt.currency} ${receipt.amount} · ${receipt.receiptDate}")
                    receipt.sgdEquivalent?.let { Text("SGD $it · rate ${receipt.exchangeRate ?: "unknown"}") }
                    Text(state.categories.firstOrNull { it.id == receipt.categoryId }?.name ?: "Classification ${receipt.categoryId}")
                    Text("Expense account: ${ExpenseAccounts.find(receipt.expenseAccountId)?.displayName ?: "Not selected"}")
                    runCatching { ReportDetailsCodec.decodeReceipt(receipt.detailsJson, receipt.amount).displayLines() }.getOrDefault(emptyList()).forEach { Text(it) }
                    receipt.description?.takeIf(String::isNotBlank)?.let { Text(it) }
                    receipt.note?.takeIf(String::isNotBlank)?.let { Text("Note: $it") }
                    val image = receipt.imagePath.takeIf(String::isNotBlank)?.let(::File)
                    if (image != null && image.isFile) {
                        AsyncImage(model = image, contentDescription = "Receipt photo. Tap to zoom.", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clickable { zoomImage = image }, contentScale = ContentScale.Fit)
                        TextButton(onClick = { zoomImage = image }) { Text("Open full-size photo") }
                    } else Text("Receipt photo unavailable on this device")
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } }
        )
    }
    zoomImage?.let { image ->
        ReceiptImageViewerDialog(image, onDismiss = { zoomImage = null })
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Find and manage receipts") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.usage?.let { usage -> item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${state.receipts.size} receipts · ${formatBytes(usage.receiptPhotoBytes)} receipt photos · ${formatBytes(usage.reportFileBytes)} saved reports")
                        Text("App data ${formatBytes(usage.managedBytes)} · ${formatBytes(usage.availableBytes)} device storage available")
                    }
                }
            } }
            item { OutlinedTextField(fromDate, { fromDate = it }, label = { Text("From date, inclusive (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { OutlinedTextField(toDate, { toDate = it }, label = { Text("To date, inclusive (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item {
                ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = it }) {
                    OutlinedTextField(
                        value = state.categories.firstOrNull { it.id == categoryId }?.name ?: "All classifications",
                        onValueChange = {}, readOnly = true, label = { Text("Classification") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("All classifications") }, onClick = { categoryId = null; menuExpanded = false })
                        state.categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) }, onClick = { categoryId = category.id; menuExpanded = false }) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            val start = fromDate.trim().takeIf(String::isNotEmpty)?.also { LocalDate.parse(it) }?.toString()
                            val end = toDate.trim().takeIf(String::isNotEmpty)?.also { LocalDate.parse(it) }?.toString()
                            require(start == null || end == null || start <= end) { "From date must be on or before the To date." }
                            appliedFrom = start; appliedTo = end; appliedCategory = categoryId
                        }.onSuccess { filterError = null }.onFailure { filterError = it.message ?: "Enter valid date filters." }
                    }) { Text("Apply filters") }
                    OutlinedButton(onClick = {
                        fromDate = ""; toDate = ""; categoryId = null
                        appliedFrom = null; appliedTo = null; appliedCategory = null; filterError = null
                    }) { Text("Clear") }
                }
                filterError?.let { Text(it) }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${visible.size} matching · ${selectedCodes.count { it in visibleCodes }} selected")
                    TextButton(onClick = {
                        selectedCodes = if (visibleCodes.isNotEmpty() && selectedCodes.containsAll(visibleCodes)) selectedCodes - visibleCodes else (selectedCodes + visibleCodes).distinct()
                    }) { Text(if (visibleCodes.isNotEmpty() && selectedCodes.containsAll(visibleCodes)) "Clear selection" else "Select all matching") }
                }
            }
            if (selectedCodes.any { it in visibleCodes }) item {
                Button(enabled = !state.working, onClick = {
                    val keys = visible.filter { code(key(it)) in selectedCodes }.mapTo(linkedSetOf(), ::key)
                    viewModel.previewDelete(keys)
                }, modifier = Modifier.fillMaxWidth()) { Text("Delete selected (${selectedCodes.count { it in visibleCodes }})") }
            }
            state.error?.let { item { Text(it) } }
            state.message?.let { item { Text(it) } }
            if (visible.isEmpty()) item { Text(if (state.loading) "Loading receipts…" else "No receipts match these filters.") }
            items(visible, key = { "${it.ownerUserId}:${it.clientReceiptId}" }) { receipt ->
                val receiptKey = key(receipt)
                val selected = code(receiptKey) in selectedCodes
                Card(Modifier.fillMaxWidth().clickable { detail = receipt }) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = selected, onCheckedChange = { checked -> selectedCodes = if (checked) selectedCodes + code(receiptKey) else selectedCodes - code(receiptKey) })
                        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(receipt.name?.takeIf(String::isNotBlank) ?: receipt.description?.takeIf(String::isNotBlank) ?: "Receipt · ${receipt.receiptDate}")
                            Text("${receipt.currency} ${receipt.amount} · ${receipt.receiptDate}")
                            Text(state.categories.firstOrNull { it.id == receipt.categoryId }?.name ?: "Classification ${receipt.categoryId}")
                        }
                        OutlinedButton(enabled = !state.working, onClick = { viewModel.previewDelete(setOf(receiptKey)) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReceiptImageViewerDialog(image: File, onDismiss: () -> Unit) {
    var scale by rememberSaveable(image.path) { mutableFloatStateOf(1f) }
    var panX by rememberSaveable(image.path) { mutableFloatStateOf(0f) }
    var panY by rememberSaveable(image.path) { mutableFloatStateOf(0f) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        Surface(Modifier.fillMaxWidth().height(screenHeight)) {
            Column(Modifier.fillMaxWidth().height(screenHeight).statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Receipt photo · pinch to zoom", Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Box(Modifier.fillMaxWidth().height((screenHeight - 240.dp).coerceAtLeast(96.dp)).clipToBounds()) {
                    AsyncImage(
                        model = image,
                        contentDescription = "Zoomable full-size receipt photo",
                        modifier = Modifier.fillMaxSize().clipToBounds().pointerInput(image.path) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 6f)
                                scale = nextScale
                                if (nextScale == 1f) { panX = 0f; panY = 0f }
                                else {
                                    val maxX = size.width * (nextScale - 1f) / 2f
                                    val maxY = size.height * (nextScale - 1f) / 2f
                                    panX = (panX + pan.x).coerceIn(-maxX, maxX)
                                    panY = (panY + pan.y).coerceIn(-maxY, maxY)
                                }
                            }
                        }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY),
                        contentScale = ContentScale.Fit
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = {
                        scale = (scale / 1.25f).coerceAtLeast(1f)
                        if (scale == 1f) { panX = 0f; panY = 0f }
                    }) { Text("Zoom out") }
                    Text("${(scale * 100).toInt()}%", Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                    TextButton(onClick = { scale = (scale * 1.25f).coerceAtMost(6f) }) { Text("Zoom in") }
                    TextButton(onClick = { scale = 1f; panX = 0f; panY = 0f }) { Text("Reset") }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
