package com.kharcha.ledger.engine.model

import java.time.Instant

/**
 * A raw inbound message exactly as the phone received it. The engine never
 * mutates one and never needs to keep one: everything downstream is derived.
 */
data class RawMessage(
    /** Stable per-device id, e.g. the SMS provider `_id` or a notification key. */
    val id: String,
    val sender: String,
    val body: String,
    val receivedAt: Instant,
    val sourceType: SourceType = SourceType.SMS
) {
    val normalizedBody: String = body.replace(Regex("\\s+"), " ").trim()
    val upperBody: String = normalizedBody.uppercase()
}

/** The verdict of the first-stage classifier. */
data class Classification(
    val messageClass: MessageClass,
    val confidence: Double,
    /** Human-readable reasons, kept for the debug screen and for parser triage. */
    val signals: List<String> = emptyList()
) {
    val producesTransaction: Boolean get() = messageClass.producesTransaction
}
