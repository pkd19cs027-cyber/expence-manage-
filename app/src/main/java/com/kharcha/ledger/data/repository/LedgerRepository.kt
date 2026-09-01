package com.kharcha.ledger.data.repository

import com.kharcha.ledger.data.db.LedgerDatabase
import com.kharcha.ledger.data.db.entity.AccountEntity
import com.kharcha.ledger.data.db.entity.RawMessageEntity
import com.kharcha.ledger.data.mapper.processedMessage
import com.kharcha.ledger.data.mapper.toDomain
import com.kharcha.ledger.data.mapper.toEntity
import com.kharcha.ledger.data.prefs.SettingsRepository
import com.kharcha.ledger.data.sms.SmsInboxReader
import com.kharcha.ledger.engine.LedgerPipeline
import com.kharcha.ledger.engine.PipelineResult
import com.kharcha.ledger.engine.insights.InsightsEngine
import com.kharcha.ledger.engine.learn.InMemoryRuleStore
import com.kharcha.ledger.engine.learn.UserRule
import com.kharcha.ledger.engine.merchant.CategoryEngine
import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.RawMessage
import com.kharcha.ledger.engine.model.SourceType
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.model.TransactionStatus
import com.kharcha.ledger.engine.parse.TransactionParser
import com.kharcha.ledger.engine.reconcile.SelfAccountRegistry
import com.kharcha.ledger.engine.review.ReviewAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class IngestSummary(
    val messagesRead: Int,
    val newTransactions: Int,
    val mergedIntoExisting: Int,
    val skipped: Int,
    val reviewItems: Int,
    val accountsDiscovered: Int
)

/**
 * The bridge between the pure engine and the encrypted database.
 *
 * Everything the engine needs — the user's confirmed accounts, their learned
 * rules, the transactions near in time to the new ones — is loaded here, handed
 * over, and the result written back atomically. The engine itself never touches
 * storage, which is what keeps it testable.
 */
@Singleton
class LedgerRepository @Inject constructor(
    private val db: LedgerDatabase,
    private val inbox: SmsInboxReader,
    private val settings: SettingsRepository,
    private val insights: InsightsEngine
) {

    /** How far back reconciliation looks for duplicates, refunds and transfers. */
    private val reconciliationWindowDays = 120L

    fun observeTransactions(limit: Int = 500): Flow<List<CanonicalTransaction>> =
        db.transactionDao().observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    fun observeTransactionsBetween(from: Instant, to: Instant): Flow<List<CanonicalTransaction>> =
        db.transactionDao().observeBetween(from.toEpochMilli(), to.toEpochMilli())
            .map { rows -> rows.map { it.toDomain() } }

    fun observeTransaction(id: String): Flow<CanonicalTransaction?> =
        combine(
            db.transactionDao().observeById(id),
            db.sourceDao().observeFor(id)
        ) { entity, sources ->
            entity?.toDomain(sources.map { it.toDomain() })
        }

    fun observeLinks(id: String) = db.linkDao().observeFor(id).map { rows -> rows.map { it.toDomain() } }

    fun observeAccounts() = db.accountDao().observeAll()

    fun observeUnverifiedAccounts() = db.accountDao().observeUnverified()

    fun observeOpenReviews() = db.reviewDao().observeOpen().map { rows -> rows.map { it.toDomain() } }

    fun observeOpenReviewCount(): Flow<Int> = db.reviewDao().observeOpenCount()

    fun observeMessageStats(): Flow<Pair<Int, Int>> =
        combine(db.messageDao().observeCount(), db.messageDao().observeIgnoredCount()) { total, ignored ->
            total to ignored
        }

    fun observeRecurring() = db.recurringDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeRules() = db.ruleDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeBudgets() = db.budgetDao().observeAll()

    /** Reads only what has arrived since the last import. */
    suspend fun importIncremental(): IngestSummary {
        val last = db.messageDao().lastProcessedAt()?.let { Instant.ofEpochMilli(it) }
        // A small overlap absorbs clock skew between the provider and the phone.
        val since = last?.minus(2, ChronoUnit.HOURS)
        return ingest(inbox.read(since = since))
    }

    /** The first-run import of the whole inbox. */
    suspend fun importFullInbox(limit: Int = 5_000): IngestSummary = ingest(inbox.read(limit = limit))

    suspend fun ingest(messages: List<RawMessage>): IngestSummary {
        if (messages.isEmpty()) return IngestSummary(0, 0, 0, 0, 0, 0)

        val alreadySeen = db.messageDao().processedIds(SourceType.SMS.name).toSet()
        val fresh = messages.filter { it.id !in alreadySeen }
        if (fresh.isEmpty()) return IngestSummary(messages.size, 0, 0, 0, 0, 0)

        val pipeline = LedgerPipeline(
            categoryEngine = CategoryEngine(loadRules()),
            registry = loadRegistry(),
            rules = loadRules()
        )

        val windowStart = Instant.now().minus(reconciliationWindowDays, ChronoUnit.DAYS)
        val existing = db.transactionDao().since(windowStart.toEpochMilli()).map { entity ->
            entity.toDomain(db.sourceDao().forTransaction(entity.id).map { it.toDomain() })
        }

        val result = pipeline.process(fresh, existing)
        persist(result, fresh)
        settings.setLastImportAt(Instant.now())

        return IngestSummary(
            messagesRead = fresh.size,
            newTransactions = result.reconciliation.created.size,
            mergedIntoExisting = result.reconciliation.mergedSourceIds.size,
            skipped = result.skipped.size,
            reviewItems = result.reconciliation.reviewItems.size,
            accountsDiscovered = result.reconciliation.discoveredAccounts.size
        )
    }

    private suspend fun persist(result: PipelineResult, messages: List<RawMessage>) {
        val now = Instant.now()
        val reconciliation = result.reconciliation
        val touched = (reconciliation.created + reconciliation.updated).distinctBy { it.id }

        db.transactionDao().upsert(touched.map { it.toEntity(now) })
        db.sourceDao().insert(touched.flatMap { tx -> tx.sources.map { it.toEntity(tx.id) } })
        db.linkDao().insert(reconciliation.links.map { it.toEntity(now) })
        db.reviewDao().upsert(reconciliation.reviewItems.map { it.toEntity() })

        // Every message is recorded as looked-at, including the ones deliberately
        // ignored, so the app can always account for what it did with an SMS.
        val transactionBySource = touched
            .flatMap { tx -> tx.sources.map { it.sourceId to tx.id } }
            .toMap()
        val processed = result.candidates.map { candidate ->
            processedMessage(
                source = candidate.source,
                messageClass = candidate.messageClass.name,
                transactionId = transactionBySource[candidate.source.sourceId],
                skippedReason = null,
                now = now
            )
        } + result.skipped.mapNotNull { skipped ->
            val message = messages.firstOrNull { it.id == skipped.messageId } ?: return@mapNotNull null
            processedMessage(
                source = com.kharcha.ledger.engine.model.SourceRef(
                    sourceType = message.sourceType,
                    sourceId = message.id,
                    sender = message.sender,
                    messageHash = TransactionParser.hash(message.body),
                    parserVersion = com.kharcha.ledger.engine.parse.PARSER_VERSION,
                    receivedAt = message.receivedAt
                ),
                messageClass = skipped.messageClass.name,
                transactionId = null,
                skippedReason = skipped.reason,
                now = now
            )
        }
        db.messageDao().upsert(processed)

        if (currentKeepRawSetting()) {
            db.messageDao().keepRaw(
                messages.map {
                    RawMessageEntity(
                        messageHash = TransactionParser.hash(it.body),
                        sourceType = it.sourceType.name,
                        sourceId = it.id,
                        sender = it.sender,
                        body = it.body,
                        receivedAt = it.receivedAt.toEpochMilli()
                    )
                }
            )
        }

        upsertAccounts(result, now)
        refreshRecurring()
    }

    /**
     * Accounts are recorded as *observations* first. They only become "yours",
     * and therefore transfer-eligible, once the user says so on the accounts
     * screen.
     */
    private suspend fun upsertAccounts(result: PipelineResult, now: Instant) {
        val observations = SelfAccountRegistry.discover(result.candidates)
        observations.forEach { observation ->
            val existing = db.accountDao().byKey(observation.ref.key)
            val balanceCandidate = result.candidates
                .filter { it.account == observation.ref && it.balanceAfter != null }
                .maxByOrNull { it.timestamp }

            db.accountDao().upsert(
                AccountEntity(
                    accountKey = observation.ref.key,
                    institutionId = observation.ref.institutionId,
                    last4 = observation.ref.last4,
                    accountType = observation.ref.type.name,
                    displayName = existing?.displayName ?: observation.suggestedName,
                    verifiedByUser = existing?.verifiedByUser ?: false,
                    hiddenByUser = existing?.hiddenByUser ?: false,
                    balancePaise = balanceCandidate?.balanceAfter?.paise ?: existing?.balancePaise,
                    balanceAt = balanceCandidate?.timestamp?.toEpochMilli() ?: existing?.balanceAt,
                    creditLimitPaise = result.candidates.firstNotNullOfOrNull {
                        if (it.account == observation.ref) it.creditLimit?.paise else null
                    } ?: existing?.creditLimitPaise,
                    availableLimitPaise = result.candidates.firstNotNullOfOrNull {
                        if (it.account == observation.ref) it.availableLimit?.paise else null
                    } ?: existing?.availableLimitPaise,
                    outstandingPaise = existing?.outstandingPaise,
                    statementDuePaise = result.candidates.firstNotNullOfOrNull {
                        if (it.account == observation.ref) it.statementAmount?.paise else null
                    } ?: existing?.statementDuePaise,
                    minimumDuePaise = result.candidates.firstNotNullOfOrNull {
                        if (it.account == observation.ref) it.minimumDue?.paise else null
                    } ?: existing?.minimumDuePaise,
                    dueDate = result.candidates.firstNotNullOfOrNull {
                        if (it.account == observation.ref) it.dueDate?.toString() else null
                    } ?: existing?.dueDate,
                    firstSeenAt = existing?.firstSeenAt ?: observation.firstSeen.toEpochMilli(),
                    lastSeenAt = observation.lastSeen.toEpochMilli(),
                    occurrences = (existing?.occurrences ?: 0) + observation.occurrences
                )
            )
        }
    }

    suspend fun confirmAccount(accountKey: String, isMine: Boolean) {
        db.accountDao().setVerification(accountKey, verified = isMine, hidden = !isMine)
        // Confirming an account can turn what looked like income into a transfer,
        // so the affected window is reconciled again.
        reprocessRecent()
    }

    suspend fun renameAccount(accountKey: String, name: String) = db.accountDao().rename(accountKey, name)

    /** Applies a decision from the review queue and, where useful, remembers it. */
    suspend fun resolveReview(reviewId: String, action: ReviewAction, value: String? = null) {
        val item = db.reviewDao().byId(reviewId) ?: return
        val transaction = db.transactionDao().byId(item.transactionId) ?: return
        val now = Instant.now()

        when (action) {
            ReviewAction.MERGE -> item.relatedTransactionId?.let { mergeTransactions(it, item.transactionId) }

            ReviewAction.KEEP_SEPARATE -> item.relatedTransactionId?.let {
                db.linkDao().delete(item.transactionId, it, "DUPLICATE_OF")
            }

            ReviewAction.MARK_TRANSFER -> {
                db.transactionDao().upsert(
                    transaction.copy(
                        kind = TransactionKind.TRANSFER.name,
                        status = TransactionStatus.TRANSFER.name,
                        userEdited = true,
                        updatedAt = now.toEpochMilli()
                    )
                )
                item.relatedTransactionId?.let { relatedId ->
                    db.transactionDao().byId(relatedId)?.let { related ->
                        db.transactionDao().upsert(
                            related.copy(
                                kind = TransactionKind.TRANSFER.name,
                                status = TransactionStatus.TRANSFER.name,
                                userEdited = true,
                                updatedAt = now.toEpochMilli()
                            )
                        )
                    }
                }
            }

            ReviewAction.MARK_EXPENSE -> setKind(item.transactionId, TransactionKind.EXPENSE)
            ReviewAction.MARK_INVESTMENT -> setKind(item.transactionId, TransactionKind.INVESTMENT)
            ReviewAction.MARK_INCOME -> setKind(item.transactionId, TransactionKind.SALARY)

            ReviewAction.SET_CATEGORY -> value?.let { category ->
                db.transactionDao().upsert(
                    transaction.copy(
                        category = category,
                        categoryConfidence = 1.0,
                        userEdited = true,
                        updatedAt = now.toEpochMilli()
                    )
                )
                learnCategory(transaction.merchantKey, category)
            }

            ReviewAction.SET_MERCHANT -> value?.let { merchant ->
                db.transactionDao().upsert(
                    transaction.copy(merchant = merchant, userEdited = true, updatedAt = now.toEpochMilli())
                )
                transaction.merchantKey?.let { key -> learn(UserRule.MatchKind.MERCHANT_KEY, key, UserRule.Action.SET_MERCHANT, merchant) }
            }

            ReviewAction.CONFIRM_ACCOUNT -> value?.let { accountKey ->
                db.accountDao().byKey(accountKey)?.let { account ->
                    db.transactionDao().upsert(
                        transaction.copy(
                            accountKey = account.accountKey,
                            institutionId = account.institutionId,
                            last4 = account.last4,
                            accountType = account.accountType,
                            userEdited = true,
                            updatedAt = now.toEpochMilli()
                        )
                    )
                }
            }

            ReviewAction.IGNORE -> db.transactionDao().upsert(
                transaction.copy(status = TransactionStatus.IGNORED.name, userEdited = true, updatedAt = now.toEpochMilli())
            )
        }

        db.reviewDao().resolve(reviewId, action.name, now.toEpochMilli())
    }

    /** Folds `duplicateId` into `keepId`, moving its evidence rather than deleting it. */
    suspend fun mergeTransactions(keepId: String, duplicateId: String) {
        if (keepId == duplicateId) return
        db.sourceDao().reassign(duplicateId, keepId)
        db.reviewDao().resolveAllFor(duplicateId, Instant.now().toEpochMilli())
        db.transactionDao().delete(duplicateId)
    }

    suspend fun setCategory(transactionId: String, category: String, remember: Boolean = true) {
        val transaction = db.transactionDao().byId(transactionId) ?: return
        db.transactionDao().upsert(
            transaction.copy(
                category = category,
                categoryConfidence = 1.0,
                userEdited = true,
                updatedAt = Instant.now().toEpochMilli()
            )
        )
        if (remember) learnCategory(transaction.merchantKey, category)
    }

    private suspend fun setKind(transactionId: String, kind: TransactionKind) {
        val transaction = db.transactionDao().byId(transactionId) ?: return
        db.transactionDao().upsert(
            transaction.copy(kind = kind.name, userEdited = true, updatedAt = Instant.now().toEpochMilli())
        )
    }

    private suspend fun learnCategory(merchantKey: String?, category: String) {
        val key = merchantKey ?: return
        learn(UserRule.MatchKind.MERCHANT_KEY, key, UserRule.Action.SET_CATEGORY, category)
    }

    private suspend fun learn(
        matchKind: UserRule.MatchKind,
        matchValue: String,
        action: UserRule.Action,
        actionValue: String
    ) {
        db.ruleDao().upsert(
            UserRule(
                id = UUID.randomUUID().toString(),
                matchKind = matchKind,
                matchValue = matchValue,
                action = action,
                actionValue = actionValue
            ).toEntity()
        )
    }

    /**
     * Re-runs reconciliation over the recent window without re-reading any SMS.
     * Used after the user confirms an account or changes a rule, and after a
     * parser upgrade.
     */
    suspend fun reprocessRecent() {
        val windowStart = Instant.now().minus(reconciliationWindowDays, ChronoUnit.DAYS)
        val existing = db.transactionDao().since(windowStart.toEpochMilli()).map { entity ->
            entity.toDomain(db.sourceDao().forTransaction(entity.id).map { it.toDomain() })
        }
        if (existing.isEmpty()) return

        val engine = com.kharcha.ledger.engine.reconcile.ReconciliationEngine(
            categoryEngine = CategoryEngine(loadRules()),
            registry = loadRegistry(),
            rules = loadRules()
        )
        val result = engine.reconcile(existing, emptyList())
        val now = Instant.now()
        if (result.updated.isNotEmpty()) {
            db.transactionDao().upsert(result.updated.map { it.toEntity(now) })
        }
        db.linkDao().insert(result.links.map { it.toEntity(now) })
        db.reviewDao().upsert(result.reviewItems.map { it.toEntity() })
        refreshRecurring()
    }

    /**
     * Re-runs an improved parser over messages already on the phone.
     *
     * This is why every row records the parser version that produced it: when
     * the SBI rules get better, last year's "Merchant: Unknown" can become
     * "Indian Oil" locally, with no cloud copy of anyone's SMS. Transactions the
     * user has edited, or that several messages corroborate, are left alone.
     */
    suspend fun reprocessOutdated(limit: Int = 2_000): IngestSummary {
        val currentVersion = com.kharcha.ledger.engine.parse.PARSER_VERSION
        val outdated = db.messageDao().olderThanParser(currentVersion, limit)
        if (outdated.isEmpty()) return IngestSummary(0, 0, 0, 0, 0, 0)

        val rebuildable = mutableListOf<com.kharcha.ledger.data.db.entity.ProcessedMessageEntity>()
        for (message in outdated) {
            val transactionId = message.transactionId
            if (transactionId == null) {
                rebuildable += message
                continue
            }
            val transaction = db.transactionDao().byId(transactionId) ?: continue
            if (transaction.userEdited) continue
            val sources = db.sourceDao().forTransaction(transactionId)
            if (sources.any { it.parserVersion >= currentVersion }) continue
            rebuildable += message
        }
        if (rebuildable.isEmpty()) return IngestSummary(0, 0, 0, 0, 0, 0)

        rebuildable.mapNotNull { it.transactionId }.distinct().forEach { db.transactionDao().delete(it) }
        db.messageDao().deleteProcessed(rebuildable.map { it.messageHash })

        val ids = rebuildable.map { it.sourceId }.toSet()
        val messages = inbox.read(limit = limit).filter { it.id in ids }
        return ingest(messages)
    }

    private suspend fun refreshRecurring() {
        val transactions = db.transactionDao().all().map { it.toDomain() }
        val patterns = insights.recurring(transactions, Instant.now())
        db.recurringDao().replaceAll(patterns.map { it.toEntity() })
    }

    private suspend fun loadRegistry(): SelfAccountRegistry =
        SelfAccountRegistry(db.accountDao().verified().map { it.accountKey }.toSet())

    private suspend fun loadRules(): InMemoryRuleStore =
        InMemoryRuleStore(db.ruleDao().all().map { it.toDomain() })

    private suspend fun currentKeepRawSetting(): Boolean =
        settings.settings.first().keepRawMessages

    suspend fun purgeRawMessages() = db.messageDao().purgeRaw()

    suspend fun accountRefFor(key: String): AccountRef? =
        db.accountDao().byKey(key)?.toDomain()?.ref
}
