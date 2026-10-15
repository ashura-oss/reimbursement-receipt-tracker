package com.receiptclaims.android.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showExportWarning by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Local backup") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Save a copy of your receipts, claims, categories, settings, and available report files on a device or drive you choose.")
            Text("The backup contains private bills and receipt images. Anyone with the ZIP file can read them. It is not password protected.")
            Button(onClick = { showExportWarning = true }, enabled = !state.working, modifier = Modifier.fillMaxWidth()) { Text("Create backup") }
            OutlinedButton(onClick = { importPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !state.working, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
            if (state.working) CircularProgressIndicator()
            state.message?.let { Text(it) }
            state.warnings.forEach { Text("Warning: $it") }
            state.error?.let { Text("Error: $it") }
        }
    }
    if (showExportWarning) {
        AlertDialog(
            onDismissRequest = { showExportWarning = false },
            title = { Text("Create a private backup?") },
            text = { Text("This ZIP includes receipt photos and saved report files. Choose a destination you trust; the file is not encrypted.") },
            confirmButton = { TextButton(onClick = { showExportWarning = false; exportPicker.launch("receipt-claims-backup-${LocalDate.now()}.zip") }) { Text("Choose destination") } },
            dismissButton = { TextButton(onClick = { showExportWarning = false }) { Text("Cancel") } }
        )
    }
}
