package com.kharcha.ledger.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kharcha.ledger.data.repository.LedgerRepository
import com.kharcha.ledger.engine.insights.InsightsEngine
import com.kharcha.ledger.engine.insights.NetWorth
import com.kharcha.ledger.engine.insights.RecurringPattern
import com.kharcha.ledger.engine.insights.SalaryFlow
import com.kharcha.ledger.engine.reconcile.SelfAccountRegistry
import com.kharcha.ledger.ui.components.CategoryBar
import com.kharcha.ledger.ui.components.SectionCard
import com.kharcha.ledger.ui.components.StatTile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class InsightsState(
    val salaryFlow: SalaryFlow? = null,
    val recurring: List<RecurringPattern> = emptyList(),
    val netWorth: NetWorth? = null,
    val messagesRead: Int = 0,
    val messagesIgnored: Int = 0
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    repository: LedgerRepository,
    private val insights: InsightsEngine
) : ViewModel() {

    private val zone = ZoneId.of("Asia/Kolkata")

    val state: StateFlow<InsightsState> = combine(
        repository.observeTransactions(limit = 3_000),
        repository.observeAccounts(),
        repository.observeMessageStats()
    ) { transactions, accounts, stats ->
        val registry = SelfAccountRegistry(
            accounts.filter { it.verifiedByUser }.map { it.accountKey }.toSet()
        )
        InsightsState(
            salaryFlow = insights.salaryFlow(transactions, YearMonth.now(zone)),
            recurring = insights.recurring(transactions, Instant.now()),
            netWorth = insights.netWorth(
                transactions,
                registry,
                accounts.associate { it.accountKey to it.displayName }
            ),
            messagesRead = stats.first,
            messagesIgnored = stats.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())
}

@Composable
fun InsightsScreen(viewModel: InsightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Insights", style = MaterialTheme.typography.headlineSmall) }

        state.salaryFlow?.let { flow ->
            item {
                SectionCard(
                    title = "Where did your money go?",
                    subtitle = "Starting from ${flow.salary.format(withPaise = false)} in"
                ) {
                    flow.destinations.take(8).forEach {
                        CategoryBar(it.category, it.amount, it.share)
                    }
                    if (flow.investments.isPositive()) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Invested", style = MaterialTheme.typography.bodyMedium)
                            Text(flow.investments.format(withPaise = false), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Remaining", style = MaterialTheme.typography.titleMedium)
                        Text(flow.remaining.format(withPaise = false), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        state.netWorth?.let { worth ->
            item {
                SectionCard(title = "Net position", subtitle = "From the last balance each bank told you") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile("Assets", worth.assets.format(withPaise = false))
                        StatTile("Card dues", worth.liabilities.format(withPaise = false))
                        StatTile("Net", worth.net.format(withPaise = false))
                    }
                }
            }
        }

        if (state.recurring.isNotEmpty()) {
            item {
                SectionCard(title = "Repeating payments") {
                    state.recurring.forEach { pattern ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(pattern.merchant, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "every ~${pattern.frequencyDays} days · next ${pattern.nextExpected}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                pattern.expectedAmount.format(withPaise = false),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        item {
            SectionCard(title = "What Kharcha read") {
                Text(
                    "${state.messagesRead} messages processed on this device. " +
                        "${state.messagesIgnored} were OTPs, marketing, balance alerts or statements " +
                        "and were deliberately not counted as money moving.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
