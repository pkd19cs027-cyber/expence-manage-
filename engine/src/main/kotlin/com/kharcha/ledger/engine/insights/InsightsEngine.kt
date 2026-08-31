package com.kharcha.ledger.engine.insights

import com.kharcha.ledger.engine.merchant.Categories
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.PaymentMethod
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.reconcile.SelfAccountRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every number the dashboard shows, computed from the ledger on the phone.
 *
 * All of it is derived, never stored: change a category or merge a duplicate and
 * the reports move with it.
 */
class InsightsEngine(private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {

    fun monthlySummary(transactions: List<CanonicalTransaction>, month: YearMonth): MonthlySummary {
        val inMonth = transactions.filter { YearMonth.from(it.timestamp.atZone(zone)) == month }

        val spending = inMonth.filter { it.isSpending }
        val spendTotal = spending.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }
        val income = inMonth.filter { it.isIncome }.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }
        val investments = inMonth.filter { it.isInvestment }.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }

        val byCategory = spending
            .groupBy { it.category ?: Categories.OTHER }
            .map { (category, rows) ->
                val amount = rows.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }
                CategoryTotal(category, amount, rows.size, share(amount, spendTotal))
            }
            .sortedByDescending { it.amount.paise }

        val topMerchants = spending
            .filter { it.merchant != null }
            .groupBy { it.merchant!! }
            .map { (merchant, rows) ->
                MerchantTotal(merchant, rows.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }, rows.size)
            }
            .sortedByDescending { it.amount.paise }
            .take(10)

        return MonthlySummary(
            month = month,
            income = income,
            spending = spendTotal,
            investments = investments,
            remaining = income - spendTotal - investments,
            transactionCount = inMonth.count { it.status.hasFinancialImpact },
            byCategory = byCategory,
            topMerchants = topMerchants
        )
    }

    /** UPI is how India pays, so it deserves its own view rather than a slice of a pie. */
    fun upiInsights(transactions: List<CanonicalTransaction>, month: YearMonth): UpiInsights {
        val upi = transactions.filter {
            it.isSpending && it.method == PaymentMethod.UPI &&
                YearMonth.from(it.timestamp.atZone(zone)) == month
        }
        val total = upi.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }
        val top = upi.filter { it.merchant != null }
            .groupBy { it.merchant!! }
            .map { (merchant, rows) ->
                MerchantTotal(merchant, rows.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }, rows.size)
            }
            .maxByOrNull { it.count }
        val topAccount = upi.mapNotNull { it.account }
            .groupBy { it.key }
            .maxByOrNull { it.value.size }
            ?.value?.first()
            ?.let { "${it.institutionId} •${it.last4}" }

        return UpiInsights(
            transactionCount = upi.size,
            total = total,
            average = if (upi.isEmpty()) Money.ZERO else Money(total.paise / upi.size),
            topMerchant = top,
            topAccount = topAccount
        )
    }

    fun smallSpends(
        transactions: List<CanonicalTransaction>,
        month: YearMonth,
        threshold: Money = Money.ofRupees(200L)
    ): SmallSpendInsight {
        val spending = transactions.filter {
            it.isSpending && YearMonth.from(it.timestamp.atZone(zone)) == month
        }
        val small = spending.filter { it.netImpact <= threshold }
        val total = small.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }
        val allTotal = spending.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }
        return SmallSpendInsight(threshold, small.size, total, share(total, allTotal))
    }

    /** The salary-flow view: one credit in, everything it paid for out. */
    fun salaryFlow(transactions: List<CanonicalTransaction>, month: YearMonth): SalaryFlow {
        val summary = monthlySummary(transactions, month)
        val salary = transactions
            .filter {
                it.kind == TransactionKind.SALARY &&
                    YearMonth.from(it.timestamp.atZone(zone)) == month
            }
            .fold(Money.ZERO) { acc, tx -> acc + tx.amount }
        val base = if (salary.isPositive()) salary else summary.income
        return SalaryFlow(
            salary = base,
            destinations = summary.byCategory,
            investments = summary.investments,
            remaining = base - summary.spending - summary.investments
        )
    }

    /**
     * Finds payments that repeat: rent, SIPs, EMIs, subscriptions. Two
     * occurrences are a coincidence, three are a pattern, so confidence rises
     * with the count and falls with how much the interval wobbles.
     */
    fun recurring(transactions: List<CanonicalTransaction>, now: Instant): List<RecurringPattern> {
        return transactions
            .filter { it.isSpending || it.kind == TransactionKind.INVESTMENT || it.kind == TransactionKind.EMI }
            .filter { it.merchant != null && it.status.hasFinancialImpact }
            .groupBy { it.merchant!! }
            .mapNotNull { (merchant, rows) ->
                if (rows.size < 2) return@mapNotNull null
                val sorted = rows.sortedBy { it.timestamp }
                val gaps = sorted.zipWithNext { a, b ->
                    ((b.timestamp.epochSecond - a.timestamp.epochSecond) / 86_400.0)
                }
                val averageGap = gaps.average()
                if (averageGap < 20 || averageGap > 400) return@mapNotNull null

                val amounts = sorted.map { it.netImpact.paise }
                val averageAmount = amounts.average()
                val amountDrift = amounts.maxOf { abs(it - averageAmount) } / averageAmount.coerceAtLeast(1.0)
                val gapDrift = if (gaps.size == 1) 0.15 else gaps.maxOf { abs(it - averageGap) } / averageGap

                if (amountDrift > 0.35 || gapDrift > 0.4) return@mapNotNull null

                val last = sorted.last()
                val frequency = averageGap.roundToInt()
                RecurringPattern(
                    merchant = merchant,
                    expectedAmount = Money(averageAmount.toLong()),
                    frequencyDays = frequency,
                    occurrences = sorted.size,
                    lastSeen = last.timestamp,
                    nextExpected = last.timestamp.atZone(zone).toLocalDate().plusDays(frequency.toLong()),
                    category = last.category,
                    confidence = (0.5 + 0.15 * (sorted.size - 2) - amountDrift - gapDrift).coerceIn(0.3, 0.97)
                )
            }
            .filter { it.lastSeen.isAfter(now.minusSeconds(120L * 86_400L)) }
            .sortedByDescending { it.expectedAmount.paise }
    }

    /**
     * What is already spoken for before the month starts. "48% of your income is
     * committed" is a more useful sentence than any category breakdown.
     */
    fun commitments(
        transactions: List<CanonicalTransaction>,
        now: Instant,
        monthlyIncome: Money
    ): Commitments {
        val monthly = recurring(transactions, now).filter { it.frequencyDays in 25..35 }
        val total = monthly.fold(Money.ZERO) { acc, pattern -> acc + pattern.expectedAmount }
        return Commitments(total, monthly, share(total, monthlyIncome))
    }

    /**
     * Balances are always reported as "last known": the phone only learns a
     * balance when a message happens to carry one, and money can move without
     * an SMS.
     */
    fun netWorth(
        transactions: List<CanonicalTransaction>,
        registry: SelfAccountRegistry,
        names: Map<String, String> = emptyMap()
    ): NetWorth {
        val latestByAccount = transactions
            .filter { it.balanceAfter != null && it.account != null && registry.isSelf(it.account) }
            .groupBy { it.account!!.key }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.timestamp }!! }

        val accounts = latestByAccount.map { (key, tx) ->
            val account = tx.account!!
            AccountBalance(
                account = account,
                displayName = names[key] ?: SelfAccountRegistry.suggestName(account),
                balance = tx.balanceAfter,
                asOf = tx.timestamp,
                isCredit = account.type == AccountType.CREDIT_CARD
            )
        }

        val assets = accounts.filterNot { it.isCredit }
            .fold(Money.ZERO) { acc, row -> acc + (row.balance ?: Money.ZERO) }
        val liabilities = accounts.filter { it.isCredit }
            .fold(Money.ZERO) { acc, row -> acc + (row.balance ?: Money.ZERO) }

        return NetWorth(assets, liabilities, assets - liabilities, accounts.sortedBy { it.displayName })
    }

    private fun share(part: Money, whole: Money): Double =
        if (whole.paise <= 0) 0.0 else part.paise.toDouble() / whole.paise.toDouble()
}
