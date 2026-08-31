package com.kharcha.ledger.engine.parse

import com.kharcha.ledger.engine.T
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.ReferenceKind
import com.kharcha.ledger.engine.model.TimestampSource
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmountExtractorTest {

    @Test
    fun `the spend is picked, not the balance`() {
        val body = "RS.5000.00 DEBITED FROM A/C XX4821 ON 31-08-26. AVL BAL RS.45,900.00"
        val readings = AmountExtractor.extractAll(body)
        assertEquals(Money.ofRupees(5000L), AmountExtractor.transactionAmount(readings)?.money)
        assertEquals(Money.ofRupees(45_900L), AmountExtractor.firstOf(readings, AmountRole.BALANCE))
    }

    @Test
    fun `statement figures are never spends`() {
        val body = "TOTAL AMOUNT DUE RS.27,000.00, MINIMUM DUE RS.3,000.00, CREDIT LIMIT RS.1,00,000.00"
        val readings = AmountExtractor.extractAll(body)
        assertNull(AmountExtractor.transactionAmount(readings), "nothing here moved")
        assertEquals(Money.ofRupees(27_000L), AmountExtractor.firstOf(readings, AmountRole.TOTAL_DUE))
        assertEquals(Money.ofRupees(3_000L), AmountExtractor.firstOf(readings, AmountRole.MINIMUM_DUE))
        assertEquals(Money.ofRupees(100_000L), AmountExtractor.firstOf(readings, AmountRole.CREDIT_LIMIT))
    }

    @Test
    fun `lakh grouping and paise survive the round trip`() {
        assertEquals(Money.ofRupees(100_000L), Money.parseAmountToken("1,00,000"))
        assertEquals(Money(54_010), Money.parseAmountToken("540.10"))
        assertEquals("₹1,00,000", Money.ofRupees(100_000L).format())
        assertEquals("₹540.10", Money(54_010).format())
    }

    @Test
    fun `available limit is not a transaction`() {
        val body = "RS.4,500.00 SPENT ON ICICI CARD XX1923 AT AMAZON. AVAILABLE LIMIT RS.95,500.00"
        val readings = AmountExtractor.extractAll(body)
        assertEquals(Money.ofRupees(4_500L), AmountExtractor.transactionAmount(readings)?.money)
        assertEquals(Money.ofRupees(95_500L), AmountExtractor.firstOf(readings, AmountRole.AVAILABLE_LIMIT))
    }
}

class DateExtractorTest {

    private val received = T.at(31, 21, 5)

    private fun parsed(body: String) =
        LocalDateTime.ofInstant(DateExtractor.extract(body, received).instant, DateExtractor.IST)

    @Test
    fun `every common Indian date format lands on the same day`() {
        listOf(
            "PAID ON 31-AUG-26 AT 20:43",
            "PAID ON 31/08/2026 AT 20:43",
            "PAID ON 31-08-26 20:43",
            "PAID ON 31AUG26 20:43",
            "PAID ON 2026-08-31 20:43"
        ).forEach { body ->
            val result = parsed(body)
            assertEquals(2026, result.year, body)
            assertEquals(8, result.monthValue, body)
            assertEquals(31, result.dayOfMonth, body)
            assertEquals(20, result.hour, body)
            assertEquals(43, result.minute, body)
        }
    }

    @Test
    fun `12-hour clock is understood`() {
        assertEquals(20, parsed("PAID ON 31-AUG-26 AT 08:43 PM").hour)
        assertEquals(8, parsed("PAID ON 31-AUG-26 AT 08:43 AM").hour)
    }

    @Test
    fun `a message with no date falls back to the SMS clock and says so`() {
        val extracted = DateExtractor.extract("RS.540 DEBITED VIA UPI", received)
        assertEquals(received, extracted.instant)
        assertEquals(TimestampSource.MESSAGE_RECEIVED_TIME, extracted.source)
    }

    @Test
    fun `a due date far in the future is not mistaken for the transaction time`() {
        val body = "TOTAL DUE RS.11,850.00 DUE DATE 12-SEP-26"
        val extracted = DateExtractor.extract(body, received)
        assertEquals(TimestampSource.MESSAGE_RECEIVED_TIME, extracted.source)
        assertEquals(9, DateExtractor.extractDueDate(body, received)?.monthValue)
        assertEquals(12, DateExtractor.extractDueDate(body, received)?.dayOfMonth)
    }
}

class AccountExtractorTest {

    @Test
    fun `every masking style resolves to the same last four`() {
        listOf(
            "A/C XX4821", "ACCOUNT *4821", "ACCT XXXXXX4821", "SAVINGS A/C 4821",
            "ACCOUNT ENDING 4821", "A/C NO. XXXXXXXX4821"
        ).forEach { body ->
            val mentions = AccountExtractor.extract("RS.540 DEBITED FROM $body")
            assertEquals("4821", mentions.firstOrNull()?.last4, body)
        }
    }

    @Test
    fun `a credit card is a different account from a savings account`() {
        val mentions = AccountExtractor.extract("RS.4500 SPENT ON CREDIT CARD XX1923", issuerIsCardCompany = false)
        assertEquals(AccountType.CREDIT_CARD, mentions.first().type)
    }

    @Test
    fun `bank account and card in one message are both found`() {
        val mentions = AccountExtractor.extract(
            "RS.20,000.00 DEBITED FROM A/C XX4821 TOWARDS ICICI CREDIT CARD XX1923"
        )
        assertEquals(setOf("4821", "1923"), mentions.map { it.last4 }.toSet())
        assertTrue(mentions.any { it.type == AccountType.CREDIT_CARD })
    }
}

class ReferenceExtractorTest {

    @Test
    fun `UPI reference wins over a generic reference`() {
        val result = ReferenceExtractor.extract("UPI REF NO 425678901234 REF 9988")
        assertEquals("425678901234", result?.value)
        assertEquals(ReferenceKind.UPI_REF, result?.kind)
    }

    @Test
    fun `slash-delimited UPI descriptors expose the reference`() {
        assertEquals("425678901234", ReferenceExtractor.extract("UPI/425678901234/SWIGGY")?.value)
        assertEquals("425678901234", ReferenceExtractor.extract("UPI/P2M/425678901234/SWIGGY")?.value)
    }

    @Test
    fun `IMPS reference is captured as a UTR`() {
        val result = ReferenceExtractor.extract("IMPS REF 423512345678 TO RAHUL")
        assertEquals("423512345678", result?.value)
        assertEquals(ReferenceKind.UTR, result?.kind)
    }

    @Test
    fun `no reference means no reference`() {
        assertNull(ReferenceExtractor.extract("RS.540 DEBITED FROM A/C XX4821"))
    }
}
