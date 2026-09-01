package com.kharcha.ledger.engine.insights

import com.kharcha.ledger.engine.LedgerPipeline
import com.kharcha.ledger.engine.T
import com.kharcha.ledger.engine.merchant.Categories
import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.RawMessage
import com.kharcha.ledger.engine.reconcile.SelfAccountRegistry
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InsightsTest {

    private val hdfc = AccountRef("HDFC", "4821", AccountType.SAVINGS)
    private val sbi = AccountRef("SBI", "8304", AccountType.SAVINGS)
    private val registry = SelfAccountRegistry(setOf(hdfc.key, sbi.key))
    private val insights = InsightsEngine()
    private val august = YearMonth.of(2026, 8)

    /** A plausible fortnight: salary in, a sweep out, food, fuel, a SIP and a failed payment. */
    private fun month(): List<RawMessage> = listOf(
        T.sms("AD-HDFCBK", "Rs.72,000.00 credited to A/c XXXX4821 on 01-08-26 towards SALARY JUL 2026. Avl Bal Rs.85,000.00", T.at(1, 6, 30), "m1"),
        T.sms("VM-HDFCBK", "Rs.15,000.00 debited from A/c XX4821 towards UPI-NOBROKER RENT on 02-Aug-26. UPI Ref 425600000001", T.at(2, 10, 0), "m2"),
        T.sms("VM-HDFCBK", "Rs.540.00 debited from A/c XX4821 towards UPI-SWIGGY on 03-Aug-26. UPI Ref 425600000002", T.at(3, 20, 43), "m3"),
        T.sms("VM-GOOGLE", "You paid Rs.540.00 to Swiggy using Google Pay. UPI Ref 425600000002", T.at(3, 20, 43), "m4"),
        T.sms("VM-HDFCBK", "Rs.180.00 debited from A/c XX4821 towards UPI-THIRD WAVE COFFEE on 04-Aug-26. UPI Ref 425600000003", T.at(4, 9, 15), "m5"),
        T.sms("VM-HDFCBK", "Rs.120.00 debited from A/c XX4821 towards UPI-NAMMA YATRI on 04-Aug-26. UPI Ref 425600000004", T.at(4, 9, 40), "m6"),
        T.sms("VM-HDFCBK", "Rs.2,400.00 debited from A/c XX4821 towards UPI-INDIAN OIL on 05-Aug-26. UPI Ref 425600000005", T.at(5, 18, 0), "m7"),
        T.sms("AD-HDFCBK", "Rs.10,000.00 debited from A/c XX4821 towards SIP of AXIS MUTUAL FUND on 05-08-26.", T.at(5, 8, 0), "m8"),
        T.sms("VM-HDFCBK", "Your transaction of INR 3,000.00 to AMAZON could not be processed. UPI Ref 425600000099", T.at(6, 11, 0), "m9"),
        T.sms("VM-HDFCBK", "Rs.20,000.00 debited from A/c XX4821 via UPI to SBI A/c on 07-Aug-26. UPI Ref 425600000006", T.at(7, 15, 0), "m10"),
        T.sms("AD-SBIINB", "Rs.20,000.00 credited to A/c XXXXX8304 via UPI on 07-08-26. UPI Ref 425600000006. Avl Bal Rs.82,400.00", T.at(7, 15, 1), "m11")
    )

    private fun ledger() = LedgerPipeline(registry = registry).process(month()).transactions

    @Test
    fun `the monthly summary separates spending, investment and transfers`() {
        val summary = insights.monthlySummary(ledger(), august)

        assertEquals(Money.ofRupees(72_000L), summary.income, "the account sweep is not income")
        assertEquals(Money.ofRupees(18_240L), summary.spending, "rent, food, coffee, ride and fuel only")
        assertEquals(Money.ofRupees(10_000L), summary.investments, "a SIP is not consumption")
        assertEquals(Money.ofRupees(43_760L), summary.remaining)
    }

    @Test
    fun `the failed payment never reaches the report`() {
        val summary = insights.monthlySummary(ledger(), august)
        assertTrue(summary.byCategory.none { it.amount == Money.ofRupees(3_000L) })
    }

    @Test
    fun `categories are ranked by what was actually spent`() {
        val summary = insights.monthlySummary(ledger(), august)
        assertEquals(Categories.RENT, summary.byCategory.first().category)
        assertEquals(Money.ofRupees(15_000L), summary.byCategory.first().amount)
    }

    @Test
    fun `UPI insights count the merged payment once`() {
        val upi = insights.upiInsights(ledger(), august)
        assertEquals(5, upi.transactionCount, "rent, swiggy, coffee, ride, fuel")
        assertEquals(Money.ofRupees(18_240L), upi.total)
    }

    @Test
    fun `small spends are surfaced as their own number`() {
        val small = insights.smallSpends(ledger(), august)
        assertEquals(2, small.count, "the coffee and the auto ride")
        assertEquals(Money.ofRupees(300L), small.total)
    }

    @Test
    fun `salary flow ends at what is actually left`() {
        val flow = insights.salaryFlow(ledger(), august)
        assertEquals(Money.ofRupees(72_000L), flow.salary)
        assertEquals(Money.ofRupees(43_760L), flow.remaining)
    }

    @Test
    fun `last known balances build a net worth without pretending to be live`() {
        val worth = insights.netWorth(ledger(), registry)
        assertTrue(worth.accounts.any { it.account.last4 == "8304" })
        assertTrue(worth.accounts.all { it.asOf != null }, "every balance is stamped with when it was learnt")
    }

    @Test
    fun `a monthly subscription is detected as a commitment`() {
        val messages = (0..3).map { index ->
            val day = 5
            T.sms(
                "VM-HDFCBK",
                "Rs.649.00 debited from A/c XX4821 towards UPI-NETFLIX. UPI Ref 42560000100$index",
                T.at(day, 9, 0, month = 5 + index),
                "sub$index"
            )
        }
        val ledger = LedgerPipeline(registry = registry).process(messages).transactions
        val patterns = insights.recurring(ledger, T.at(31, 12, 0))

        val netflix = patterns.firstOrNull { it.merchant == "Netflix" }
        assertTrue(netflix != null, "four monthly debits is a pattern")
        assertEquals(Money.ofRupees(649L), netflix.expectedAmount)
        assertTrue(netflix.frequencyDays in 28..32)
    }
}
