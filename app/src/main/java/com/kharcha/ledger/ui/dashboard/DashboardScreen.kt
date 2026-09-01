package com.kharcha.ledger.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.ui.components.CategoryBar
import com.kharcha.ledger.ui.components.EmptyState
import com.kharcha.ledger.ui.components.SectionCard
import com.kharcha.ledger.ui.components.StatTile
import com.kharcha.ledger.ui.components.TransactionRow
import com.kharcha.ledger.ui.components.relativeAge
import com.kharcha.ledger.ui.theme.IncomeGreen
import com.kharcha.ledger.ui.theme.SpendRed
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun DashboardScreen(
    onOpenTransaction: (String) -> Unit,
    onOpenReview: () -> Unit,
    onOpenAllTransactions: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = state.summary

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "${state.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${state.month.year}",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        if (state.openReviews > 0) {
            item {
                SectionCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Needs review · ${state.openReviews}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Duplicates, transfers and merchants we could not decide alone",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onOpenReview) { Text("Open") }
                    }
                }
            }
        }

        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile("Money in", (summary?.income ?: Money.ZERO).format(withPaise = false), IncomeGreen)
                    StatTile("Spent", (summary?.spending ?: Money.ZERO).format(withPaise = false), SpendRed)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile("Invested", (summary?.investments ?: Money.ZERO).format(withPaise = false))
                    StatTile("Remaining", state.spendable.format(withPaise = false))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Transfers between your own accounts, credit-card bill payments and " +
                        "failed payments are excluded from spending.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.accounts.isNotEmpty()) {
            item {
                SectionCard(title = "Accounts") {
                    state.accounts.forEach { account ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(account.displayName, style = MaterialTheme.typography.bodyMedium)
                                account.balanceAt?.let {
                                    Text(
                                        "Last known · ${Instant.ofEpochMilli(it).relativeAge()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    account.balancePaise?.let { Money(it).format(withPaise = false) } ?: "—",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (account.accountType == AccountType.CREDIT_CARD.name) {
                                    account.dueDate?.let {
                                        Text(
                                            "Due $it",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (summary != null && summary.byCategory.isNotEmpty()) {
            item {
                SectionCard(title = "Top spending") {
                    summary.byCategory.take(6).forEach {
                        CategoryBar(it.category, it.amount, it.share)
                    }
                }
            }
        }

        state.upi?.let { upi ->
            if (upi.transactionCount > 0) {
                item {
                    SectionCard(title = "UPI this month") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatTile("Payments", upi.transactionCount.toString())
                            StatTile("Spent", upi.total.format(withPaise = false))
                            StatTile("Average", upi.average.format(withPaise = false))
                        }
                        upi.topMerchant?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Most paid: ${it.merchant} · ${it.count} times",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        state.smallSpends?.let { small ->
            if (small.count > 0) {
                item {
                    SectionCard(title = "Small payments") {
                        Text(
                            "${small.count} payments under ${small.threshold.format(withPaise = false)} " +
                                "added up to ${small.total.format(withPaise = false)} — " +
                                "${(small.shareOfSpending * 100).toInt()}% of this month's spending.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        state.commitments?.let { commitments ->
            if (commitments.patterns.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "Monthly commitments",
                        subtitle = "${(commitments.shareOfIncome * 100).toInt()}% of your income is already committed"
                    ) {
                        commitments.patterns.take(6).forEach { pattern ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(pattern.merchant, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    pattern.expectedAmount.format(withPaise = false),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onOpenAllTransactions) { Text("See all") }
            }
        }

        if (state.recent.isEmpty() && !state.loading) {
            item {
                EmptyState(
                    "Nothing yet",
                    "When your bank sends a transaction SMS, it will appear here automatically."
                )
            }
        }

        items(state.recent, key = { it.id }) { transaction ->
            TransactionRow(transaction, onClick = { onOpenTransaction(transaction.id) })
            HorizontalDivider()
        }
    }
}
