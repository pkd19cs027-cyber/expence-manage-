package com.kharcha.ledger.engine.classify

import com.kharcha.ledger.engine.T
import com.kharcha.ledger.engine.model.MessageClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ClassifierTest {

    private fun classOf(sender: String, body: String) =
        FinancialMessageClassifier.classify(T.sms(sender, body, T.at(31, 20, 43))).messageClass

    @Test
    fun `OTP quoting an amount never becomes a transaction`() {
        val result = FinancialMessageClassifier.classify(
            T.sms("VM-HDFCBK", "OTP 746832 for transaction of Rs.5000 at AMAZON. Do not share this OTP with anyone.", T.at(31, 20, 43))
        )
        assertEquals(MessageClass.OTP, result.messageClass)
        assertFalse(result.producesTransaction, "an OTP must never create an expense")
    }

    @Test
    fun `marketing message is not financial`() {
        assertEquals(
            MessageClass.PROMOTIONAL,
            classOf("VK-HDFCBK", "Congratulations! You are pre-approved for a personal loan of Rs.5,00,000 at lowest interest. Apply now https://hdfcbk.in/x T&C apply")
        )
    }

    @Test
    fun `balance alert carries an amount but no movement`() {
        assertEquals(
            MessageClass.BALANCE_ALERT,
            classOf("AD-SBIINB", "Your A/c XXXXX8304 has Avl Bal Rs.62,400.15 as on 31-08-26. -SBI")
        )
    }

    @Test
    fun `failed transaction is recognised before the debit rule`() {
        assertEquals(
            MessageClass.FAILED_TRANSACTION,
            classOf("VM-HDFCBK", "Your transaction of INR 5,000.00 to SWIGGY could not be processed. UPI Ref 425678901299.")
        )
    }

    @Test
    fun `reversal is distinguished from an ordinary credit`() {
        assertEquals(
            MessageClass.REVERSAL,
            classOf("VM-HDFCBK", "Rs.2000.00 has been reversed and credited back to your A/c XX4821 towards failed UPI txn. UPI Ref 425678901234")
        )
    }

    @Test
    fun `mandate notice about a future debit is not a transaction`() {
        val result = FinancialMessageClassifier.classify(
            T.sms("AD-ICICIB", "Rs.649 will be debited from your A/c XX4821 on 05-Sep-26 towards NETFLIX e-mandate.", T.at(31, 9, 0))
        )
        assertEquals(MessageClass.MANDATE, result.messageClass)
        assertFalse(result.producesTransaction)
    }

    @Test
    fun `card statement is not a spend`() {
        assertEquals(
            MessageClass.CARD_STATEMENT,
            classOf("AD-ICICIB", "Your ICICI Bank Credit Card XX1923 statement is generated. Total Amount Due Rs.11,850.00, Minimum Due Rs.600.00, Due Date 12-Sep-26.")
        )
    }

    @Test
    fun `card bill payment is not a card spend`() {
        assertEquals(
            MessageClass.CARD_BILL_PAYMENT,
            classOf("AD-ICICIB", "Payment of Rs.20,000.00 received towards your ICICI Bank Credit Card XX1923 on 05-Sep-26. Thank you.")
        )
    }

    @Test
    fun `salary credit is classified as salary`() {
        assertEquals(
            MessageClass.SALARY,
            classOf("AD-HDFCBK", "Rs.52,340.00 credited to A/c XXXX4821 on 31-08-26 towards SALARY AUG 2026. Avl Bal Rs.77,190.00")
        )
    }

    @Test
    fun `atm withdrawal is its own class`() {
        assertEquals(
            MessageClass.ATM_WITHDRAWAL,
            classOf("AD-SBIINB", "Rs.5000 withdrawn from ATM at KORAMANGALA on 31-08-26 from A/c XXXXX8304. Avl Bal Rs.57,400.00")
        )
    }

    @Test
    fun `transactional message with a T and C footer still books`() {
        assertEquals(
            MessageClass.UPI_TRANSACTION,
            classOf("VM-HDFCBK", "Rs.540.00 debited from A/c XX4821 towards UPI-SWIGGY on 31-Aug-26. UPI Ref 425678901234. T&C apply")
        )
    }
}
