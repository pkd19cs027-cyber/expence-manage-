package com.kharcha.ledger.engine.review

import java.time.Instant

/**
 * Why the engine wants a human to look at something. Everything uncertain is
 * collected here rather than interrupting the user at the moment it happens —
 * one "Needs review · 4" queue, cleared whenever they feel like it.
 */
enum class ReviewReason(val title: String) {
    POSSIBLE_DUPLICATE("Possible duplicate"),
    UNKNOWN_MERCHANT("Unknown merchant"),
    UNKNOWN_ACCOUNT("Unknown account"),
    POSSIBLE_TRANSFER("Possible transfer"),
    POSSIBLE_REFUND("Possible refund"),
    UNCERTAIN_CATEGORY("Uncertain category"),
    LOW_CONFIDENCE("Needs confirmation"),
    SALARY_CONFIRMATION("Was this salary?"),
    UNPARSED_MESSAGE("Could not read message")
}

/** What the user can do about a review item, rendered as swipe actions. */
enum class ReviewAction {
    MERGE,
    KEEP_SEPARATE,
    MARK_TRANSFER,
    MARK_EXPENSE,
    MARK_INVESTMENT,
    MARK_INCOME,
    SET_CATEGORY,
    SET_MERCHANT,
    CONFIRM_ACCOUNT,
    IGNORE
}

data class ReviewItem(
    val id: String,
    val reason: ReviewReason,
    val transactionId: String,
    val relatedTransactionId: String? = null,
    val summary: String,
    val detail: String? = null,
    val actions: List<ReviewAction>,
    val confidence: Double,
    val createdAt: Instant,
    /** The scoring signals behind the suggestion, shown under "why?". */
    val signals: List<String> = emptyList()
)
