package com.kharcha.ledger.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kharcha.ledger.data.db.entity.AccountEntity
import com.kharcha.ledger.data.repository.LedgerRepository
import com.kharcha.ledger.engine.insights.Commitments
import com.kharcha.ledger.engine.insights.InsightsEngine
import com.kharcha.ledger.engine.insights.MonthlySummary
import com.kharcha.ledger.engine.insights.SmallSpendInsight
import com.kharcha.ledger.engine.insights.UpiInsights
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class DashboardState(
    val month: YearMonth = YearMonth.now(),
    val summary: MonthlySummary? = null,
    val upi: UpiInsights? = null,
    val smallSpends: SmallSpendInsight? = null,
    val commitments: Commitments? = null,
    val accounts: List<AccountEntity> = emptyList(),
    val recent: List<CanonicalTransaction> = emptyList(),
    val openReviews: Int = 0,
    val loading: Boolean = true
) {
    val spendable: Money
        get() = summary?.let { it.income - it.spending - it.investments } ?: Money.ZERO
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val insights: InsightsEngine
) : ViewModel() {

    private val zone = ZoneId.of("Asia/Kolkata")

    val state: StateFlow<DashboardState> = combine(
        repository.observeTransactions(limit = 2_000),
        repository.observeAccounts(),
        repository.observeOpenReviewCount()
    ) { transactions, accounts, reviews ->
        val month = YearMonth.now(zone)
        val summary = insights.monthlySummary(transactions, month)
        DashboardState(
            month = month,
            summary = summary,
            upi = insights.upiInsights(transactions, month),
            smallSpends = insights.smallSpends(transactions, month),
            commitments = insights.commitments(transactions, Instant.now(), summary.income),
            accounts = accounts.filter { it.verifiedByUser && !it.hiddenByUser },
            recent = transactions.take(8),
            openReviews = reviews,
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())
}
