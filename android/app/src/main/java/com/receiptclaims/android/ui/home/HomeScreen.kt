package com.receiptclaims.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onReceipts: () -> Unit, onAddReceipt: () -> Unit, onClaims: () -> Unit, onBackup: () -> Unit, onStorage: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Receipt claims") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Your receipts, claims, and reports stay on this device.")
            Button(onClick = onAddReceipt, modifier = Modifier.fillMaxWidth()) { Text("Add receipt") }
            OutlinedButton(onClick = onReceipts, modifier = Modifier.fillMaxWidth()) { Text("My receipts") }
            OutlinedButton(onClick = onClaims, modifier = Modifier.fillMaxWidth()) { Text("Claims and reports") }
            OutlinedButton(onClick = onStorage, modifier = Modifier.fillMaxWidth()) { Text("Storage management") }
            OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text("Backup and restore") }
        }
    }
}
