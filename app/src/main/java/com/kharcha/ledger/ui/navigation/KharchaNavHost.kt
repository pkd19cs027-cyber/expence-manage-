package com.kharcha.ledger.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.RuleFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kharcha.ledger.ui.accounts.AccountsScreen
import com.kharcha.ledger.ui.dashboard.DashboardScreen
import com.kharcha.ledger.ui.insights.InsightsScreen
import com.kharcha.ledger.ui.review.ReviewScreen
import com.kharcha.ledger.ui.search.SearchScreen
import com.kharcha.ledger.ui.settings.SettingsScreen
import com.kharcha.ledger.ui.transactions.TransactionDetailScreen
import com.kharcha.ledger.ui.transactions.TransactionsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val TRANSACTIONS = "transactions"
    const val REVIEW = "review"
    const val INSIGHTS = "insights"
    const val ACCOUNTS = "accounts"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val DETAIL = "transaction/{id}"

    fun detail(id: String) = "transaction/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.DASHBOARD, "Home", Icons.Filled.Home),
    Tab(Routes.TRANSACTIONS, "Activity", Icons.Filled.Receipt),
    Tab(Routes.REVIEW, "Review", Icons.Filled.RuleFolder),
    Tab(Routes.INSIGHTS, "Insights", Icons.Filled.Insights),
    Tab(Routes.ACCOUNTS, "Accounts", Icons.Filled.AccountBalance)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KharchaNavHost(reviewCount: Int) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kharcha") },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.SEARCH) }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (tab.route == Routes.REVIEW && reviewCount > 0) {
                                BadgedBox(badge = { Badge { Text(reviewCount.toString()) } }) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenTransaction = { navController.navigate(Routes.detail(it)) },
                    onOpenReview = { navController.navigate(Routes.REVIEW) },
                    onOpenAllTransactions = { navController.navigate(Routes.TRANSACTIONS) }
                )
            }
            composable(Routes.TRANSACTIONS) {
                TransactionsScreen(onOpenTransaction = { navController.navigate(Routes.detail(it)) })
            }
            composable(Routes.REVIEW) { ReviewScreen() }
            composable(Routes.INSIGHTS) { InsightsScreen() }
            composable(Routes.ACCOUNTS) { AccountsScreen() }
            composable(Routes.SEARCH) {
                SearchScreen(onOpenTransaction = { navController.navigate(Routes.detail(it)) })
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.DETAIL) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                TransactionDetailScreen(transactionId = id)
            }
        }
    }
}
