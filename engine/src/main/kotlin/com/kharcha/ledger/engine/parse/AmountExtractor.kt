package com.kharcha.ledger.engine.parse

import com.kharcha.ledger.engine.model.Money

/** What a number in a financial SMS actually refers to. */
enum class AmountRole {
    TRANSACTION,
    BALANCE,
    AVAILABLE_LIMIT,
    CREDIT_LIMIT,
    TOTAL_DUE,
    MINIMUM_DUE,
    STATEMENT,
    REWARD,
    UNKNOWN
}

data class AmountReading(
    val money: Money,
    val role: AmountRole,
    val index: Int,
    val raw: String,
    val leftContext: String,
    val rightContext: String
)

/**
 * Pulls every rupee amount out of a message and, crucially, decides what each
 * one *means*.
 *
 * A single card SMS routinely contains four numbers — the spend, the available
 * balance, the total due and the minimum due — and only one of them is money
 * that just moved. Grabbing "the first ₹ in the string" is the single most
 * common way an Indian expense tracker ends up reporting nonsense.
 */
object AmountExtractor {

    private const val CONTEXT = 42

    /** `Rs.540`, `INR 540.00`, `₹1,00,000`, `Rs 5,000/-`. */
    private val PREFIXED = Regex(
        "(?:RS\\.?|INR|₹|MRP)\\s*\\.?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE
    )

    /** `540.00 INR`, `5,000 Rs`. */
    private val SUFFIXED = Regex(
        "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:RS\\.?|INR|RUPEES)\\b",
        RegexOption.IGNORE_CASE
    )

    private val BALANCE_LEFT = Regex(
        "(AVL\\.? ?BAL|AVAIL(ABLE)?\\.? ?BAL(ANCE)?|A/C BAL|BAL(ANCE)?( IS)?|CLEAR BAL|CLOSING BAL|" +
            "TOTAL BAL|BALANCE:|BAL:)\\s*[-:]?\\s*$"
    )
    private val BALANCE_RIGHT = Regex("^\\s*(IS |)?(YOUR |THE )?(AVAILABLE |AVL |CURRENT |CLEAR )?BAL")
    private val AVAILABLE_LIMIT_LEFT = Regex("(AVL\\.? ?(CREDIT )?LIMIT|AVAILABLE (CREDIT )?LIMIT|AVAILABLE CREDIT)\\s*[-:]?\\s*$")
    private val CREDIT_LIMIT_LEFT = Regex("(TOTAL (CREDIT )?LIMIT|CREDIT LIMIT|CASH LIMIT|LIMIT OF)\\s*[-:]?\\s*$")
    private val TOTAL_DUE_LEFT = Regex("(TOTAL (AMOUNT )?DUE|TOT(AL)? AMT DUE|AMOUNT DUE|OUTSTANDING( AMOUNT| BAL(ANCE)?)?|TAD)\\s*[-:]?( IS)?\\s*$")
    private val MIN_DUE_LEFT = Regex("(MIN(IMUM)?\\.? ?(AMOUNT |AMT )?DUE|MAD)\\s*[-:]?( IS)?\\s*$")
    private val STATEMENT_LEFT = Regex("(STATEMENT (AMOUNT|BALANCE|OF)|BILL (AMOUNT|OF)|BILLED AMOUNT)\\s*[-:]?\\s*$")
    private val REWARD_LEFT = Regex("(CASHBACK OF|REWARD(S)? WORTH|EARNED)\\s*[-:]?\\s*$")

    private val MOVEMENT = Regex(
        "\\b(DEBITED|CREDITED|SPENT|PAID|WITHDRAWN|SENT|TRANSFERRED|DEDUCTED|RECEIVED|PURCHASE|" +
            "TXN|TRANSACTION|PAYMENT|CHARGED|DEBIT|CREDIT|REFUND|REVERSED)\\b"
    )

    fun extractAll(upperBody: String): List<AmountReading> {
        val readings = mutableListOf<AmountReading>()
        val consumed = mutableListOf<IntRange>()

        fun add(match: MatchResult, group: Int) {
            if (consumed.any { it.first <= match.range.first && match.range.first <= it.last }) return
            val money = Money.parseAmountToken(match.groupValues[group]) ?: return
            val left = upperBody.substring((match.range.first - CONTEXT).coerceAtLeast(0), match.range.first)
            val rightEnd = (match.range.last + 1 + CONTEXT).coerceAtMost(upperBody.length)
            val right = upperBody.substring((match.range.last + 1).coerceAtMost(upperBody.length), rightEnd)
            consumed += match.range
            readings += AmountReading(money, roleFor(left, right), match.range.first, match.value, left, right)
        }

        PREFIXED.findAll(upperBody).forEach { add(it, 1) }
        SUFFIXED.findAll(upperBody).forEach { add(it, 1) }
        return readings.sortedBy { it.index }
    }

    private fun roleFor(left: String, right: String): AmountRole = when {
        MIN_DUE_LEFT.containsMatchIn(left) -> AmountRole.MINIMUM_DUE
        TOTAL_DUE_LEFT.containsMatchIn(left) -> AmountRole.TOTAL_DUE
        AVAILABLE_LIMIT_LEFT.containsMatchIn(left) -> AmountRole.AVAILABLE_LIMIT
        CREDIT_LIMIT_LEFT.containsMatchIn(left) -> AmountRole.CREDIT_LIMIT
        STATEMENT_LEFT.containsMatchIn(left) -> AmountRole.STATEMENT
        REWARD_LEFT.containsMatchIn(left) -> AmountRole.REWARD
        BALANCE_LEFT.containsMatchIn(left) -> AmountRole.BALANCE
        BALANCE_RIGHT.containsMatchIn(right) -> AmountRole.BALANCE
        MOVEMENT.containsMatchIn(left) || MOVEMENT.containsMatchIn(right) -> AmountRole.TRANSACTION
        else -> AmountRole.UNKNOWN
    }

    /**
     * The amount that actually moved. Prefers an amount explicitly tied to a
     * movement verb; falls back to the first amount that is provably not a
     * balance, limit or due figure.
     */
    fun transactionAmount(readings: List<AmountReading>): AmountReading? =
        readings.firstOrNull { it.role == AmountRole.TRANSACTION }
            ?: readings.firstOrNull { it.role == AmountRole.UNKNOWN }

    fun firstOf(readings: List<AmountReading>, role: AmountRole): Money? =
        readings.firstOrNull { it.role == role }?.money
}
