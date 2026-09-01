package com.kharcha.ledger.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kharcha.ledger.data.repository.LedgerRepository
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.LinkRelation
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.TransactionLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DaySection(val date: LocalDate, val spent: Money, val transactions: List<CanonicalTransaction>)

data class TransactionsState(
    val sections: List<DaySection> = emptyList(),
    val includeNonSpending: Boolean = true,
    val loading: Boolean = true
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: LedgerRepository
) : ViewModel() {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val includeNonSpending = MutableStateFlow(true)

    val state: StateFlow<TransactionsState> = combine(
        repository.observeTransactions(limit = 1_000),
        includeNonSpending
    ) { transactions, includeAll ->
        val visible = if (includeAll) transactions else transactions.filter { it.isSpending }
        TransactionsState(
            sections = visible
                .groupBy { it.timestamp.atZone(zone).toLocalDate() }
                .map { (date, rows) ->
                    DaySection(
                        date = date,
                        spent = rows.filter { it.isSpending }.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact },
                        transactions = rows.sortedByDescending { it.timestamp }
                    )
                }
                .sortedByDescending { it.date },
            includeNonSpending = includeAll,
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsState())

    fun toggleNonSpending() {
        includeNonSpending.value = !includeNonSpending.value
    }
}

data class TransactionDetailState(
    val transaction: CanonicalTransaction? = null,
    val links: List<TransactionLink> = emptyList(),
    val related: Map<String, CanonicalTransaction> = emptyMap()
)

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val repository: LedgerRepository
) : ViewModel() {

    private val transactionId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TransactionDetailState> = transactionId
        .filterNotNull()
        .flatMapLatest { id ->
            combine(
                repository.observeTransaction(id),
                repository.observeLinks(id),
                repository.observeTransactions(limit = 2_000)
            ) { transaction, links, all ->
                TransactionDetailState(
                    transaction = transaction,
                    links = links,
                    related = all
                        .filter { candidate ->
                            candidate.id != id &&
                                links.any { it.fromTransactionId == candidate.id || it.toTransactionId == candidate.id }
                        }
                        .associateBy { it.id }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionDetailState())

    fun load(id: String) {
        transactionId.value = id
    }

    fun setCategory(category: String) {
        val id = transactionId.value ?: return
        viewModelScope.launch { repository.setCategory(id, category) }
    }

    fun relationLabel(link: TransactionLink, currentId: String): String = when (link.relation) {
        LinkRelation.DUPLICATE_OF -> "Possible duplicate"
        LinkRelation.REVERSAL_OF -> if (link.fromTransactionId == currentId) "Reverses" else "Reversed by"
        LinkRelation.REFUND_OF -> if (link.fromTransactionId == currentId) "Refund for" else "Refunded by"
        LinkRelation.TRANSFER_PAIR -> "Transfer pair"
        LinkRelation.CARD_PAYMENT_FOR -> "Card bill payment"
    }
}
