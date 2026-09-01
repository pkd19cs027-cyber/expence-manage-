package com.kharcha.ledger.engine.reconcile

import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.ReferenceKind
import com.kharcha.ledger.engine.model.SourceType
import kotlin.math.abs

enum class DuplicateVerdict { MERGE, REVIEW, SEPARATE }

data class DuplicateScore(
    val score: Int,
    val verdict: DuplicateVerdict,
    val signals: List<String>
) {
    val probability: Double get() = (score.coerceIn(-100, 120) + 100) / 220.0
}

/**
 * Decides whether two reconstructed transactions are the same real-world payment.
 *
 * One ₹540 Swiggy order can produce a bank SMS, a Google Pay SMS and a Swiggy
 * SMS. Booked naively that is ₹1,620 of spending. Merged wrongly, two genuine
 * ₹100 coffees on the same day become one. The scoring below is tuned to fail
 * towards *asking* rather than towards either mistake.
 */
object DuplicateDetector {

    const val MERGE_THRESHOLD = 75
    const val REVIEW_THRESHOLD = 45

    private const val TWO_MINUTES = 120L
    private const val FIVE_MINUTES = 300L
    private const val FIFTEEN_MINUTES = 900L
    private const val TWO_HOURS = 7_200L

    fun score(a: CanonicalTransaction, b: CanonicalTransaction): DuplicateScore {
        val signals = mutableListOf<String>()
        var score = 0

        // Opposite directions are never the same event. Reversals and refunds are
        // a real relationship, but they are a different relationship.
        if (a.direction != b.direction) {
            return DuplicateScore(-100, DuplicateVerdict.SEPARATE, listOf("direction-mismatch"))
        }

        val refA = Fingerprints.exact(a.reference, a.referenceKind)
        val refB = Fingerprints.exact(b.reference, b.referenceKind)
        val referenceMatch = refA != null && refA == refB
        val referenceConflict = refA != null && refB != null && refA != refB

        if (referenceMatch) {
            score += 100
            signals += "same-reference(${a.referenceKind})"
        }
        if (referenceConflict) {
            // Two different rail references mean two different payments, even
            // when everything else lines up.
            val kindsComparable = a.referenceKind == b.referenceKind &&
                a.referenceKind in setOf(ReferenceKind.UPI_REF, ReferenceKind.UTR, ReferenceKind.RRN)
            if (kindsComparable) {
                return DuplicateScore(-100, DuplicateVerdict.SEPARATE, listOf("reference-conflict"))
            }
            signals += "reference-differs(weak)"
            score -= 20
        }

        if (a.amount == b.amount) {
            score += 35
            signals += "same-amount"
        } else {
            return DuplicateScore(-100, DuplicateVerdict.SEPARATE, listOf("amount-mismatch"))
        }

        val gap = abs(a.timestamp.epochSecond - b.timestamp.epochSecond)
        when {
            gap <= TWO_MINUTES -> { score += 25; signals += "within-2-min" }
            gap <= FIVE_MINUTES -> { score += 15; signals += "within-5-min" }
            gap <= FIFTEEN_MINUTES -> { score += 5; signals += "within-15-min" }
            gap >= TWO_HOURS -> { score -= 40; signals += "hours-apart" }
            else -> signals += "same-hour"
        }

        val accountA = a.account?.key
        val accountB = b.account?.key
        when {
            accountA != null && accountA == accountB -> { score += 20; signals += "same-account" }
            accountA != null && accountB != null -> { score -= 25; signals += "different-account" }
            else -> signals += "account-unknown-on-one-side"
        }

        val merchantA = a.merchant
        val merchantB = b.merchant
        when {
            merchantA != null && merchantA.equals(merchantB, ignoreCase = true) -> {
                score += 20; signals += "same-merchant"
            }
            merchantA != null && merchantB != null -> { score -= 20; signals += "different-merchant" }
            else -> signals += "merchant-unknown-on-one-side"
        }

        if (a.method.isCompatibleWith(b.method)) {
            score += 10
            signals += "compatible-method"
        } else {
            score -= 15
            signals += "incompatible-method"
        }

        // Two messages from different reporters (bank + UPI app + merchant) is
        // corroboration; two identical messages from the same sender is more
        // likely to be two real payments the user made twice.
        if (differentReporters(a, b)) {
            score += 10
            signals += "complementary-sources"
        }

        // The repeated-purchase guard: without a shared rail reference, a gap of
        // more than a few minutes means "bought the same thing twice", which is
        // exactly what a naive matcher gets wrong.
        if (!referenceMatch && gap > FIFTEEN_MINUTES) {
            score -= 30
            signals += "repeat-purchase-guard"
        }

        val verdict = when {
            score >= MERGE_THRESHOLD -> DuplicateVerdict.MERGE
            score >= REVIEW_THRESHOLD -> DuplicateVerdict.REVIEW
            else -> DuplicateVerdict.SEPARATE
        }
        return DuplicateScore(score, verdict, signals)
    }

    private fun differentReporters(a: CanonicalTransaction, b: CanonicalTransaction): Boolean {
        val sendersA = a.sources.map { reporterKey(it.sender, it.sourceType) }.toSet()
        val sendersB = b.sources.map { reporterKey(it.sender, it.sourceType) }.toSet()
        return sendersA.isNotEmpty() && sendersB.isNotEmpty() && sendersA.intersect(sendersB).isEmpty()
    }

    private fun reporterKey(sender: String, type: SourceType): String =
        type.name + ":" + com.kharcha.ledger.engine.sender.SenderRegistry.normalizeSenderId(sender)
}
