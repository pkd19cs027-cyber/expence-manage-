package com.kharcha.ledger.engine.reconcile

import com.kharcha.ledger.engine.model.CandidateTransaction
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.ReferenceKind
import java.security.MessageDigest
import java.time.Instant

/**
 * Fingerprints let the engine find *candidate* matches cheaply before running
 * the (much more expensive) scoring pass.
 *
 * Several fingerprints exist per transaction on purpose: every message omits
 * something. A bank SMS has the account but no merchant; a merchant app has the
 * merchant but no account; a wallet has neither but does have the reference.
 * One hash would simply fail whenever its input was the missing field.
 */
object Fingerprints {

    private const val SOFT_WINDOW_SECONDS = 300L

    /** Strongest: the payment rail's own identifier. */
    fun exact(reference: String?, kind: ReferenceKind): String? {
        if (reference.isNullOrBlank()) return null
        if (kind == ReferenceKind.NONE) return null
        val normalized = reference.uppercase().filter { it.isLetterOrDigit() }.trimStart('0')
        if (normalized.length < 6) return null
        return "REF:" + sha(normalized)
    }

    /** Account + amount + merchant + a five-minute time bucket. */
    fun soft(
        accountKey: String?,
        amount: Money,
        merchantKey: String?,
        timestamp: Instant,
        direction: Direction
    ): String = "SOFT:" + sha(
        listOf(
            accountKey ?: "?",
            amount.paise.toString(),
            merchantKey ?: "?",
            bucket(timestamp).toString(),
            direction.name
        ).joinToString("|")
    )

    /** Amount + merchant + day: loose enough to survive a missing account. */
    fun coarse(amount: Money, merchantKey: String?, timestamp: Instant, direction: Direction): String =
        "COARSE:" + sha(
            listOf(
                amount.paise.toString(),
                merchantKey ?: "?",
                (timestamp.epochSecond / 86_400L).toString(),
                direction.name
            ).joinToString("|")
        )

    fun of(candidate: CandidateTransaction, merchantKey: String?): Set<String> = buildSet {
        exact(candidate.reference, candidate.referenceKind)?.let { add(it) }
        add(soft(candidate.account?.key, candidate.amount, merchantKey, candidate.timestamp, candidate.direction))
        add(coarse(candidate.amount, merchantKey, candidate.timestamp, candidate.direction))
    }

    fun of(transaction: CanonicalTransaction): Set<String> = buildSet {
        exact(transaction.reference, transaction.referenceKind)?.let { add(it) }
        add(soft(transaction.account?.key, transaction.amount, transaction.merchant, transaction.timestamp, transaction.direction))
        add(coarse(transaction.amount, transaction.merchant, transaction.timestamp, transaction.direction))
    }

    /**
     * Buckets are computed on both edges so that two messages either side of a
     * bucket boundary still collide on one of them.
     */
    private fun bucket(timestamp: Instant): Long = timestamp.epochSecond / SOFT_WINDOW_SECONDS

    private fun sha(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
}
