package com.kharcha.ledger.engine.reconcile

import com.kharcha.ledger.engine.learn.RuleStore
import com.kharcha.ledger.engine.merchant.CategoryEngine
import com.kharcha.ledger.engine.merchant.MerchantNormalizer
import com.kharcha.ledger.engine.model.CandidateTransaction
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.LinkRelation
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.model.TransactionLink
import com.kharcha.ledger.engine.model.TransactionStatus
import com.kharcha.ledger.engine.review.ReviewAction
import com.kharcha.ledger.engine.review.ReviewItem
import com.kharcha.ledger.engine.review.ReviewReason
import java.security.MessageDigest
import kotlin.math.abs

data class ReconciliationResult(
    /** The whole working set after reconciliation, ready to be persisted. */
    val transactions: List<CanonicalTransaction>,
    val created: List<CanonicalTransaction>,
    val updated: List<CanonicalTransaction>,
    val links: List<TransactionLink>,
    val reviewItems: List<ReviewItem>,
    val discoveredAccounts: List<AccountObservation>,
    /** Sources folded into an existing transaction rather than booked separately. */
    val mergedSourceIds: List<String>
)

/**
 * Turns a pile of candidate observations into the ledger.
 *
 * This is the piece the whole product rests on. The rule it enforces is:
 *
 *     many messages -> financial events -> reconciliation -> one transaction
 *
 * rather than "one SMS = one expense". Duplicates, self-transfers, credit-card
 * bill payments, ATM withdrawals, failures, reversals and refunds are all just
 * consequences of getting that one rule right.
 */
class ReconciliationEngine(
    private val categoryEngine: CategoryEngine = CategoryEngine(),
    private val registry: SelfAccountRegistry = SelfAccountRegistry(),
    private val rules: RuleStore = RuleStore.empty()
) {

    /** Maps one candidate to a provisional transaction, before any matching. */
    fun toCanonical(candidate: CandidateTransaction): CanonicalTransaction {
        val merchantKey = candidate.merchantRaw?.let { MerchantNormalizer.normalizeKey(it) }
        val userMerchant = merchantKey?.let { rules.merchantFor(it) }
        val kind = merchantKey?.let { rules.kindFor(it) } ?: candidate.kind
        val category = categoryEngine.categorize(
            merchantKey = merchantKey,
            merchantCanonical = userMerchant ?: candidate.merchantCanonical,
            kind = kind,
            messageClass = candidate.messageClass,
            descriptor = candidate.merchantRaw
        )

        val status = when {
            candidate.failed -> TransactionStatus.FAILED
            candidate.confidence >= 0.8 && candidate.account != null -> TransactionStatus.CONFIRMED
            else -> TransactionStatus.DETECTED
        }

        return CanonicalTransaction(
            id = idFor(candidate),
            account = candidate.account,
            amount = candidate.amount,
            direction = candidate.direction,
            kind = kind,
            status = status,
            method = candidate.method,
            timestamp = candidate.timestamp,
            timestampSource = candidate.timestampSource,
            merchant = userMerchant ?: candidate.merchantCanonical,
            merchantRaw = candidate.merchantRaw,
            category = category.category,
            categoryConfidence = category.confidence,
            counterAccount = candidate.counterAccount,
            channelApp = candidate.channelApp,
            counterpartyVpa = candidate.counterpartyVpa,
            reference = candidate.reference,
            referenceKind = candidate.referenceKind,
            balanceAfter = candidate.balanceAfter,
            confidence = candidate.confidence,
            sources = listOf(candidate.source),
            notes = candidate.notes
        )
    }

    fun reconcile(
        existing: List<CanonicalTransaction>,
        candidates: List<CandidateTransaction>
    ): ReconciliationResult {
        val working = existing.associateBy { it.id }.toMutableMap()
        val originalSnapshot = existing.associate { it.id to it }
        val links = mutableListOf<TransactionLink>()
        val reviews = mutableListOf<ReviewItem>()
        val merged = mutableListOf<String>()
        val createdIds = mutableSetOf<String>()

        // 1. Duplicate resolution, oldest first so the earliest report becomes
        //    the canonical row and later corroborations fold into it.
        for (candidate in candidates.sortedBy { it.timestamp }) {
            val provisional = toCanonical(candidate)
            if (working.values.any { tx -> tx.sources.any { it.sourceId == candidate.source.sourceId && it.sourceType == candidate.source.sourceType } }) {
                merged += candidate.source.sourceId
                continue
            }

            val best = working.values
                .asSequence()
                .filter { plausiblePair(it, provisional) }
                .map { it to DuplicateDetector.score(it, provisional) }
                .maxByOrNull { it.second.score }

            when (best?.second?.verdict) {
                DuplicateVerdict.MERGE -> {
                    val mergedTx = TransactionMerger.merge(best.first, provisional)
                    working[mergedTx.id] = mergedTx
                    merged += candidate.source.sourceId
                }

                DuplicateVerdict.REVIEW -> {
                    working[provisional.id] = provisional
                    createdIds += provisional.id
                    links += TransactionLink(
                        fromTransactionId = provisional.id,
                        toTransactionId = best.first.id,
                        relation = LinkRelation.DUPLICATE_OF,
                        confidence = best.second.probability,
                        explanation = "suspected, not merged"
                    )
                    reviews += ReviewItem(
                        id = reviewId("dup", provisional.id, best.first.id),
                        reason = ReviewReason.POSSIBLE_DUPLICATE,
                        transactionId = provisional.id,
                        relatedTransactionId = best.first.id,
                        summary = "${provisional.amount.format()} ${provisional.merchant ?: "payment"} may already be recorded",
                        detail = "Match score ${best.second.score}",
                        actions = listOf(ReviewAction.MERGE, ReviewAction.KEEP_SEPARATE),
                        confidence = best.second.probability,
                        createdAt = provisional.timestamp,
                        signals = best.second.signals
                    )
                }

                else -> {
                    working[provisional.id] = provisional
                    createdIds += provisional.id
                }
            }
        }

        // 2. Relationships, most specific first: a credit landing on a card is a
        //    bill payment before it is ever a transfer.
        val claimed = mutableSetOf<String>()

        CardPaymentDetector.detect(working.values.toList(), registry).forEach { pair ->
            working[pair.debit.id] = working.getValue(pair.debit.id)
                .copy(kind = TransactionKind.CARD_PAYMENT, counterAccount = pair.credit.account)
            working[pair.credit.id] = working.getValue(pair.credit.id)
                .copy(kind = TransactionKind.CARD_PAYMENT)
            claimed += pair.debit.id
            claimed += pair.credit.id
            links += TransactionLink(
                pair.debit.id, pair.credit.id, LinkRelation.CARD_PAYMENT_FOR, pair.confidence,
                "credit-card bill payment"
            )
        }

        ReversalDetector.detect(working.values.toList()).forEach { pair ->
            if (pair.debit.id in claimed) return@forEach
            working[pair.debit.id] = working.getValue(pair.debit.id).copy(status = TransactionStatus.REVERSED)
            working[pair.credit.id] = working.getValue(pair.credit.id).copy(kind = TransactionKind.REVERSAL)
            claimed += pair.debit.id
            claimed += pair.credit.id
            links += TransactionLink(
                pair.credit.id, pair.debit.id, LinkRelation.REVERSAL_OF, pair.confidence,
                "payment reversed, net impact zero"
            )
        }

        RefundDetector.detect(working.values.toList()).forEach { pair ->
            if (pair.credit.id in claimed) return@forEach
            val original = working.getValue(pair.debit.id)
            val refunded = original.refundedAmount + pair.credit.amount
            val status = if (refunded >= original.amount) TransactionStatus.REFUNDED
            else TransactionStatus.PARTIALLY_REFUNDED
            working[original.id] = original.copy(
                refundedAmount = minOf(refunded, original.amount),
                status = status
            )
            claimed += pair.credit.id
            links += TransactionLink(
                pair.credit.id, original.id, LinkRelation.REFUND_OF, pair.confidence,
                "refund of ${pair.credit.amount.format()}"
            )
            if (pair.confidence < 0.8) {
                reviews += ReviewItem(
                    id = reviewId("refund", pair.credit.id, original.id),
                    reason = ReviewReason.POSSIBLE_REFUND,
                    transactionId = pair.credit.id,
                    relatedTransactionId = original.id,
                    summary = "Is this a refund for ${original.merchant ?: original.amount.format()}?",
                    actions = listOf(ReviewAction.KEEP_SEPARATE, ReviewAction.MARK_INCOME),
                    confidence = pair.confidence,
                    createdAt = pair.credit.timestamp
                )
            }
        }

        TransferDetector.detect(working.values.toList(), registry).forEach { pair ->
            if (pair.debit.id in claimed || pair.credit.id in claimed) return@forEach
            if (pair.confidence >= 0.8) {
                working[pair.debit.id] = working.getValue(pair.debit.id)
                    .copy(kind = TransactionKind.TRANSFER, status = TransactionStatus.TRANSFER, counterAccount = pair.credit.account)
                working[pair.credit.id] = working.getValue(pair.credit.id)
                    .copy(kind = TransactionKind.TRANSFER, status = TransactionStatus.TRANSFER, counterAccount = pair.debit.account)
                links += TransactionLink(
                    pair.debit.id, pair.credit.id, LinkRelation.TRANSFER_PAIR, pair.confidence,
                    "between your own accounts"
                )
                claimed += pair.debit.id
                claimed += pair.credit.id
            } else {
                reviews += ReviewItem(
                    id = reviewId("transfer", pair.debit.id, pair.credit.id),
                    reason = ReviewReason.POSSIBLE_TRANSFER,
                    transactionId = pair.debit.id,
                    relatedTransactionId = pair.credit.id,
                    summary = "${pair.debit.amount.format()} moved between your accounts?",
                    actions = listOf(ReviewAction.MARK_TRANSFER, ReviewAction.KEEP_SEPARATE),
                    confidence = pair.confidence,
                    createdAt = pair.debit.timestamp
                )
            }
        }

        // 3. Everything still uncertain becomes a review card.
        working.values.filter { it.id in createdIds }.forEach { tx ->
            reviews += reviewsFor(tx)
        }

        val finalSet = working.values.sortedByDescending { it.timestamp }
        val created = finalSet.filter { it.id in createdIds }
        val updated = finalSet.filter { tx ->
            val before = originalSnapshot[tx.id]
            before != null && before != tx
        }

        return ReconciliationResult(
            transactions = finalSet,
            created = created,
            updated = updated,
            links = links,
            reviewItems = reviews,
            discoveredAccounts = SelfAccountRegistry.discover(candidates)
                .filter { !registry.isSelf(it.ref) && !registry.isRejected(it.ref) && it.looksLikeUserAccount },
            mergedSourceIds = merged
        )
    }

    /** Cheap pre-filter so scoring only runs on pairs that could possibly match. */
    private fun plausiblePair(existing: CanonicalTransaction, incoming: CanonicalTransaction): Boolean {
        if (existing.amount != incoming.amount) return false
        if (existing.direction != incoming.direction) return false
        val gap = abs(existing.timestamp.epochSecond - incoming.timestamp.epochSecond)
        if (gap > 86_400L) return false
        val sharedFingerprint = Fingerprints.of(existing).intersect(Fingerprints.of(incoming)).isNotEmpty()
        return sharedFingerprint || gap <= 7_200L
    }

    private fun reviewsFor(tx: CanonicalTransaction): List<ReviewItem> {
        val items = mutableListOf<ReviewItem>()
        if (tx.status == TransactionStatus.FAILED) return items

        if (tx.account == null) {
            items += ReviewItem(
                id = reviewId("account", tx.id),
                reason = ReviewReason.UNKNOWN_ACCOUNT,
                transactionId = tx.id,
                summary = "Which account paid ${tx.amount.format()}?",
                detail = tx.channelApp?.let { "Paid via $it" },
                actions = listOf(ReviewAction.CONFIRM_ACCOUNT, ReviewAction.IGNORE),
                confidence = tx.confidence,
                createdAt = tx.timestamp
            )
        }
        if (tx.merchant == null && tx.direction == Direction.DEBIT && tx.kind == TransactionKind.EXPENSE) {
            items += ReviewItem(
                id = reviewId("merchant", tx.id),
                reason = ReviewReason.UNKNOWN_MERCHANT,
                transactionId = tx.id,
                summary = "Who was ${tx.amount.format()} paid to?",
                actions = listOf(ReviewAction.SET_MERCHANT, ReviewAction.SET_CATEGORY, ReviewAction.IGNORE),
                confidence = tx.confidence,
                createdAt = tx.timestamp
            )
        } else if (tx.categoryConfidence < 0.55 && tx.direction == Direction.DEBIT) {
            items += ReviewItem(
                id = reviewId("category", tx.id),
                reason = ReviewReason.UNCERTAIN_CATEGORY,
                transactionId = tx.id,
                summary = "Category for ${tx.merchant ?: tx.amount.format()}?",
                actions = listOf(ReviewAction.SET_CATEGORY, ReviewAction.IGNORE),
                confidence = tx.categoryConfidence,
                createdAt = tx.timestamp
            )
        }
        if (tx.direction == Direction.CREDIT && tx.kind == TransactionKind.INCOME &&
            tx.amount >= Money.ofRupees(15_000L)
        ) {
            items += ReviewItem(
                id = reviewId("salary", tx.id),
                reason = ReviewReason.SALARY_CONFIRMATION,
                transactionId = tx.id,
                summary = "Was ${tx.amount.format()} your salary?",
                actions = listOf(ReviewAction.MARK_INCOME, ReviewAction.MARK_TRANSFER, ReviewAction.IGNORE),
                confidence = 0.5,
                createdAt = tx.timestamp
            )
        }
        if (tx.confidence < 0.45) {
            items += ReviewItem(
                id = reviewId("confidence", tx.id),
                reason = ReviewReason.LOW_CONFIDENCE,
                transactionId = tx.id,
                summary = "Check ${tx.amount.format()} on ${tx.timestamp}",
                actions = listOf(ReviewAction.MARK_EXPENSE, ReviewAction.IGNORE),
                confidence = tx.confidence,
                createdAt = tx.timestamp
            )
        }
        return items
    }

    private fun idFor(candidate: CandidateTransaction): String {
        val seed = "${candidate.source.sourceType}:${candidate.source.sourceId}:${candidate.source.messageHash}"
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return "T_" + digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    private fun reviewId(kind: String, vararg parts: String) = "R_${kind}_" + parts.joinToString("_") { it.takeLast(8) }
}
