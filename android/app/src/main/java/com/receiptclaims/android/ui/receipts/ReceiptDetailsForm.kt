package com.receiptclaims.android.ui.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.receiptclaims.android.data.local.ReceiptDetails
import com.receiptclaims.android.data.local.ReportDetailsCodec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptDetailsForm(details: ReceiptDetails, onChange: (ReceiptDetails) -> Unit, modifier: Modifier = Modifier) {
    var unitMenu by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Optional report details. Values are saved with this receipt.")
        OutlinedTextField(details.travelStart.orEmpty(), { onChange(details.copy(travelStart = it.take(5).ifBlank { null })) }, label = { Text("Travel start time (HH:mm)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(details.travelEnd.orEmpty(), { onChange(details.copy(travelEnd = it.take(5).ifBlank { null })) }, label = { Text("Travel end time (HH:mm)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(details.fromLocation.orEmpty(), { onChange(details.copy(fromLocation = it.take(160).ifBlank { null })) }, label = { Text("From") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(details.toLocation.orEmpty(), { onChange(details.copy(toLocation = it.take(160).ifBlank { null })) }, label = { Text("To") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(details.purpose.orEmpty(), { onChange(details.copy(purpose = it.take(500).ifBlank { null })) }, label = { Text("Purpose") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(details.eventName.orEmpty(), { onChange(details.copy(eventName = it.take(160).ifBlank { null })) }, label = { Text("Event name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(details.gstAmount.orEmpty(), { onChange(details.copy(gstAmount = it.take(64).ifBlank { null })) }, label = { Text("GST included in amount") }, supportingText = { Text("Enter the GST portion already included in the receipt total; it is not added again.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        ExposedDropdownMenuBox(expanded = unitMenu, onExpandedChange = { unitMenu = it }) {
            OutlinedTextField(details.businessUnit ?: "Not selected", {}, readOnly = true, label = { Text("Business unit") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
            DropdownMenu(unitMenu, onDismissRequest = { unitMenu = false }) {
                DropdownMenuItem(text = { Text("Not selected") }, onClick = { onChange(details.copy(businessUnit = "")); unitMenu = false })
                ReportDetailsCodec.businessUnits.forEach { unit -> DropdownMenuItem(text = { Text(unit) }, onClick = { onChange(details.copy(businessUnit = unit)); unitMenu = false }) }
            }
        }
        OutlinedTextField(details.costCentre1.orEmpty(), { onChange(details.copy(costCentre1 = it.take(80))) }, label = { Text("Cost centre 1") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(details.departmentCode.orEmpty(), { onChange(details.copy(departmentCode = it.take(80))) }, label = { Text("Department code") }, modifier = Modifier.fillMaxWidth())
    }
}
