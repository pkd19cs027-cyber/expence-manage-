package com.kharcha.ledger.engine.classify

import com.kharcha.ledger.engine.model.Classification
import com.kharcha.ledger.engine.model.MessageClass
import com.kharcha.ledger.engine.model.RawMessage
import com.kharcha.ledger.engine.sender.SenderRegistry

/**
 * Stage one of the pipeline. Every message on the phone passes through here and
 * leaves with a label; only labels whose [MessageClass.producesTransaction] is
 * true are handed to the parser.
 *
 * The ordering of the rules is the whole design. An OTP message that quotes a
 * ₹5,000 amount must be classified as an OTP *before* anything notices the
 * amount, otherwise the ledger gains a ₹5,000 expense that never happened.
 */
object FinancialMessageClassifier {

    fun classify(message: RawMessage): Classification {
        val body = message.upperBody
        val signals = mutableListOf<String>()

        val hasAmount = Patterns.AMOUNT.containsMatchIn(body)
        val hasAccount = Patterns.ACCOUNT_HINT.containsMatchIn(body)
        val debitVerb = Patterns.DEBIT_VERB.containsMatchIn(body)
        val creditVerb = Patterns.CREDIT_VERB.containsMatchIn(body)
        val institution = SenderRegistry.resolve(message.sender, body)
        if (institution != null) signals += "sender:${institution.id}"

        // 1. Authentication first. An OTP body may quote the amount it authorises.
        if (Patterns.OTP.containsMatchIn(body) && Patterns.OTP_CODE.containsMatchIn(body)) {
            return Classification(MessageClass.OTP, 0.99, signals + "otp-keyword")
        }
        if (Patterns.DO_NOT_SHARE.containsMatchIn(body) && Patterns.OTP_CODE.containsMatchIn(body) && !debitVerb) {
            return Classification(MessageClass.OTP, 0.9, signals + "do-not-share")
        }

        // 2. Marketing. Requires either no transaction verb at all, or a hard
        //    marketing marker, so that "spent ... T&C apply" footers survive.
        val promoHits = Patterns.PROMOTIONAL.findAll(body).map { it.value }.toList()
        if (promoHits.isNotEmpty()) {
            val transactional = (debitVerb || creditVerb) && hasAmount && hasAccount
            val hardPromo = promoHits.size >= 2 && Patterns.URL.containsMatchIn(body)
            if (!transactional || (hardPromo && !hasAccount)) {
                return Classification(
                    MessageClass.PROMOTIONAL,
                    if (transactional) 0.6 else 0.9,
                    signals + promoHits.take(3).map { "promo:$it" }
                )
            }
            signals += "promo-footer-ignored"
        }

        if (!hasAmount && !creditVerb && !debitVerb) {
            return Classification(MessageClass.NOT_FINANCIAL, 0.8, signals + "no-amount")
        }

        // 3. Announcements about money that has not moved yet.
        if (Patterns.MANDATE.containsMatchIn(body) && Patterns.FUTURE_TENSE.containsMatchIn(body)) {
            return Classification(MessageClass.MANDATE, 0.85, signals + "mandate-future")
        }
        if (Patterns.FUTURE_TENSE.containsMatchIn(body) && !debitVerbInPast(body) && !creditVerb) {
            return Classification(MessageClass.MANDATE, 0.7, signals + "future-tense")
        }

        // 4. Money that moved and came back, or never moved at all.
        if (Patterns.FAILED.containsMatchIn(body)) {
            return Classification(MessageClass.FAILED_TRANSACTION, 0.92, signals + "failure-phrase")
        }
        if (Patterns.REVERSAL.containsMatchIn(body)) {
            return Classification(MessageClass.REVERSAL, 0.9, signals + "reversal-phrase")
        }
        if (Patterns.REFUND.containsMatchIn(body)) {
            return Classification(MessageClass.REFUND, 0.88, signals + "refund-phrase")
        }

        // 5. Card bill payments must be recognised before the generic credit rule,
        //    or they book as income on the card.
        if (Patterns.CARD_BILL_PAYMENT.containsMatchIn(body)) {
            return Classification(MessageClass.CARD_BILL_PAYMENT, 0.9, signals + "card-bill-payment")
        }

        // 6. Statements and balance pings carry amounts but no movement.
        if (Patterns.STATEMENT.containsMatchIn(body) && !debitVerbInPast(body)) {
            return Classification(MessageClass.CARD_STATEMENT, 0.88, signals + "statement")
        }
        if (Patterns.BALANCE_LABEL.containsMatchIn(body) && !debitVerb && !creditVerb) {
            return Classification(MessageClass.BALANCE_ALERT, 0.9, signals + "balance-only")
        }

        // 7. Actual movement, most specific rail first.
        if (Patterns.ATM.containsMatchIn(body) && debitVerb) {
            return Classification(MessageClass.ATM_WITHDRAWAL, 0.9, signals + "atm")
        }
        if (creditVerb && Patterns.SALARY.containsMatchIn(body)) {
            return Classification(MessageClass.SALARY, 0.9, signals + "salary")
        }
        if (creditVerb && Patterns.INTEREST.containsMatchIn(body)) {
            return Classification(MessageClass.INTEREST, 0.88, signals + "interest")
        }
        if (creditVerb && Patterns.CASHBACK.containsMatchIn(body)) {
            return Classification(MessageClass.CASHBACK, 0.88, signals + "cashback")
        }
        if (Patterns.EMI.containsMatchIn(body) && debitVerb) {
            return Classification(MessageClass.EMI, 0.88, signals + "emi")
        }
        if (Patterns.INVESTMENT.containsMatchIn(body) && debitVerb) {
            return Classification(MessageClass.INVESTMENT, 0.85, signals + "investment")
        }
        if (Patterns.BILL.containsMatchIn(body) && debitVerb) {
            return Classification(MessageClass.BILL_PAYMENT, 0.85, signals + "bill")
        }
        if (Patterns.CARD.containsMatchIn(body) && (debitVerb || hasAmount)) {
            return Classification(MessageClass.CARD_SPEND, 0.87, signals + "card-spend")
        }
        if (Patterns.UPI.containsMatchIn(body) && (debitVerb || creditVerb)) {
            return Classification(MessageClass.UPI_TRANSACTION, 0.9, signals + "upi")
        }
        if ((Patterns.IMPS.containsMatchIn(body) || Patterns.NEFT.containsMatchIn(body) ||
                Patterns.RTGS.containsMatchIn(body)) && (debitVerb || creditVerb)
        ) {
            return Classification(MessageClass.BANK_TRANSFER, 0.88, signals + "bank-transfer")
        }
        if (debitVerb && hasAmount) {
            return Classification(MessageClass.DEBIT, if (hasAccount) 0.85 else 0.6, signals + "generic-debit")
        }
        if (creditVerb && hasAmount) {
            return Classification(MessageClass.CREDIT, if (hasAccount) 0.85 else 0.6, signals + "generic-credit")
        }

        return Classification(MessageClass.UNKNOWN, 0.3, signals + "unmatched")
    }

    /**
     * "₹500 will be debited on 05-Sep" is a warning; "₹500 debited" is a fact.
     * Past participles adjacent to a future-tense marker are the give-away.
     */
    private fun debitVerbInPast(body: String): Boolean {
        val match = Patterns.DEBIT_VERB.find(body) ?: return false
        val prefixStart = (match.range.first - 20).coerceAtLeast(0)
        val prefix = body.substring(prefixStart, match.range.first)
        return !Regex("\\b(WILL|SHALL|WOULD|TO)\\s*(BE)?\\s*$").containsMatchIn(prefix)
    }
}
