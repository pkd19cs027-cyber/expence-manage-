package com.kharcha.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "merchants", indices = [Index(value = ["canonicalName"], unique = true)])
data class MerchantEntity(
    @PrimaryKey val id: String,
    val canonicalName: String,
    val category: String,
    val createdByUser: Boolean = false
)

@Entity(tableName = "merchant_aliases", indices = [Index("merchantId")])
data class MerchantAliasEntity(
    @PrimaryKey val alias: String,
    val merchantId: String,
    val createdByUser: Boolean = false
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val name: String,
    val isCustom: Boolean = false,
    val sortOrder: Int = 0
)

/** A correction the user made once, applied automatically from then on. */
@Entity(tableName = "rules", indices = [Index("matchValue")])
data class RuleEntity(
    @PrimaryKey val id: String,
    val matchKind: String,
    val matchValue: String,
    val action: String,
    val actionValue: String,
    val confidence: Double = 1.0,
    val createdAt: Long
)

@Entity(tableName = "recurring_patterns", indices = [Index("merchant")])
data class RecurringPatternEntity(
    @PrimaryKey val id: String,
    val merchant: String,
    val expectedPaise: Long,
    val frequencyDays: Int,
    val occurrences: Int,
    val lastSeen: Long,
    val nextExpected: String,
    val category: String? = null,
    val confidence: Double
)

@Entity(tableName = "budgets", indices = [Index(value = ["category", "period"], unique = true)])
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    /** `MONTHLY` today; the column exists so weekly budgets need no migration. */
    val period: String = "MONTHLY",
    val amountPaise: Long
)

@Entity(
    tableName = "review_items",
    indices = [Index("transactionId"), Index("resolvedAt")]
)
data class ReviewItemEntity(
    @PrimaryKey val id: String,
    val reason: String,
    val transactionId: String,
    val relatedTransactionId: String? = null,
    val summary: String,
    val detail: String? = null,
    val actions: String,
    val signals: String? = null,
    val confidence: Double,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    val resolvedAction: String? = null
)

/**
 * Every message the app has ever looked at, by hash.
 *
 * This is what makes ingestion idempotent, what lets the parser be re-run over
 * old messages when it improves ([parserVersion]), and what lets the app show
 * "these 214 messages were read and deliberately ignored" without keeping a
 * single word of their text.
 */
@Entity(
    tableName = "processed_messages",
    indices = [
        Index(value = ["sourceType", "sourceId"], unique = true),
        Index("parserVersion"),
        Index("messageClass")
    ]
)
data class ProcessedMessageEntity(
    @PrimaryKey val messageHash: String,
    val sourceType: String,
    val sourceId: String,
    val sender: String,
    val receivedAt: Long,
    val processedAt: Long,
    val parserVersion: Int,
    val messageClass: String,
    val transactionId: String? = null,
    val skippedReason: String? = null
)

/**
 * Original message text, stored only when the user turns retention on in
 * Settings. Default is off: the app keeps the hash and the extracted fields,
 * and drops the words.
 */
@Entity(tableName = "raw_messages")
data class RawMessageEntity(
    @PrimaryKey val messageHash: String,
    val sourceType: String,
    val sourceId: String,
    val sender: String,
    val body: String,
    val receivedAt: Long
)
