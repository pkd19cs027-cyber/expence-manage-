package com.kharcha.ledger.engine.insights

import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class CategoryTotal(val category: String, val amount: Money, val count: Int, val share: Double)

data class MerchantTotal(val merchant: String, val amount: Money, val count: Int)

/**
 * The headline numbers. Investments are separated from spending on purpose:
 * ₹10,000 moved into a SIP has not disappeared, and lumping it in with food and
 * fuel makes the month look worse than it was.
 */
data class MonthlySummary(
    val month: YearMonth,
    val income: Money,
    val spending: Money,
    val investments: Money,
    val remaining: Money,
    val transactionCount: Int,
    val byCategory: List<CategoryTotal>,
    val topMerchants: List<MerchantTotal>
)

data class UpiInsights(
    val transactionCount: Int,
    val total: Money,
    val average: Money,
    val topMerchant: MerchantTotal?,
    val topAccount: String?
)

/**
 * UPI turns spending into a stream of ₹40 and ₹180 payments that never feel
 * like anything. Totalling them is more useful than another pie chart.
 */
data class SmallSpendInsight(
    val threshold: Money,
    val count: Int,
    val total: Money,
    val shareOfSpending: Double
)

/** "Where did my salary go?" — the flow from one credit to everything it funded. */
data class SalaryFlow(
    val salary: Money,
    val destinations: List<CategoryTotal>,
    val investments: Money,
    val remaining: Money
)

data class RecurringPattern(
    val merchant: String,
    val expectedAmount: Money,
    val frequencyDays: Int,
    val occurrences: Int,
    val lastSeen: Instant,
    val nextExpected: LocalDate,
    val category: String?,
    val confidence: Double
)

data class Commitments(
    val monthlyTotal: Money,
    val patterns: List<RecurringPattern>,
    val shareOfIncome: Double
)

data class AccountBalance(
    val account: AccountRef,
    val displayName: String,
    val balance: Money?,
    val asOf: Instant?,
    val isCredit: Boolean
)

data class NetWorth(
    val assets: Money,
    val liabilities: Money,
    val net: Money,
    val accounts: List<AccountBalance>
)
