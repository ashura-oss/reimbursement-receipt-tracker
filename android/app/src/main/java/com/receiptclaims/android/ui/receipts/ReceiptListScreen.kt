package com.receiptclaims.android.ui.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptListScreen(onBack: () -> Unit, viewModel: ReceiptViewModel = hiltViewModel()) {
    val receipts by viewModel.receipts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    var fullSizeImage by remember { mutableStateOf<File?>(null) }
    fullSizeImage?.let { image ->
        AlertDialog(
            onDismissRequest = { fullSizeImage = null },
            confirmButton = { TextButton(onClick = { fullSizeImage = null }) { Text("Close") } },
            text = {
                AsyncImage(
                    model = image,
                    contentDescription = "Full-size receipt image",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                    contentScale = ContentScale.Fit
                )
            }
        )
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("My receipts") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = viewModel::refreshReceipts) { Icon(Icons.Default.Refresh, "Refresh receipts") } }
        )
    }) { padding ->
        if (receipts.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (refreshState.refreshing) "Refreshing receipts…" else "No receipts saved yet.")
                refreshState.error?.let { Text(it) }
                if (refreshState.imageWarning) Text("Some receipt images could not download. Tap refresh to retry.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (refreshState.refreshing) item { Text("Refreshing receipts…") }
                refreshState.error?.let { message -> item { Text(message) } }
                if (refreshState.imageWarning) item { Text("Some receipt images could not download. Tap refresh to retry.") }
                items(receipts, key = { it.clientReceiptId }) { receipt ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (receipt.imagePath.isNotBlank() && File(receipt.imagePath).exists()) {
                                AsyncImage(
                                    model = File(receipt.imagePath),
                                    contentDescription = "Receipt image preview. Tap to view full size.",
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp).clickable { fullSizeImage = File(receipt.imagePath) }.padding(bottom = 8.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Text("${receipt.currency} ${receipt.amount}")
                            Text(receipt.receiptDate)
                            Text(categories.firstOrNull { it.id == receipt.categoryId }?.name ?: "Category ${receipt.categoryId}")
                            receipt.description?.takeIf(String::isNotBlank)?.let { Text(it) }
                            Text("Status: ${receipt.status.toFriendlyStatus()}")
                            receipt.errorMessage?.let { Text(it) }
                            if (receipt.status == com.receiptclaims.android.data.local.ReceiptEntity.STATUS_FAILED || receipt.status == com.receiptclaims.android.data.local.ReceiptEntity.STATUS_PENDING_UPLOAD) {
                                androidx.compose.material3.TextButton(onClick = { viewModel.retryUpload(receipt) }) { Text("Retry upload") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun String.toFriendlyStatus(): String = when (this) {
    com.receiptclaims.android.data.local.ReceiptEntity.STATUS_DRAFT -> "Draft"
    com.receiptclaims.android.data.local.ReceiptEntity.STATUS_PENDING_UPLOAD -> "Waiting to upload"
    com.receiptclaims.android.data.local.ReceiptEntity.STATUS_UPLOADING -> "Uploading…"
    com.receiptclaims.android.data.local.ReceiptEntity.STATUS_UPLOADED -> "Ready to claim"
    com.receiptclaims.android.data.local.ReceiptEntity.STATUS_CLAIMED -> "In a claim"
    com.receiptclaims.android.data.local.ReceiptEntity.STATUS_FAILED -> "Upload failed"
    else -> replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
}
