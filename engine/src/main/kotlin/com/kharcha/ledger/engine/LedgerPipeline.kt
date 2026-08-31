package com.kharcha.ledger.engine

import com.kharcha.ledger.engine.learn.RuleStore
import com.kharcha.ledger.engine.merchant.CategoryEngine
import com.kharcha.ledger.engine.model.CandidateTransaction
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Classification
import com.kharcha.ledger.engine.model.MessageClass
import com.kharcha.ledger.engine.model.RawMessage
import com.kharcha.ledger.engine.parse.ParseResult
import com.kharcha.ledger.engine.parse.TransactionParser
import com.kharcha.ledger.engine.reconcile.ReconciliationEngine
import com.kharcha.ledger.engine.reconcile.ReconciliationResult
import com.kharcha.ledger.engine.reconcile.SelfAccountRegistry

/** A message the pipeline deliberately did not book, and why. */
data class SkippedMessage(
    val messageId: String,
    val messageClass: MessageClass,
    val reason: String,
    val classification: Classification
)

data class PipelineResult(
    val reconciliation: ReconciliationResult,
    val candidates: List<CandidateTransaction>,
    val skipped: List<SkippedMessage>
) {
    val transactions: List<CanonicalTransaction> get() = reconciliation.transactions
}

/**
 * The whole offline brain, end to end:
 *
 *     messages -> classify -> parse -> reconcile -> ledger
 *
 * Deliberately synchronous and side-effect free. The Android layer owns SMS
 * access, storage and scheduling; everything that decides *what a message means*
 * lives here, where it can be tested without a device.
 */
class LedgerPipeline(
    categoryEngine: CategoryEngine = CategoryEngine(),
    private val registry: SelfAccountRegistry = SelfAccountRegistry(),
    rules: RuleStore = RuleStore.empty()
) {
    private val reconciliation = ReconciliationEngine(categoryEngine, registry, rules)

    fun process(
        messages: List<RawMessage>,
        existing: List<CanonicalTransaction> = emptyList()
    ): PipelineResult {
        val candidates = mutableListOf<CandidateTransaction>()
        val skipped = mutableListOf<SkippedMessage>()

        messages.forEach { message ->
            val result: ParseResult = TransactionParser.parse(message)
            val candidate = result.candidate
            if (candidate == null) {
                skipped += SkippedMessage(
                    messageId = message.id,
                    messageClass = result.classification.messageClass,
                    reason = result.rejectionReason ?: "not-parsed",
                    classification = result.classification
                )
            } else {
                candidates += candidate
            }
        }

        return PipelineResult(
            reconciliation = reconciliation.reconcile(existing, candidates),
            candidates = candidates,
            skipped = skipped
        )
    }

    fun selfAccounts(): SelfAccountRegistry = registry
}
