package com.kharcha.ledger.engine

import com.kharcha.ledger.engine.model.RawMessage
import com.kharcha.ledger.engine.model.SourceType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Every fixture is anchored to one evening in IST so gaps are easy to reason about. */
object T {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    fun at(day: Int, hour: Int, minute: Int, month: Int = 8, year: Int = 2026): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(IST).toInstant()

    private var counter = 0

    fun sms(sender: String, body: String, receivedAt: Instant, id: String? = null): RawMessage =
        RawMessage(
            id = id ?: "sms-${counter++}",
            sender = sender,
            body = body,
            receivedAt = receivedAt,
            sourceType = SourceType.SMS
        )
}
