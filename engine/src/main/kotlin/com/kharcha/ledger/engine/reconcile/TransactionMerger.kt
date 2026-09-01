package com.kharcha.ledger.engine.reconcile

import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.PaymentMethod
import com.kharcha.ledger.engine.model.ReferenceKind
import com.kharcha.ledger.engine.model.TimestampSource
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.model.TransactionStatus

/**
 * Folds a duplicate into its canonical transaction.
 *
 * Nothing is discarded: the merged row keeps *both* sources, so the app can say
 * "confirmed by 2 messages" and, if the user later disagrees, the merge can be
 * undone from the evidence rather than guessed at.
 */
object TransactionMerger {

    fun merge(base: CanonicalTransaction, incoming: CanonicalTransaction): CanonicalTransaction {
        val sources = (base.sources + incoming.sources)
            .distinctBy { it.sourceType to it.sourceId }
        val extraSources = (sources.size - 1).coerceAtLeast(0)

        return base.copy(
            account = preferAccount(base.account, incoming.account),
            counterAccount = base.counterAccount ?: incoming.counterAccount,
            merchant = preferMerchant(base.merchant, incoming.merchant),
            merchantRaw = base.merchantRaw ?: incoming.merchantRaw,
            category = if (base.categoryConfidence >= incoming.categoryConfidence) base.category else incoming.category,
            categoryConfidence = maxOf(base.categoryConfidence, incoming.categoryConfidence),
            channelApp = base.channelApp ?: incoming.channelApp,
            counterpartyVpa = base.counterpartyVpa ?: incoming.counterpartyVpa,
            reference = preferReference(base, incoming).first,
            referenceKind = preferReference(base, incoming).second,
            method = preferMethod(base.method, incoming.method),
            kind = preferKind(base.kind, incoming.kind),
            status = preferStatus(base.status, incoming.status),
            timestamp = preferTimestamp(base, incoming).first,
            timestampSource = preferTimestamp(base, incoming).second,
            balanceAfter = base.balanceAfter ?: incoming.balanceAfter,
            confidence = (maxOf(base.confidence, incoming.confidence) + 0.03 * extraSources).coerceAtMost(0.99),
            sources = sources,
            notes = (base.notes + incoming.notes).distinct()
        )
    }

    /** A bank's own statement of the account beats a payment app's guess. */
    private fun preferAccount(base: AccountRef?, incoming: AccountRef?): AccountRef? = when {
        base == null -> incoming
        incoming == null -> base
        base.institutionId != "UNKNOWN" -> base
        incoming.institutionId != "UNKNOWN" -> incoming
        else -> base
    }

    /** "Swiggy" beats "UPI-SWIGGY-4256"; anything beats nothing. */
    private fun preferMerchant(base: String?, incoming: String?): String? = when {
        base == null -> incoming
        incoming == null -> base
        incoming.length < base.length && incoming.none { it.isDigit() } -> incoming
        else -> base
    }

    private fun preferReference(
        base: CanonicalTransaction,
        incoming: CanonicalTransaction
    ): Pair<String?, ReferenceKind> {
        val order = listOf(ReferenceKind.UPI_REF, ReferenceKind.UTR, ReferenceKind.RRN, ReferenceKind.CHEQUE, ReferenceKind.TXN_ID)
        fun rank(kind: ReferenceKind) = order.indexOf(kind).let { if (it < 0) 99 else it }
        return when {
            base.reference == null -> incoming.reference to incoming.referenceKind
            incoming.reference == null -> base.reference to base.referenceKind
            rank(incoming.referenceKind) < rank(base.referenceKind) -> incoming.reference to incoming.referenceKind
            else -> base.reference to base.referenceKind
        }
    }

    private fun preferMethod(base: PaymentMethod, incoming: PaymentMethod): PaymentMethod = when {
        base != PaymentMethod.UNKNOWN -> base
        else -> incoming
    }

    private fun preferKind(base: TransactionKind, incoming: TransactionKind): TransactionKind = when {
        base != TransactionKind.UNKNOWN && base != TransactionKind.EXPENSE -> base
        incoming != TransactionKind.UNKNOWN && incoming != TransactionKind.EXPENSE -> incoming
        base != TransactionKind.UNKNOWN -> base
        else -> incoming
    }

    /** A failure or reversal reported by any source wins over a bare "detected". */
    private fun preferStatus(base: TransactionStatus, incoming: TransactionStatus): TransactionStatus {
        val priority = listOf(
            TransactionStatus.FAILED, TransactionStatus.REVERSED, TransactionStatus.REFUNDED,
            TransactionStatus.PARTIALLY_REFUNDED, TransactionStatus.TRANSFER,
            TransactionStatus.CONFIRMED, TransactionStatus.PENDING, TransactionStatus.DETECTED
        )
        fun rank(s: TransactionStatus) = priority.indexOf(s).let { if (it < 0) 99 else it }
        return if (rank(incoming) < rank(base)) incoming else base
    }

    /** A time the bank actually printed beats the moment the phone woke up. */
    private fun preferTimestamp(
        base: CanonicalTransaction,
        incoming: CanonicalTransaction
    ): Pair<java.time.Instant, TimestampSource> = when {
        base.timestampSource == TimestampSource.MESSAGE_BODY -> base.timestamp to base.timestampSource
        incoming.timestampSource == TimestampSource.MESSAGE_BODY -> incoming.timestamp to incoming.timestampSource
        base.timestamp <= incoming.timestamp -> base.timestamp to base.timestampSource
        else -> incoming.timestamp to incoming.timestampSource
    }
}
