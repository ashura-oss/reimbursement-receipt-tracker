package com.receiptclaims.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.receiptclaims.android.data.local.ReportDetailsCodec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffProfileScreen(onBack: () -> Unit, viewModel: StaffProfileViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Staff details") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Optional defaults for new claims and receipts. Changes here do not change saved records.")
            OutlinedTextField(profile.name, { viewModel.update(profile.copy(name = it.take(120))) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(profile.position, { viewModel.update(profile.copy(position = it.take(120))) }, label = { Text("Position") }, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(profile.businessUnit.ifBlank { "Not selected" }, {}, readOnly = true, label = { Text("Business unit") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Not selected") }, onClick = { viewModel.update(profile.copy(businessUnit = "")); expanded = false })
                    ReportDetailsCodec.businessUnits.forEach { unit -> DropdownMenuItem(text = { Text(unit) }, onClick = { viewModel.update(profile.copy(businessUnit = unit)); expanded = false }) }
                }
            }
            OutlinedTextField(profile.costCentre1, { viewModel.update(profile.copy(costCentre1 = it.take(80))) }, label = { Text("Cost centre 1") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(profile.departmentCode, { viewModel.update(profile.copy(departmentCode = it.take(80))) }, label = { Text("Department code") }, modifier = Modifier.fillMaxWidth())
            message?.let { Text(it) }
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text("Save staff details") }
        }
    }
}
