package com.kharcha.ledger.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.query.QueryParser
import com.kharcha.ledger.ui.components.TransactionRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    val interpretation: String = "",
    val results: List<CanonicalTransaction> = emptyList(),
    val total: Money = Money.ZERO
)

/**
 * Offline natural-ish search. "food above 500 last month" is parsed locally into
 * a filter — no model, no network, no query leaving the phone.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    repository: LedgerRepository
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<SearchState> = combine(
        repository.observeTransactions(limit = 3_000),
        query
    ) { transactions, text ->
        val parsed = QueryParser.parse(text, Instant.now())
        val results = transactions.filter { parsed.matches(it) }
        SearchState(
            query = text,
            interpretation = describe(parsed),
            results = results,
            total = results.filter { it.isSpending }.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    private fun describe(query: com.kharcha.ledger.engine.query.TransactionQuery): String {
        val parts = mutableListOf<String>()
        if (query.categories.isNotEmpty()) parts += query.categories.joinToString("/")
        if (query.merchants.isNotEmpty()) parts += query.merchants.joinToString("/")
        if (query.methods.isNotEmpty()) parts += query.methods.joinToString("/") { it.name.lowercase() }
        query.minAmount?.let { parts += "above ${it.format(withPaise = false)}" }
        query.maxAmount?.let { parts += "below ${it.format(withPaise = false)}" }
        query.range?.let { parts += it.label }
        query.accountFragment?.let { parts += "on ${it.uppercase()}" }
        return parts.joinToString(" · ")
    }
}

private val SUGGESTIONS = listOf(
    "swiggy last month",
    "food above 500",
    "upi payments this week",
    "credit card spending",
    "payments above 10000",
    "fuel this month"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onOpenTransaction: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text("Search your money") },
            placeholder = { Text("food above 500 last month") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )

        if (state.query.isBlank()) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SUGGESTIONS.forEach { suggestion ->
                    AssistChip(
                        onClick = { viewModel.onQueryChange(suggestion) },
                        label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        } else {
            Text(
                buildString {
                    append("${state.results.size} results")
                    if (state.total.isPositive()) append(" · ${state.total.format(withPaise = false)}")
                    if (state.interpretation.isNotBlank()) append(" · ${state.interpretation}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        LazyColumn {
            items(state.results, key = { it.id }) { transaction ->
                TransactionRow(transaction, onClick = { onOpenTransaction(transaction.id) })
                HorizontalDivider()
            }
        }
    }
}
