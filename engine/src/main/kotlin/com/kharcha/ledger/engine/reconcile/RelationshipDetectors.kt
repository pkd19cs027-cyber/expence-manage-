package com.kharcha.ledger.engine.reconcile

import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.TransactionKind
import kotlin.math.abs

data class DetectedPair(
    val debit: CanonicalTransaction,
    val credit: CanonicalTransaction,
    val confidence: Double,
    val reason: String,
    val amount: Money = credit.amount
)

/**
 * A debit here and a credit there that are really one movement between two of
 * the user's own accounts.
 *
 * Left alone, an account sweep of ₹10,000 shows up as ₹10,000 spent *and*
 * ₹10,000 earned, which corrupts every report on the dashboard.
 */
object TransferDetector {

    private const val WINDOW_SECONDS = 6 * 3600L

    fun detect(
        transactions: List<CanonicalTransaction>,
        registry: SelfAccountRegistry
    ): List<DetectedPair> {
        val debits = transactions.filter {
            it.direction == Direction.DEBIT &&
                registry.isSelf(it.account) &&
                it.status.hasFinancialImpact &&
                it.kind != TransactionKind.CASH_WITHDRAWAL
        }
        val credits = transactions.filter {
            it.direction == Direction.CREDIT &&
                registry.isSelf(it.account) &&
                it.status.hasFinancialImpact &&
                it.kind !in setOf(TransactionKind.REFUND, TransactionKind.REVERSAL)
        }

        val used = mutableSetOf<String>()
        val pairs = mutableListOf<DetectedPair>()

        for (debit in debits.sortedBy { it.timestamp }) {
            val match = credits.asSequence()
                .filter { it.id !in used && it.id != debit.id }
                .filter { it.amount == debit.amount }
                .filter { it.account?.key != debit.account?.key }
                // A credit landing on a credit card is a bill payment, a
                // different relationship with different accounting.
                .filter { it.account?.type != AccountType.CREDIT_CARD }
                .filter { abs(it.timestamp.epochSecond - debit.timestamp.epochSecond) <= WINDOW_SECONDS }
                .minByOrNull { abs(it.timestamp.epochSecond - debit.timestamp.epochSecond) }
                ?: continue

            val sameReference = debit.reference != null && debit.reference == match.reference
            val gap = abs(match.timestamp.epochSecond - debit.timestamp.epochSecond)
            val confidence = when {
                sameReference -> 0.98
                gap <= 120 -> 0.9
                gap <= 1800 -> 0.8
                else -> 0.65
            }
            used += match.id
            pairs += DetectedPair(debit, match, confidence, if (sameReference) "same-reference" else "amount+window")
        }
        return pairs
    }
}

/**
 * A ₹20,000 debit from a bank account that lands on a credit card is not
 * ₹20,000 of new spending — the card purchases were already booked. It is a
 * liability payment, and double-counting it is the single fastest way to make a
 * spending report untrustworthy.
 */
object CardPaymentDetector {

    private const val WINDOW_SECONDS = 3 * 86_400L

    fun detect(
        transactions: List<CanonicalTransaction>,
        registry: SelfAccountRegistry
    ): List<DetectedPair> {
        val payments = transactions.filter {
            it.direction == Direction.DEBIT &&
                (it.kind == TransactionKind.CARD_PAYMENT || it.counterAccount?.type == AccountType.CREDIT_CARD)
        }
        val cardCredits = transactions.filter {
            it.direction == Direction.CREDIT && it.account?.type == AccountType.CREDIT_CARD
        }

        val used = mutableSetOf<String>()
        return payments.mapNotNull { payment ->
            val match = cardCredits.asSequence()
                .filter { it.id !in used && it.id != payment.id }
                .filter { it.amount == payment.amount }
                .filter { payment.counterAccount == null || it.account?.key == payment.counterAccount.key }
                .filter { abs(it.timestamp.epochSecond - payment.timestamp.epochSecond) <= WINDOW_SECONDS }
                .minByOrNull { abs(it.timestamp.epochSecond - payment.timestamp.epochSecond) }
                ?: return@mapNotNull null
            used += match.id
            DetectedPair(payment, match, if (registry.isSelf(match.account)) 0.95 else 0.8, "card-bill-payment")
        }
    }
}

/**
 * UPI and card payments in India fail *after* debiting often enough that a
 * tracker without reversal handling will regularly show money the user never
 * actually spent.
 */
object ReversalDetector {

    private const val WINDOW_SECONDS = 7 * 86_400L

    fun detect(transactions: List<CanonicalTransaction>): List<DetectedPair> {
        val reversals = transactions.filter {
            it.direction == Direction.CREDIT && it.kind == TransactionKind.REVERSAL
        }
        val debits = transactions.filter { it.direction == Direction.DEBIT }

        val used = mutableSetOf<String>()
        return reversals.mapNotNull { reversal ->
            val match = debits.asSequence()
                .filter { it.id !in used && it.id != reversal.id }
                .filter { it.amount == reversal.amount }
                .filter { it.timestamp <= reversal.timestamp }
                .filter { reversal.timestamp.epochSecond - it.timestamp.epochSecond <= WINDOW_SECONDS }
                .filter { reversal.account == null || it.account == null || it.account.key == reversal.account.key }
                .maxByOrNull { it.timestamp }
                ?: return@mapNotNull null
            val sameReference = reversal.reference != null && reversal.reference == match.reference
            used += match.id
            DetectedPair(match, reversal, if (sameReference) 0.99 else 0.85, "reversal")
        }
    }
}

/**
 * A refund is not a failure: the purchase really happened and may only be
 * partly returned. `₹4,000 bought, ₹1,500 back` must net to ₹2,500, not to
 * zero and not to ₹4,000.
 */
object RefundDetector {

    private const val WINDOW_SECONDS = 90 * 86_400L

    fun detect(transactions: List<CanonicalTransaction>): List<DetectedPair> {
        val refunds = transactions.filter {
            it.direction == Direction.CREDIT && it.kind == TransactionKind.REFUND
        }
        val purchases = transactions.filter {
            it.direction == Direction.DEBIT && it.status.hasFinancialImpact
        }

        return refunds.mapNotNull { refund ->
            val candidates = purchases.asSequence()
                .filter { it.id != refund.id }
                .filter { it.timestamp <= refund.timestamp }
                .filter { refund.timestamp.epochSecond - it.timestamp.epochSecond <= WINDOW_SECONDS }
                .filter { it.amount >= refund.amount }
                .filter { (it.amount - it.refundedAmount) >= refund.amount }
                .toList()

            val sameMerchant = candidates.filter {
                refund.merchant != null && refund.merchant.equals(it.merchant, ignoreCase = true)
            }
            val pool = sameMerchant.ifEmpty {
                // Without a merchant match, only an exact-amount, same-account
                // credit is safe to attribute; anything looser guesses.
                candidates.filter {
                    it.amount == refund.amount && it.account?.key == refund.account?.key
                }
            }
            val match = pool.maxByOrNull { it.timestamp } ?: return@mapNotNull null
            val confidence = when {
                sameMerchant.isNotEmpty() && match.amount == refund.amount -> 0.95
                sameMerchant.isNotEmpty() -> 0.85
                else -> 0.6
            }
            DetectedPair(match, refund, confidence, if (sameMerchant.isNotEmpty()) "merchant-match" else "amount-match")
        }
    }
}
