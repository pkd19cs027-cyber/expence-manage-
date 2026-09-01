package com.kharcha.ledger.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kharcha.ledger.ui.components.EmptyState
import com.kharcha.ledger.ui.components.TransactionRow
import java.time.format.DateTimeFormatter

private val headerFormat = DateTimeFormatter.ofPattern("EEEE, d MMM")

@Composable
fun TransactionsScreen(
    onOpenTransaction: (String) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.includeNonSpending,
                    onClick = viewModel::toggleNonSpending,
                    label = { Text("Include transfers & bills") }
                )
            }
        }

        if (state.sections.isEmpty() && !state.loading) {
            item {
                EmptyState(
                    "No activity yet",
                    "Kharcha reads transaction SMS as they arrive. Nothing here means nothing has come in."
                )
            }
        }

        state.sections.forEach { section ->
            item(key = "header-${section.date}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(section.date.format(headerFormat), style = MaterialTheme.typography.labelSmall)
                    if (section.spent.isPositive()) {
                        Text(
                            section.spent.format(withPaise = false),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(section.transactions, key = { it.id }) { transaction ->
                TransactionRow(transaction, onClick = { onOpenTransaction(transaction.id) })
                HorizontalDivider()
            }
        }
    }
}
