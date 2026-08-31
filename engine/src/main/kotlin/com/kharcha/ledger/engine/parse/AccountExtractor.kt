package com.kharcha.ledger.engine.parse

import com.kharcha.ledger.engine.model.AccountType

data class AccountMention(
    val last4: String,
    val type: AccountType,
    val index: Int,
    val raw: String,
    /** True when the message named a card rather than an account. */
    val isCard: Boolean
)

/**
 * Finds the account and card numbers a message refers to.
 *
 * Banks mask them a dozen different ways — `A/c XX4821`, `Acct XXXXXX4821`,
 * `account ending with 4821`, `A/C *4821`, `Card no. XX1923` — but the identity
 * that matters is always the trailing four digits plus the institution, so that
 * is all that is kept.
 */
object AccountExtractor {

    private val ACCOUNT = Regex(
        "\\b(?:A/C|A/CNO|AC|ACC|ACCT|ACCOUNT|SAVINGS A/C|SB A/C)\\s*" +
            "(?:NO\\.?|NUMBER|#)?\\s*[:.]?\\s*(?:ENDING\\s*(?:WITH|IN)?\\s*)?" +
            "[X*#]*\\s*(\\d{3,18})\\b"
    )

    private val ACCOUNT_ENDING = Regex(
        "\\bACCOUNT\\s+ENDING\\s+(?:WITH\\s+|IN\\s+)?[X*#]*\\s*(\\d{3,6})\\b"
    )

    private val CARD = Regex(
        "\\b(?:(CREDIT|DEBIT|PREPAID)\\s+)?CARD\\s*" +
            "(?:NO\\.?|NUMBER|#)?\\s*(?:ENDING\\s*(?:WITH|IN)?\\s*)?[:.]?\\s*" +
            "[X*#]*\\s*(\\d{3,6})\\b"
    )

    private val CARD_SUFFIX_FIRST = Regex(
        "\\b(?:X{2,}|\\*{2,})(\\d{4})\\s+(CREDIT|DEBIT)\\s+CARD\\b"
    )

    /** Bare masked number, e.g. `XX4821` or `XXXXXX4821`, used as a last resort. */
    private val MASKED = Regex("\\b(?:X{2,}|\\*{2,}|#{2,})\\s*(\\d{3,6})\\b")

    private val WALLET = Regex("\\b(WALLET|PAYTM BALANCE|AMAZON PAY BALANCE)\\b")

    fun extract(upperBody: String, issuerIsCardCompany: Boolean = false): List<AccountMention> {
        val mentions = mutableListOf<AccountMention>()
        val taken = mutableListOf<IntRange>()

        fun claim(range: IntRange): Boolean {
            if (taken.any { it.first <= range.first && range.first <= it.last }) return false
            taken += range
            return true
        }

        CARD_SUFFIX_FIRST.findAll(upperBody).forEach { m ->
            if (!claim(m.range)) return@forEach
            val credit = m.groupValues[2] == "CREDIT" || issuerIsCardCompany
            mentions += AccountMention(
                last4 = last4(m.groupValues[1]),
                type = if (credit) AccountType.CREDIT_CARD else AccountType.SAVINGS,
                index = m.range.first,
                raw = m.value,
                isCard = true
            )
        }

        CARD.findAll(upperBody).forEach { m ->
            if (!claim(m.range)) return@forEach
            val qualifier = m.groupValues[1]
            val credit = qualifier == "CREDIT" || (qualifier.isEmpty() && issuerIsCardCompany)
            mentions += AccountMention(
                last4 = last4(m.groupValues[2]),
                // A debit-card spend still leaves the bank account; it is only
                // credit cards that are a separate liability account.
                type = if (credit) AccountType.CREDIT_CARD else AccountType.SAVINGS,
                index = m.range.first,
                raw = m.value,
                isCard = true
            )
        }

        ACCOUNT_ENDING.findAll(upperBody).forEach { m ->
            if (!claim(m.range)) return@forEach
            mentions += AccountMention(last4(m.groupValues[1]), AccountType.SAVINGS, m.range.first, m.value, false)
        }

        ACCOUNT.findAll(upperBody).forEach { m ->
            if (!claim(m.range)) return@forEach
            val digits = m.groupValues[1]
            // Long unmasked digit runs in the middle of a reference number are
            // not account numbers; a real masked account shows 3-6 visible digits.
            if (digits.length > 6) return@forEach
            mentions += AccountMention(last4(digits), AccountType.SAVINGS, m.range.first, m.value, false)
        }

        if (mentions.isEmpty()) {
            MASKED.findAll(upperBody).forEach { m ->
                if (!claim(m.range)) return@forEach
                val walletContext = WALLET.containsMatchIn(upperBody)
                mentions += AccountMention(
                    last4 = last4(m.groupValues[1]),
                    type = if (walletContext) AccountType.PREPAID_WALLET else AccountType.UNKNOWN,
                    index = m.range.first,
                    raw = m.value,
                    isCard = false
                )
            }
        }

        return mentions.sortedBy { it.index }
    }

    private fun last4(digits: String): String =
        if (digits.length <= 4) digits.padStart(4, '0') else digits.takeLast(4)
}
