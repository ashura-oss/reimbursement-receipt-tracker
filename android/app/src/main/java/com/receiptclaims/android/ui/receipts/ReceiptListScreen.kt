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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.receiptclaims.android.data.local.ExpenseAccounts
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptListScreen(onBack: () -> Unit, onStorage: () -> Unit = {}, viewModel: ReceiptViewModel = hiltViewModel()) {
    val receipts by viewModel.receipts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val claimedReceiptKeys by viewModel.claimedReceiptKeys.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    var fullSizeImage by remember { mutableStateOf<File?>(null) }
    var legacyToRelease by remember { mutableStateOf<com.receiptclaims.android.data.local.ReceiptEntity?>(null) }
    var receiptForRate by remember { mutableStateOf<com.receiptclaims.android.data.local.ReceiptEntity?>(null) }
    var rateValue by remember { mutableStateOf("") }
    receiptForRate?.let { receipt ->
        AlertDialog(
            onDismissRequest = { receiptForRate = null },
            title = { Text("Set exchange rate") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the rate used for this receipt. 1 ${receipt.currency} = SGD")
                    OutlinedTextField(rateValue, { rateValue = it }, label = { Text("SGD per ${receipt.currency}") })
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.setExchangeRate(receipt, rateValue); receiptForRate = null }) { Text("Save rate") } },
            dismissButton = { TextButton(onClick = { receiptForRate = null }) { Text("Cancel") } }
        )
    }
    legacyToRelease?.let { receipt ->
        AlertDialog(
            onDismissRequest = { legacyToRelease = null },
            title = { Text("Make this receipt available locally?") },
            text = { Text("This receipt was marked claimed by the previous online app, but its claim details are not stored on this device. Releasing it allows it to be added to a new local claim.") },
            confirmButton = { TextButton(onClick = { viewModel.releaseLegacyClaim(receipt); legacyToRelease = null }) { Text("Make available") } },
            dismissButton = { TextButton(onClick = { legacyToRelease = null }) { Text("Cancel") } }
        )
    }
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
            actions = { TextButton(onClick = onStorage) { Text("Find and manage") } },
        )
    }) { padding ->
        if (receipts.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No receipts saved yet.")
                Text("Receipts and photos are stored on this device.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Text("Stored on this device") }
                saveState.error?.let { message -> item { Text(message) } }
                items(receipts, key = { "${it.ownerUserId}:${it.clientReceiptId}" }) { receipt ->
                    var expenseAccountExpanded by remember(receipt.ownerUserId, receipt.clientReceiptId) { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (receipt.imagePath.isNotBlank() && File(receipt.imagePath).exists()) {
                                AsyncImage(
                                    model = File(receipt.imagePath),
                                    contentDescription = "Receipt image preview. Tap to view full size.",
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp).clickable { fullSizeImage = File(receipt.imagePath) }.padding(bottom = 8.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else Text("Receipt photo unavailable on this device")
                            Text("${receipt.currency} ${receipt.amount}")
                            receipt.sgdEquivalent?.let { Text("SGD $it (rate ${receipt.exchangeRate ?: "unknown"})") }
                                ?: if (receipt.currency != "SGD" && receipt.status == com.receiptclaims.android.data.local.ReceiptEntity.STATUS_SAVED) {
                                    TextButton(onClick = { rateValue = ""; receiptForRate = receipt }) { Text("Set exchange rate manually") }
                                } else Unit
                            Text(receipt.receiptDate)
                            Text(receipt.name?.takeIf(String::isNotBlank) ?: "Receipt · ${receipt.receiptDate}")
                            Text("Classification: ${categories.firstOrNull { it.id == receipt.categoryId }?.name ?: "Category ${receipt.categoryId}"}")
                            val expenseAccount = ExpenseAccounts.find(receipt.expenseAccountId)
                            val inLocalClaim = "${receipt.ownerUserId}:${receipt.clientReceiptId}" in claimedReceiptKeys
                            val canEditAccount = receipt.status == com.receiptclaims.android.data.local.ReceiptEntity.STATUS_SAVED && !inLocalClaim
                            if (canEditAccount) {
                                ExposedDropdownMenuBox(expanded = expenseAccountExpanded, onExpandedChange = { expenseAccountExpanded = it }) {
                                    OutlinedTextField(expenseAccount?.displayName ?: "Not selected", {}, readOnly = true, label = { Text("Expense account (optional)") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expenseAccountExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                    DropdownMenu(expanded = expenseAccountExpanded, onDismissRequest = { expenseAccountExpanded = false }) {
                                        DropdownMenuItem(text = { Text("Not selected") }, onClick = { viewModel.setExpenseAccount(receipt, null); expenseAccountExpanded = false })
                                        ExpenseAccounts.all.forEach { option ->
                                            DropdownMenuItem(text = { Text(option.displayName) }, onClick = { viewModel.setExpenseAccount(receipt, option.id); expenseAccountExpanded = false })
                                        }
                                    }
                                }
                            } else {
                                Text("Expense account: ${expenseAccount?.displayName ?: "Not selected"}")
                            }
                            receipt.description?.takeIf(String::isNotBlank)?.let { Text(it) }
                            Text("Status: ${if (inLocalClaim) "In a local claim" else receipt.status.toFriendlyStatus()}")
                            receipt.errorMessage?.let { Text(it) }
                            if (receipt.status == com.receiptclaims.android.data.local.ReceiptEntity.STATUS_CLAIMED) {
                                Text("Previously claimed online. No claim history was saved on this device.")
                                TextButton(onClick = { legacyToRelease = receipt }) { Text("Make available for a local claim…") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun String.toFriendlyStatus(): String = when (this) {
    com.receiptclaims.android.data.local.ReceiptEntity.STATUS_SAVED -> "Saved on this device"
    com.receiptclaims.android.data.local.ReceiptEntity.STATUS_CLAIMED -> "In a claim"
    else -> replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
}
