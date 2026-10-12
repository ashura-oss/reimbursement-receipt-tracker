package com.receiptclaims.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.receiptclaims.android.ui.auth.LoginScreen
import com.receiptclaims.android.ui.auth.LoginViewModel
import com.receiptclaims.android.ui.auth.RegisterScreen
import com.receiptclaims.android.ui.auth.RegisterViewModel
import com.receiptclaims.android.ui.claims.ClaimsScreen
import com.receiptclaims.android.ui.home.HomeScreen
import com.receiptclaims.android.ui.receipts.AddReceiptScreen
import com.receiptclaims.android.ui.receipts.ReceiptListScreen

private object Destinations {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home"
    const val RECEIPTS = "receipts"
    const val ADD_RECEIPT = "add-receipt"
    const val CLAIMS = "claims"
}

@Composable
fun ReceiptClaimsApp() {
    val loginViewModel: LoginViewModel = hiltViewModel()
    val sessionViewModel: SessionViewModel = hiltViewModel()
    val sessionState by sessionViewModel.state.collectAsStateWithLifecycle()
    if (sessionState.loading) return
    // Authentication is the navigation root: replacing the graph on session changes avoids
    // competing callback/effect back-stack mutations during login, logout, and token expiry.
    key(sessionState.authenticated) {
        val navController = rememberNavController()
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = if (sessionState.authenticated) Destinations.HOME else Destinations.LOGIN) {
                composable(Destinations.LOGIN) {
                    LoginScreen(
                        viewModel = loginViewModel,
                        onCreateAccount = { navController.navigate(Destinations.REGISTER) }
                    )
                }
                composable(Destinations.REGISTER) {
                    val registerViewModel: RegisterViewModel = hiltViewModel()
                    RegisterScreen(
                        viewModel = registerViewModel,
                        onBack = { navController.popBackStack() },
                        onCreated = { navController.popBackStack() }
                    )
                }
                composable(Destinations.HOME) {
                    val activity = LocalContext.current.findActivity()
                    BackHandler {
                        // Android's root-task Back behavior backgrounds the app and preserves Home.
                        if (activity?.moveTaskToBack(true) != true) activity?.finish()
                    }
                    HomeScreen(
                        onReceipts = { navController.navigate(Destinations.RECEIPTS) },
                        onAddReceipt = { navController.navigate(Destinations.ADD_RECEIPT) },
                        onClaims = { navController.navigate(Destinations.CLAIMS) },
                        onLogout = { sessionViewModel.logout() },
                        username = sessionState.username
                    )
                }
                composable(Destinations.RECEIPTS) {
                    ReceiptListScreen(onBack = { navController.popBackStack() })
                }
                composable(Destinations.ADD_RECEIPT) {
                    AddReceiptScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
                }
                composable(Destinations.CLAIMS) {
                    ClaimsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
