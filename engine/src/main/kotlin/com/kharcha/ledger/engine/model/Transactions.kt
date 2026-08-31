package com.kharcha.ledger.engine.model

import java.time.Instant
import java.time.LocalDate

/** A pointer back to the message (or receipt) a transaction was derived from. */
data class SourceRef(
    val sourceType: SourceType,
    val sourceId: String,
    val sender: String,
    /** Hash of the message body, so the raw text need never be retained. */
    val messageHash: String,
    val parserVersion: Int,
    val receivedAt: Instant
)

/**
 * One financial *event* extracted from one message. A candidate is not yet a
 * transaction: three candidates may describe the same ₹540 Swiggy payment.
 * Reconciliation turns candidates into [CanonicalTransaction]s.
 */
data class CandidateTransaction(
    val source: SourceRef,
    val messageClass: MessageClass,
    val direction: Direction,
    val amount: Money,
    val timestamp: Instant,
    val timestampSource: TimestampSource,
    val account: AccountRef?,
    /** The other side: the card a bill was paid to, the payee account of a transfer. */
    val counterAccount: AccountRef? = null,
    /** Google Pay / PhonePe / Paytm: the interface used, not where the money sat. */
    val channelApp: String? = null,
    val method: PaymentMethod = PaymentMethod.UNKNOWN,
    val kind: TransactionKind = TransactionKind.UNKNOWN,
    val merchantRaw: String? = null,
    val merchantCanonical: String? = null,
    val counterpartyVpa: String? = null,
    val reference: String? = null,
    val referenceKind: ReferenceKind = ReferenceKind.NONE,
    val balanceAfter: Money? = null,
    val availableLimit: Money? = null,
    val creditLimit: Money? = null,
    val statementAmount: Money? = null,
    val minimumDue: Money? = null,
    val dueDate: LocalDate? = null,
    val failed: Boolean = false,
    val confidence: Double = 0.5,
    val notes: List<String> = emptyList()
) {
    val currency: String get() = "INR"
}

enum class ReferenceKind { NONE, UPI_REF, UTR, RRN, TXN_ID, CHEQUE }

/**
 * The reconstructed real-world transaction: one row per thing that actually
 * happened, however many messages described it.
 */
data class CanonicalTransaction(
    val id: String,
    val account: AccountRef?,
    val amount: Money,
    val direction: Direction,
    val kind: TransactionKind,
    val status: TransactionStatus,
    val method: PaymentMethod,
    val timestamp: Instant,
    val timestampSource: TimestampSource,
    val merchant: String? = null,
    val merchantRaw: String? = null,
    val category: String? = null,
    val categoryConfidence: Double = 0.0,
    val counterAccount: AccountRef? = null,
    val channelApp: String? = null,
    val counterpartyVpa: String? = null,
    val reference: String? = null,
    val referenceKind: ReferenceKind = ReferenceKind.NONE,
    val balanceAfter: Money? = null,
    val confidence: Double = 0.5,
    val sources: List<SourceRef> = emptyList(),
    val notes: List<String> = emptyList(),
    /** Net of refunds; equals [amount] until something is refunded against it. */
    val refundedAmount: Money = Money.ZERO
) {
    /** What this transaction actually cost the user once refunds and reversals land. */
    val netImpact: Money
        get() = when {
            !status.hasFinancialImpact -> Money.ZERO
            else -> amount - refundedAmount
        }

    val isSpending: Boolean
        get() = direction == Direction.DEBIT && kind.countsAsSpending && status.hasFinancialImpact

    val isIncome: Boolean
        get() = direction == Direction.CREDIT && kind.countsAsIncome && status.hasFinancialImpact

    val isInvestment: Boolean
        get() = kind == TransactionKind.INVESTMENT && status.hasFinancialImpact

    val sourceCount: Int get() = sources.size
}

/** An edge in the transaction graph. */
data class TransactionLink(
    val fromTransactionId: String,
    val toTransactionId: String,
    val relation: LinkRelation,
    val confidence: Double = 1.0,
    val explanation: String? = null
)
