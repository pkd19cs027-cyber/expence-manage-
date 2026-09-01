package com.kharcha.ledger.ui.components

import com.kharcha.ledger.engine.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")
private val dayFormat = DateTimeFormatter.ofPattern("d MMM")
private val fullFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

fun Money.short(): String = when {
    paise >= 10_000_000_00L -> "₹%.1fCr".format(paise / 10_000_000_00.0)
    paise >= 100_000_00L -> "₹%.1fL".format(paise / 100_000_00.0)
    paise >= 1_000_00L -> "₹%.1fK".format(paise / 1_000_00.0)
    else -> format(withPaise = false)
}

fun Instant.formatDay(): String {
    val date = atZone(IST).toLocalDate()
    val today = LocalDate.now(IST)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(dayFormat)
    }
}

fun Instant.formatTime(): String = atZone(IST).format(timeFormat)

fun Instant.formatFull(): String = atZone(IST).format(fullFormat)

/** "Last known balance · 2 hours ago" reads honestly; "Balance" does not. */
fun Instant.relativeAge(now: Instant = Instant.now()): String {
    val minutes = ChronoUnit.MINUTES.between(this, now)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} hr ago"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} days ago"
        else -> formatDay()
    }
}
