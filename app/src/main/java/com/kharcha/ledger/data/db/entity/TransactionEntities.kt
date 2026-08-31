package com.kharcha.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One reconstructed real-world transaction.
 *
 * Enums are stored as strings rather than ordinals so a reordering of a Kotlin
 * enum can never silently reinterpret a user's history, and so the database is
 * readable when someone inspects an export.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index("timestamp"),
        Index("accountKey"),
        Index("merchantKey"),
        Index("category"),
        Index("reference"),
        Index("status")
    ]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountKey: String? = null,
    val institutionId: String? = null,
    val last4: String? = null,
    val accountType: String? = null,
    val amountPaise: Long,
    val currency: String = "INR",
    val direction: String,
    val kind: String,
    val status: String,
    val method: String,
    val timestamp: Long,
    val timestampSource: String,
    val merchant: String? = null,
    val merchantRaw: String? = null,
    val merchantKey: String? = null,
    val category: String? = null,
    val categoryConfidence: Double = 0.0,
    val counterAccountKey: String? = null,
    val counterInstitutionId: String? = null,
    val counterLast4: String? = null,
    val counterAccountType: String? = null,
    val channelApp: String? = null,
    val counterpartyVpa: String? = null,
    val reference: String? = null,
    val referenceKind: String,
    val balanceAfterPaise: Long? = null,
    val confidence: Double,
    val refundedPaise: Long = 0,
    val notes: String? = null,
    /** Set when the user edits a field, so re-parsing never overwrites them. */
    val userEdited: Boolean = false,
    val updatedAt: Long
)

/**
 * The evidence trail. A merged transaction keeps every message that reported it,
 * which is what lets the app say "confirmed from 2 messages" and lets a bad
 * merge be undone from facts rather than guesses.
 */
@Entity(
    tableName = "transaction_sources",
    indices = [
        Index("transactionId"),
        Index(value = ["sourceType", "sourceId"], unique = true),
        Index("messageHash")
    ],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TransactionSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: String,
    val sourceType: String,
    val sourceId: String,
    val sender: String,
    val messageHash: String,
    val parserVersion: Int,
    val receivedAt: Long
)

/** An edge in the transaction graph: duplicate, reversal, refund, transfer, card payment. */
@Entity(
    tableName = "transaction_links",
    indices = [
        Index("fromTransactionId"),
        Index("toTransactionId"),
        Index(value = ["fromTransactionId", "toTransactionId", "relation"], unique = true)
    ]
)
data class TransactionLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromTransactionId: String,
    val toTransactionId: String,
    val relation: String,
    val confidence: Double,
    val explanation: String? = null,
    val createdAt: Long
)
