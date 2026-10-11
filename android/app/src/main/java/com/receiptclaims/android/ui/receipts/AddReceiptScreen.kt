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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReceiptScreen(onBack: () -> Unit, onSaved: () -> Unit, viewModel: ReceiptViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("SGD") }
    var receiptDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var categoryId by remember { mutableStateOf<Int?>(null) }
    var description by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var currencyMenuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true else error = "Camera permission is required to capture a receipt."
    }
    val pickImage = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) sourceUri = uri }

    LaunchedEffect(categories) { if (categoryId == null) categoryId = categories.firstOrNull()?.id }

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
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = currencyMenuOpen, onExpandedChange = { currencyMenuOpen = !currencyMenuOpen }) {
                OutlinedTextField(currency, {}, readOnly = true, label = { Text("Currency") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(currencyMenuOpen) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(expanded = currencyMenuOpen, onDismissRequest = { currencyMenuOpen = false }) {
                    listOf("SGD", "INR", "USD", "EUR", "GBP", "AUD", "MYR", "THB", "JPY", "CNY").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { currency = option; currencyMenuOpen = false })
                    }
                }
            }
            OutlinedTextField(receiptDate, { receiptDate = it }, label = { Text("Receipt date (YYYY-MM-DD)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = categoryMenuOpen, onExpandedChange = { categoryMenuOpen = !categoryMenuOpen }) {
                val selectedCategory = categories.firstOrNull { it.id == categoryId }
                OutlinedTextField(selectedCategory?.name ?: "Select classification", {}, readOnly = true, label = { Text("Classification") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryMenuOpen) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
                    categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) }, onClick = { categoryId = category.id; categoryMenuOpen = false }) }
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
                    when {
                        sourceUri == null -> error = "Add a receipt image first."
                        parsedAmount == null || parsedAmount <= java.math.BigDecimal.ZERO -> error = "Enter a valid amount."
                        !validDate -> error = "Enter a valid date as YYYY-MM-DD."
                        categoryId == null -> error = "Choose a classification."
                        else -> viewModel.saveReceipt(amount.trim(), currency, receiptDate.trim(), categoryId!!, description.trim().ifBlank { null }, note.trim().ifBlank { null }, sourceUri!!, onSaved)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saveState.saving) "Saving…" else "Save receipt") }
        }
    }
}
