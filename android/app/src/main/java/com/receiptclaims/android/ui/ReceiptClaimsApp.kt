package com.receiptclaims.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    val navController = rememberNavController()
    val loginViewModel: LoginViewModel = hiltViewModel()
    val sessionViewModel: SessionViewModel = hiltViewModel()
    val sessionState by sessionViewModel.state.collectAsStateWithLifecycle()
    if (sessionState.loading) return
    Surface(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = if (sessionState.authenticated) Destinations.HOME else Destinations.LOGIN) {
            composable(Destinations.LOGIN) {
                LoginScreen(
                    viewModel = loginViewModel,
                    onLoggedIn = { navController.navigate(Destinations.HOME) { popUpTo(Destinations.LOGIN) { inclusive = true } } },
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
                HomeScreen(
                    onReceipts = { navController.navigate(Destinations.RECEIPTS) },
                    onAddReceipt = { navController.navigate(Destinations.ADD_RECEIPT) },
                    onClaims = { navController.navigate(Destinations.CLAIMS) },
                    onLogout = {
                        sessionViewModel.logout {
                            navController.navigate(Destinations.LOGIN) { popUpTo(navController.graph.id) { inclusive = true } }
                        }
                    }
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
