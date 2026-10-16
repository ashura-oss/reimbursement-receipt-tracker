package com.receiptclaims.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.receiptclaims.android.backup.BackupScreen
import com.receiptclaims.android.ui.claims.ClaimsScreen
import com.receiptclaims.android.ui.home.HomeScreen
import com.receiptclaims.android.ui.home.StaffProfileScreen
import com.receiptclaims.android.ui.receipts.AddReceiptScreen
import com.receiptclaims.android.ui.receipts.ReceiptListScreen
import com.receiptclaims.android.storage.StorageScreen

private object Destinations {
    const val HOME = "home"
    const val RECEIPTS = "receipts"
    const val ADD_RECEIPT = "add-receipt"
    const val CLAIMS = "claims"
    const val BACKUP = "backup"
    const val STORAGE = "storage"
    const val STAFF = "staff-details"
}

@Composable
fun ReceiptClaimsApp() {
    val navController = rememberNavController()
    Surface(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Destinations.HOME) {
            composable(Destinations.HOME) {
                val activity = LocalContext.current.findActivity()
                BackHandler { if (activity?.moveTaskToBack(true) != true) activity?.finish() }
                HomeScreen(
                    onReceipts = { navController.navigate(Destinations.RECEIPTS) },
                    onAddReceipt = { navController.navigate(Destinations.ADD_RECEIPT) },
                    onClaims = { navController.navigate(Destinations.CLAIMS) },
                    onBackup = { navController.navigate(Destinations.BACKUP) },
                    onStorage = { navController.navigate(Destinations.STORAGE) },
                    onStaffDetails = { navController.navigate(Destinations.STAFF) }
                )
            }
            composable(Destinations.RECEIPTS) {
                ReceiptListScreen(onBack = { navController.popBackStack() }, onStorage = { navController.navigate(Destinations.STORAGE) })
            }
            composable(Destinations.ADD_RECEIPT) { AddReceiptScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() }) }
            composable(Destinations.CLAIMS) { ClaimsScreen(onBack = { navController.popBackStack() }) }
            composable(Destinations.BACKUP) { BackupScreen(onBack = { navController.popBackStack() }) }
            composable(Destinations.STORAGE) { StorageScreen(onBack = { navController.popBackStack() }) }
            composable(Destinations.STAFF) { StaffProfileScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
