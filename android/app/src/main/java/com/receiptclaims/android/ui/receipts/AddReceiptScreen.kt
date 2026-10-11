package com.receiptclaims.android.ui.receipts

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeParseException
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReceiptScreen(onBack: () -> Unit, onSaved: () -> Unit, viewModel: ReceiptViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    var amount by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("SGD") }
    var receiptDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var categoryId by rememberSaveable { mutableStateOf<Int?>(null) }
    var description by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var sourceUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var expandedMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true else error = "Camera permission is required to capture a receipt."
    }
    val pickImage = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            sourceUri?.let { old ->
                if (old.scheme == "file" && old.path?.startsWith(File(context.filesDir, "receipt-staging").absolutePath) == true) File(old.path!!).delete()
            }
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sourceUri = uri
        }
    }

    if (showCamera) {
        CameraCaptureScreen(onCaptured = { sourceUri = it; showCamera = false }, onCancel = { showCamera = false })
        return
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Add receipt") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("Take photo") }
            OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) { Text("Choose from gallery") }
            Text(if (sourceUri == null) "No receipt image selected" else "Receipt image selected")
            sourceUri?.let { AsyncImage(model = it, contentDescription = "Selected receipt preview", modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = expandedMenu == "currency", onExpandedChange = { expandedMenu = if (it) "currency" else null }) {
                OutlinedTextField(currency, {}, readOnly = true, label = { Text("Currency") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedMenu == "currency") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(expanded = expandedMenu == "currency", onDismissRequest = { expandedMenu = null }) {
                    listOf("SGD", "INR", "USD", "EUR", "GBP", "AUD", "MYR", "THB", "JPY", "CNY").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { currency = option; expandedMenu = null })
                    }
                }
            }
            OutlinedTextField(receiptDate, { receiptDate = it }, label = { Text("Receipt date (YYYY-MM-DD)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = expandedMenu == "category", onExpandedChange = { expandedMenu = if (it) "category" else null }) {
                val selectedCategory = categories.firstOrNull { it.id == categoryId }
                OutlinedTextField(selectedCategory?.name ?: "Choose a classification", {}, readOnly = true, label = { Text("Classification (required)") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedMenu == "category") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(expanded = expandedMenu == "category", onDismissRequest = { expandedMenu = null }) {
                    categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) }, onClick = { categoryId = category.id; expandedMenu = null }) }
                }
            }
            OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it) }
            saveState.error?.let { Text(it) }
            Button(
                enabled = !saveState.saving,
                onClick = {
                    val parsedAmount = amount.toBigDecimalOrNull()
                    val validDate = runCatching { LocalDate.parse(receiptDate.trim()) }.isSuccess
                    val normalizedAmount = parsedAmount?.stripTrailingZeros()?.let { value ->
                        value.takeIf { it.scale() <= 2 && it > java.math.BigDecimal.ZERO && it <= java.math.BigDecimal("1000000000") }
                            ?.setScale(2, java.math.RoundingMode.UNNECESSARY)?.toPlainString()
                    }
                    when {
                        sourceUri == null -> error = "Add a receipt image first."
                        normalizedAmount == null -> error = "Enter an amount greater than 0 and no more than 1,000,000,000, with at most 2 decimal places."
                        !validDate -> error = "Enter a valid date as YYYY-MM-DD."
                        categoryId == null -> error = "Choose a classification."
                        else -> {
                            val selectedImage = sourceUri!!
                            viewModel.saveReceipt(normalizedAmount, currency, receiptDate.trim(), categoryId!!, description.trim().ifBlank { null }, note.trim().ifBlank { null }, selectedImage) {
                                if (selectedImage.scheme == "file" && selectedImage.path?.startsWith(File(context.filesDir, "receipt-staging").absolutePath) == true) File(selectedImage.path!!).delete()
                                onSaved()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saveState.saving) "Saving…" else "Save receipt") }
        }
    }
}
