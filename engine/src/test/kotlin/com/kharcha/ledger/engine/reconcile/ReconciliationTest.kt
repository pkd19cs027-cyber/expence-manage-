package com.kharcha.ledger.engine.reconcile

import com.kharcha.ledger.engine.LedgerPipeline
import com.kharcha.ledger.engine.T
import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.RawMessage
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.model.TransactionStatus
import com.kharcha.ledger.engine.review.ReviewReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReconciliationTest {

    private val hdfc = AccountRef("HDFC", "4821", AccountType.SAVINGS)
    private val sbi = AccountRef("SBI", "8304", AccountType.SAVINGS)
    private val iciciCard = AccountRef("ICICI", "1923", AccountType.CREDIT_CARD)

    private fun pipeline(vararg self: AccountRef) = LedgerPipeline(
        registry = SelfAccountRegistry(self.map { it.key }.toSet())
    )

    private fun spendTotal(transactions: List<CanonicalTransaction>): Money =
        transactions.filter { it.isSpending }.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }

    private fun incomeTotal(transactions: List<CanonicalTransaction>): Money =
        transactions.filter { it.isIncome }.fold(Money.ZERO) { acc, tx -> acc + tx.netImpact }

    @Test
    fun `one Swiggy payment reported by three apps is one 540 rupee expense`() {
        val messages = listOf(
            T.sms("VM-HDFCBK", "Rs.540.00 debited from A/c XX4821 towards UPI-SWIGGY on 31-Aug-26. UPI Ref 425678901234. Avl Bal Rs.24,850.00", T.at(31, 20, 43), "s1"),
            T.sms("VM-GOOGLE", "You paid Rs.540.00 to Swiggy using Google Pay. UPI Ref 425678901234", T.at(31, 20, 43), "s2"),
            T.sms("AD-SWIGGY", "Payment of Rs.540.00 to Swiggy successful. UPI Ref 425678901234", T.at(31, 20, 44), "s3")
        )
        val result = pipeline(hdfc).process(messages)
        val transactions = result.transactions

        assertEquals(1, transactions.size, "three messages, one payment")
        val tx = transactions.single()
        assertEquals(Money.ofRupees(540L), tx.amount)
        assertEquals("Swiggy", tx.merchant)
        assertEquals("4821", tx.account?.last4, "the bank's account survives the merge")
        assertEquals(3, tx.sourceCount, "all three messages stay attached as evidence")
        assertEquals(Money.ofRupees(540L), spendTotal(transactions))
    }

    @Test
    fun `two coffees of the same price on the same day are two purchases`() {
        val messages = listOf(
            T.sms("VM-HDFCBK", "Rs.100.00 debited from A/c XX4821 towards UPI-THIRD WAVE COFFEE on 31-Aug-26.", T.at(31, 10, 3), "c1"),
            T.sms("VM-HDFCBK", "Rs.100.00 debited from A/c XX4821 towards UPI-THIRD WAVE COFFEE on 31-Aug-26.", T.at(31, 12, 30), "c2")
        )
        val result = pipeline(hdfc).process(messages)
        assertEquals(2, result.transactions.size, "same amount and merchant, hours apart, is not a duplicate")
        assertEquals(Money.ofRupees(200L), spendTotal(result.transactions))
    }

    @Test
    fun `different UPI references are never merged`() {
        val messages = listOf(
            T.sms("VM-HDFCBK", "Rs.250.00 debited from A/c XX4821 towards UPI-ZOMATO on 31-Aug-26. UPI Ref 425678900001", T.at(31, 20, 43), "z1"),
            T.sms("VM-HDFCBK", "Rs.250.00 debited from A/c XX4821 towards UPI-ZOMATO on 31-Aug-26. UPI Ref 425678900002", T.at(31, 20, 44), "z2")
        )
        val result = pipeline(hdfc).process(messages)
        assertEquals(2, result.transactions.size)
    }

    @Test
    fun `a sweep between the user's own accounts is a transfer, not income plus spending`() {
        val messages = listOf(
            T.sms("VM-HDFCBK", "Rs.10,000.00 debited from A/c XX4821 via UPI to SBI A/c on 31-Aug-26. UPI Ref 425611112222", T.at(31, 15, 0), "t1"),
            T.sms("AD-SBIINB", "Rs.10,000.00 credited to A/c XXXXX8304 via UPI on 31-08-26. UPI Ref 425611112222. Avl Bal Rs.72,400.00", T.at(31, 15, 1), "t2")
        )
        val result = pipeline(hdfc, sbi).process(messages)

        assertEquals(2, result.transactions.size, "both legs are kept")
        assertTrue(result.transactions.all { it.kind == TransactionKind.TRANSFER })
        assertEquals(Money.ZERO, spendTotal(result.transactions))
        assertEquals(Money.ZERO, incomeTotal(result.transactions))
        assertTrue(result.reconciliation.links.any { it.relation.name == "TRANSFER_PAIR" })
    }

    @Test
    fun `a credit card bill payment is a liability payment, not new spending`() {
        val messages = listOf(
            T.sms("AD-ICICIB", "Rs.4,500.00 spent on ICICI Bank Credit Card XX1923 at AMAZON on 03-09-26. Avl Limit Rs.95,500.00", T.at(3, 19, 0, month = 9), "cc1"),
            T.sms("VM-HDFCBK", "Rs.20,000.00 debited from A/c XX4821 towards ICICI Credit Card XX1923 on 05-09-26. UPI Ref 425600001111", T.at(5, 11, 0, month = 9), "cc2"),
            T.sms("AD-ICICIB", "Payment of Rs.20,000.00 received towards your ICICI Bank Credit Card XX1923 on 05-Sep-26. Thank you.", T.at(5, 11, 5, month = 9), "cc3")
        )
        val result = pipeline(hdfc, iciciCard).process(messages)

        assertEquals(Money.ofRupees(4_500L), spendTotal(result.transactions), "only the Amazon purchase is spending")
        assertTrue(
            result.reconciliation.links.any { it.relation.name == "CARD_PAYMENT_FOR" },
            "the bank debit and the card credit are linked"
        )
        assertEquals(Money.ZERO, incomeTotal(result.transactions), "a card payment is never income")
    }

    @Test
    fun `a reversed payment nets to zero`() {
        val messages = listOf(
            T.sms("VM-HDFCBK", "Rs.2,000.00 debited from A/c XX4821 towards UPI-BIGBASKET on 31-Aug-26. UPI Ref 425622223333", T.at(31, 12, 0), "r1"),
            T.sms("VM-HDFCBK", "Rs.2,000.00 has been reversed and credited back to your A/c XX4821 towards failed UPI txn. UPI Ref 425622223333", T.at(31, 12, 40), "r2")
        )
        val result = pipeline(hdfc).process(messages)

        val original = result.transactions.first { it.id != result.transactions.maxByOrNull { t -> t.timestamp }!!.id || it.status == TransactionStatus.REVERSED }
        assertEquals(TransactionStatus.REVERSED, original.status)
        assertEquals(Money.ZERO, spendTotal(result.transactions))
        assertEquals(Money.ZERO, incomeTotal(result.transactions), "the reversal is not income")
    }

    @Test
    fun `a partial refund reduces the original expense but does not erase it`() {
        val messages = listOf(
            T.sms("AD-ICICIB", "Rs.4,000.00 spent on ICICI Bank Credit Card XX1923 at AMAZON on 03-08-26.", T.at(3, 19, 0), "p1"),
            T.sms("AD-ICICIB", "Rs.1,500.00 credited to your ICICI Bank Credit Card XX1923 as refund from AMAZON on 09-08-26.", T.at(9, 11, 0), "p2")
        )
        val result = pipeline(iciciCard).process(messages)

        val purchase = result.transactions.first { it.amount == Money.ofRupees(4_000L) }
        assertEquals(TransactionStatus.PARTIALLY_REFUNDED, purchase.status)
        assertEquals(Money.ofRupees(2_500L), purchase.netImpact)
        assertEquals(Money.ofRupees(2_500L), spendTotal(result.transactions))
    }

    @Test
    fun `a failed transaction has no financial impact`() {
        val messages = listOf(
            T.sms("VM-HDFCBK", "Your transaction of INR 5,000.00 to SWIGGY could not be processed. UPI Ref 425678901299", T.at(31, 20, 43), "f1")
        )
        val result = pipeline(hdfc).process(messages)
        assertEquals(TransactionStatus.FAILED, result.transactions.single().status)
        assertEquals(Money.ZERO, spendTotal(result.transactions))
    }

    @Test
    fun `withdrawing cash and then spending it is not counted twice`() {
        val messages = listOf(
            T.sms("AD-SBIINB", "Rs.5000 withdrawn from ATM at KORAMANGALA on 31-08-26 from A/c XXXXX8304. Avl Bal Rs.57,400.00", T.at(31, 18, 10), "a1")
        )
        val result = pipeline(sbi).process(messages)
        val withdrawal = result.transactions.single()
        assertEquals(TransactionKind.CASH_WITHDRAWAL, withdrawal.kind)
        assertEquals(Money.ZERO, spendTotal(result.transactions), "moving money to the cash wallet is not spending")
    }

    @Test
    fun `salary is income and the accounts it touches are discovered`() {
        val messages = listOf(
            T.sms("AD-HDFCBK", "Rs.52,340.00 credited to A/c XXXX4821 on 31-08-26 towards SALARY AUG 2026. Avl Bal Rs.77,190.00", T.at(31, 6, 30), "sal")
        )
        val result = LedgerPipeline().process(messages)
        assertEquals(TransactionKind.SALARY, result.transactions.single().kind)
        assertEquals(Money.ofRupees(52_340L), incomeTotal(result.transactions))
        assertTrue(result.reconciliation.discoveredAccounts.any { it.ref.last4 == "4821" })
    }

    @Test
    fun `an unidentified payment lands in the review queue instead of being guessed`() {
        val messages = listOf(
            T.sms("VM-GOOGLE", "You paid Rs.220.00 to ABC CAFE using Google Pay.", T.at(31, 9, 15), "u1")
        )
        val result = LedgerPipeline().process(messages)
        val reasons = result.reconciliation.reviewItems.map { it.reason }
        assertTrue(ReviewReason.UNKNOWN_ACCOUNT in reasons, "no account was named, so ask")
        assertNotNull(result.transactions.single().merchant)
    }

    @Test
    fun `re-importing the same messages does not duplicate anything`() {
        val messages = listOf(
            T.sms("VM-HDFCBK", "Rs.540.00 debited from A/c XX4821 towards UPI-SWIGGY on 31-Aug-26. UPI Ref 425678901234", T.at(31, 20, 43), "s1")
        )
        val engine = pipeline(hdfc)
        val first = engine.process(messages)
        val second = engine.process(messages, existing = first.transactions)

        assertEquals(1, second.transactions.size)
        assertEquals(1, second.transactions.single().sourceCount)
        assertTrue(second.reconciliation.created.isEmpty())
    }

    @Test
    fun `messages that are not transactions are reported as skipped, not dropped silently`() {
        val messages: List<RawMessage> = listOf(
            T.sms("VM-HDFCBK", "OTP 746832 for transaction of Rs.5000. Do not share.", T.at(31, 20, 43), "n1"),
            T.sms("AD-SBIINB", "Your A/c XXXXX8304 has Avl Bal Rs.62,400.15 as on 31-08-26.", T.at(31, 21, 0), "n2"),
            T.sms("VK-HDFCBK", "Pre-approved loan of Rs.5,00,000! Apply now https://x.in T&C apply", T.at(31, 21, 5), "n3")
        )
        val result = LedgerPipeline().process(messages)
        assertTrue(result.transactions.isEmpty())
        assertEquals(3, result.skipped.size)
    }
}
