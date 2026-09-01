package com.kharcha.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An account the app has seen. `verifiedByUser` is the important column: until
 * the user confirms an account is theirs, money moving into it cannot be
 * treated as a transfer.
 */
@Entity(
    tableName = "accounts",
    indices = [Index("institutionId"), Index("last4")]
)
data class AccountEntity(
    @PrimaryKey val accountKey: String,
    val institutionId: String,
    val last4: String,
    val accountType: String,
    val displayName: String,
    val verifiedByUser: Boolean = false,
    val hiddenByUser: Boolean = false,
    /** Last balance the bank happened to mention, with the moment it said it. */
    val balancePaise: Long? = null,
    val balanceAt: Long? = null,
    val creditLimitPaise: Long? = null,
    val availableLimitPaise: Long? = null,
    val outstandingPaise: Long? = null,
    val statementDuePaise: Long? = null,
    val minimumDuePaise: Long? = null,
    val dueDate: String? = null,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val occurrences: Int = 1
)
