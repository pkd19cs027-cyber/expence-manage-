package com.kharcha.ledger.engine.parse

import com.kharcha.ledger.engine.T
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.PaymentMethod
import com.kharcha.ledger.engine.model.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionParserTest {

    @Test
    fun `three wordings of one payment produce the same facts`() {
        val bodies = listOf(
            "Rs.540 debited from A/c XX4821 towards UPI-SWIGGY on 31-Aug-26. UPI Ref 425678901234. -HDFC Bank",
            "INR 540.00 has been debited from account ending 4821 via UPI on 31-08-26. UPI Ref no. 425678901234.",
            "Your A/c XXXX4821 is debited by Rs 540.00. UPI Ref 425678901234."
        )
        bodies.forEach { body ->
            val candidate = TransactionParser.parse(T.sms("VM-HDFCBK", body, T.at(31, 20, 43))).candidate
            assertNotNull(candidate, body)
            assertEquals(Money.ofRupees(540L), candidate.amount, body)
            assertEquals(Direction.DEBIT, candidate.direction, body)
            assertEquals("4821", candidate.account?.last4, body)
            assertEquals("HDFC", candidate.account?.institutionId, body)
            assertEquals(PaymentMethod.UPI, candidate.method, body)
            assertEquals("425678901234", candidate.reference, body)
        }
    }

    @Test
    fun `merchant descriptors resolve to one canonical name`() {
        val bodies = listOf(
            "Rs.540 debited from A/c XX4821 towards UPI-SWIGGY on 31-Aug-26.",
            "Rs.540 debited from A/c XX4821. Info: UPI/425678901234/SWIGGY",
            "Rs.540 spent at RAZORPAY*SWIGGY from A/c XX4821"
        )
        bodies.forEach { body ->
            val candidate = TransactionParser.parse(T.sms("VM-HDFCBK", body, T.at(31, 20, 43))).candidate
            assertEquals("Swiggy", candidate?.merchantCanonical, body)
        }
    }

    @Test
    fun `a Google Pay message does not invent a Google Pay account`() {
        val candidate = TransactionParser.parse(
            T.sms("VM-GOOGLE", "You paid Rs.540.00 to Swiggy using Google Pay. UPI Ref 425678901234", T.at(31, 20, 43))
        ).candidate
        assertNotNull(candidate)
        assertNull(candidate.account, "a UPI app is a channel, not an account")
        assertEquals("Google Pay", candidate.channelApp)
    }

    @Test
    fun `a Google Pay message that names the funding bank uses that bank`() {
        val candidate = TransactionParser.parse(
            T.sms(
                "VM-GOOGLE",
                "You paid Rs.540.00 to Swiggy from HDFC Bank A/c XX4821 using Google Pay. UPI Ref 425678901234",
                T.at(31, 20, 43)
            )
        ).candidate
        assertEquals("HDFC", candidate?.account?.institutionId)
        assertEquals("4821", candidate?.account?.last4)
        assertEquals("Google Pay", candidate?.channelApp)
    }

    @Test
    fun `a card bill payment names both sides`() {
        val candidate = TransactionParser.parse(
            T.sms(
                "VM-HDFCBK",
                "Rs.20,000.00 debited from A/c XX4821 towards ICICI Credit Card XX1923 on 05-Sep-26. UPI Ref 425600001111",
                T.at(5, 11, 0, month = 9)
            )
        ).candidate
        assertNotNull(candidate)
        assertEquals(TransactionKind.CARD_PAYMENT, candidate.kind)
        assertEquals("HDFC", candidate.account?.institutionId)
        assertEquals("4821", candidate.account?.last4)
        assertEquals("ICICI", candidate.counterAccount?.institutionId)
        assertEquals(AccountType.CREDIT_CARD, candidate.counterAccount?.type)
    }

    @Test
    fun `an ATM withdrawal moves money to the cash wallet`() {
        val candidate = TransactionParser.parse(
            T.sms(
                "AD-SBIINB",
                "Rs.5000 withdrawn from ATM at KORAMANGALA on 31-08-26 from A/c XXXXX8304. Avl Bal Rs.57,400.00",
                T.at(31, 18, 10)
            )
        ).candidate
        assertNotNull(candidate)
        assertEquals(TransactionKind.CASH_WITHDRAWAL, candidate.kind)
        assertEquals(AccountType.CASH, candidate.counterAccount?.type)
        assertEquals(Money.ofRupees(5_000L), candidate.amount)
        assertEquals(Money.ofRupees(57_400L), candidate.balanceAfter)
    }

    @Test
    fun `the balance is captured with its own timestamp, not as a spend`() {
        val candidate = TransactionParser.parse(
            T.sms(
                "VM-HDFCBK",
                "Rs.540.00 debited from A/c XX4821 on 31-08-26. Avl Bal Rs.24,850.00",
                T.at(31, 20, 43)
            )
        ).candidate
        assertEquals(Money.ofRupees(540L), candidate?.amount)
        assertEquals(Money.ofRupees(24_850L), candidate?.balanceAfter)
    }

    @Test
    fun `a failed transaction is parsed but marked failed`() {
        val candidate = TransactionParser.parse(
            T.sms("VM-HDFCBK", "Your transaction of INR 5,000.00 could not be processed. UPI Ref 425678901299", T.at(31, 20, 43))
        ).candidate
        assertNotNull(candidate)
        assertTrue(candidate.failed)
    }

    @Test
    fun `an OTP produces no candidate at all`() {
        val result = TransactionParser.parse(
            T.sms("VM-HDFCBK", "OTP 746832 for transaction of Rs.5000. Do not share.", T.at(31, 20, 43))
        )
        assertNull(result.candidate)
    }

    @Test
    fun `a SIP debit is an investment, not consumption`() {
        val candidate = TransactionParser.parse(
            T.sms(
                "AD-HDFCBK",
                "Rs.5,000.00 debited from A/c XX4821 towards SIP of AXIS MUTUAL FUND on 05-09-26.",
                T.at(5, 8, 0, month = 9)
            )
        ).candidate
        assertEquals(TransactionKind.INVESTMENT, candidate?.kind)
    }

    @Test
    fun `an EMI debit is booked as an EMI without inventing a principal split`() {
        val candidate = TransactionParser.parse(
            T.sms("AD-HDFCBK", "Rs.12,400.00 debited from A/c XX4821 towards EMI for Loan A/c 998877.", T.at(5, 8, 0, month = 9))
        ).candidate
        assertEquals(TransactionKind.EMI, candidate?.kind)
        assertEquals(Money.ofRupees(12_400L), candidate?.amount)
    }
}
