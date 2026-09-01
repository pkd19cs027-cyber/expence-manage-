package com.kharcha.ledger.data.mapper

import com.kharcha.ledger.data.db.entity.AccountEntity
import com.kharcha.ledger.data.db.entity.ProcessedMessageEntity
import com.kharcha.ledger.data.db.entity.RecurringPatternEntity
import com.kharcha.ledger.data.db.entity.ReviewItemEntity
import com.kharcha.ledger.data.db.entity.RuleEntity
import com.kharcha.ledger.data.db.entity.TransactionEntity
import com.kharcha.ledger.data.db.entity.TransactionLinkEntity
import com.kharcha.ledger.data.db.entity.TransactionSourceEntity
import com.kharcha.ledger.engine.insights.RecurringPattern
import com.kharcha.ledger.engine.learn.UserRule
import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.KnownAccount
import com.kharcha.ledger.engine.model.LinkRelation
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.PaymentMethod
import com.kharcha.ledger.engine.model.ReferenceKind
import com.kharcha.ledger.engine.model.SourceRef
import com.kharcha.ledger.engine.model.SourceType
import com.kharcha.ledger.engine.model.TimestampSource
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.model.TransactionLink
import com.kharcha.ledger.engine.model.TransactionStatus
import com.kharcha.ledger.engine.review.ReviewAction
import com.kharcha.ledger.engine.review.ReviewItem
import com.kharcha.ledger.engine.review.ReviewReason
import java.time.Instant
import java.time.LocalDate

/**
 * Translation between the engine's domain model and the database rows.
 *
 * Enum names are written as strings and read back defensively: a row written by
 * a newer build that a downgrade cannot understand degrades to a sane default
 * instead of crashing the app on launch.
 */

private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

fun CanonicalTransaction.toEntity(now: Instant = Instant.now(), userEdited: Boolean = false) = TransactionEntity(
    id = id,
    accountKey = account?.key,
    institutionId = account?.institutionId,
    last4 = account?.last4,
    accountType = account?.type?.name,
    amountPaise = amount.paise,
    direction = direction.name,
    kind = kind.name,
    status = status.name,
    method = method.name,
    timestamp = timestamp.toEpochMilli(),
    timestampSource = timestampSource.name,
    merchant = merchant,
    merchantRaw = merchantRaw,
    merchantKey = merchantRaw?.let { com.kharcha.ledger.engine.merchant.MerchantNormalizer.normalizeKey(it) },
    category = category,
    categoryConfidence = categoryConfidence,
    counterAccountKey = counterAccount?.key,
    counterInstitutionId = counterAccount?.institutionId,
    counterLast4 = counterAccount?.last4,
    counterAccountType = counterAccount?.type?.name,
    channelApp = channelApp,
    counterpartyVpa = counterpartyVpa,
    reference = reference,
    referenceKind = referenceKind.name,
    balanceAfterPaise = balanceAfter?.paise,
    confidence = confidence,
    refundedPaise = refundedAmount.paise,
    notes = notes.takeIf { it.isNotEmpty() }?.joinToString("|"),
    userEdited = userEdited,
    updatedAt = now.toEpochMilli()
)

fun TransactionEntity.toDomain(sources: List<SourceRef> = emptyList()) = CanonicalTransaction(
    id = id,
    account = if (institutionId != null && last4 != null) {
        AccountRef(institutionId, last4, accountType.toEnum(AccountType.UNKNOWN))
    } else null,
    amount = Money(amountPaise),
    direction = direction.toEnum(Direction.DEBIT),
    kind = kind.toEnum(TransactionKind.UNKNOWN),
    status = status.toEnum(TransactionStatus.DETECTED),
    method = method.toEnum(PaymentMethod.UNKNOWN),
    timestamp = Instant.ofEpochMilli(timestamp),
    timestampSource = timestampSource.toEnum(TimestampSource.MESSAGE_RECEIVED_TIME),
    merchant = merchant,
    merchantRaw = merchantRaw,
    category = category,
    categoryConfidence = categoryConfidence,
    counterAccount = if (counterInstitutionId != null && counterLast4 != null) {
        AccountRef(counterInstitutionId, counterLast4, counterAccountType.toEnum(AccountType.UNKNOWN))
    } else null,
    channelApp = channelApp,
    counterpartyVpa = counterpartyVpa,
    reference = reference,
    referenceKind = referenceKind.toEnum(ReferenceKind.NONE),
    balanceAfter = balanceAfterPaise?.let { Money(it) },
    confidence = confidence,
    sources = sources,
    notes = notes?.split("|")?.filter { it.isNotBlank() } ?: emptyList(),
    refundedAmount = Money(refundedPaise)
)

fun SourceRef.toEntity(transactionId: String) = TransactionSourceEntity(
    transactionId = transactionId,
    sourceType = sourceType.name,
    sourceId = sourceId,
    sender = sender,
    messageHash = messageHash,
    parserVersion = parserVersion,
    receivedAt = receivedAt.toEpochMilli()
)

fun TransactionSourceEntity.toDomain() = SourceRef(
    sourceType = sourceType.toEnum(SourceType.SMS),
    sourceId = sourceId,
    sender = sender,
    messageHash = messageHash,
    parserVersion = parserVersion,
    receivedAt = Instant.ofEpochMilli(receivedAt)
)

fun TransactionLink.toEntity(now: Instant = Instant.now()) = TransactionLinkEntity(
    fromTransactionId = fromTransactionId,
    toTransactionId = toTransactionId,
    relation = relation.name,
    confidence = confidence,
    explanation = explanation,
    createdAt = now.toEpochMilli()
)

fun TransactionLinkEntity.toDomain() = TransactionLink(
    fromTransactionId = fromTransactionId,
    toTransactionId = toTransactionId,
    relation = relation.toEnum(LinkRelation.DUPLICATE_OF),
    confidence = confidence,
    explanation = explanation
)

fun AccountEntity.toDomain() = KnownAccount(
    ref = AccountRef(institutionId, last4, accountType.toEnum(AccountType.UNKNOWN)),
    displayName = displayName,
    verifiedByUser = verifiedByUser,
    balance = balancePaise?.let { Money(it) },
    balanceAt = balanceAt?.let { Instant.ofEpochMilli(it) },
    creditLimit = creditLimitPaise?.let { Money(it) },
    outstanding = outstandingPaise?.let { Money(it) },
    statementDue = statementDuePaise?.let { Money(it) },
    minimumDue = minimumDuePaise?.let { Money(it) },
    dueDate = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
)

fun ReviewItem.toEntity() = ReviewItemEntity(
    id = id,
    reason = reason.name,
    transactionId = transactionId,
    relatedTransactionId = relatedTransactionId,
    summary = summary,
    detail = detail,
    actions = actions.joinToString(",") { it.name },
    signals = signals.takeIf { it.isNotEmpty() }?.joinToString(","),
    confidence = confidence,
    createdAt = createdAt.toEpochMilli()
)

fun ReviewItemEntity.toDomain() = ReviewItem(
    id = id,
    reason = reason.toEnum(ReviewReason.LOW_CONFIDENCE),
    transactionId = transactionId,
    relatedTransactionId = relatedTransactionId,
    summary = summary,
    detail = detail,
    actions = actions.split(",").filter { it.isNotBlank() }
        .mapNotNull { runCatching { ReviewAction.valueOf(it) }.getOrNull() },
    confidence = confidence,
    createdAt = Instant.ofEpochMilli(createdAt),
    signals = signals?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
)

fun RuleEntity.toDomain() = UserRule(
    id = id,
    matchKind = matchKind.toEnum(UserRule.MatchKind.MERCHANT_KEY),
    matchValue = matchValue,
    action = action.toEnum(UserRule.Action.SET_CATEGORY),
    actionValue = actionValue,
    confidence = confidence,
    createdAt = Instant.ofEpochMilli(createdAt)
)

fun UserRule.toEntity() = RuleEntity(
    id = id,
    matchKind = matchKind.name,
    matchValue = matchValue,
    action = action.name,
    actionValue = actionValue,
    confidence = confidence,
    createdAt = createdAt.toEpochMilli()
)

fun RecurringPattern.toEntity() = RecurringPatternEntity(
    id = "RP_" + merchant.uppercase().replace(" ", "_"),
    merchant = merchant,
    expectedPaise = expectedAmount.paise,
    frequencyDays = frequencyDays,
    occurrences = occurrences,
    lastSeen = lastSeen.toEpochMilli(),
    nextExpected = nextExpected.toString(),
    category = category,
    confidence = confidence
)

fun RecurringPatternEntity.toDomain() = RecurringPattern(
    merchant = merchant,
    expectedAmount = Money(expectedPaise),
    frequencyDays = frequencyDays,
    occurrences = occurrences,
    lastSeen = Instant.ofEpochMilli(lastSeen),
    nextExpected = runCatching { LocalDate.parse(nextExpected) }.getOrDefault(LocalDate.now()),
    category = category,
    confidence = confidence
)

fun processedMessage(
    source: SourceRef,
    messageClass: String,
    transactionId: String?,
    skippedReason: String?,
    now: Instant = Instant.now()
) = ProcessedMessageEntity(
    messageHash = source.messageHash,
    sourceType = source.sourceType.name,
    sourceId = source.sourceId,
    sender = source.sender,
    receivedAt = source.receivedAt.toEpochMilli(),
    processedAt = now.toEpochMilli(),
    parserVersion = source.parserVersion,
    messageClass = messageClass,
    transactionId = transactionId,
    skippedReason = skippedReason
)
