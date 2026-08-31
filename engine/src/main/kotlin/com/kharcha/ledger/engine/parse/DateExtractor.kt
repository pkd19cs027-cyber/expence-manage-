package com.kharcha.ledger.engine.parse

import com.kharcha.ledger.engine.model.TimestampSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ExtractedTime(
    val instant: Instant,
    val source: TimestampSource,
    val hadDate: Boolean,
    val hadTime: Boolean
)

/**
 * Indian banks each pick their own date format and then vary it by product:
 * `31-Aug-26`, `31/08/2026`, `31-08-26`, `31AUG26`, `Aug 31`, `2026-08-31`.
 * Everything is normalised to an instant in IST, and when the body carries no
 * date at all the SMS receive time is used — but flagged, so the UI can say
 * "time approximate" instead of quietly inventing precision.
 */
object DateExtractor {

    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    private val MONTHS = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "SEPT" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
    )

    /** `31-Aug-26`, `31 AUG 2026`, `31AUG26`, `31/Aug/2026`. */
    private val DAY_MON_YEAR = Regex("\\b(\\d{1,2})[- /]?([A-Z]{3,4})[- /]?(\\d{2,4})\\b")

    /** `31-08-2026`, `31/08/26`, `31.08.2026`. */
    private val DAY_MONTH_YEAR_NUM = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b")

    /** `2026-08-31` (ISO, used by a few NBFCs). */
    private val ISO = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")

    /** `AUG 31` / `AUG 31, 2026`, and `31 AUG` without a year. */
    private val MON_DAY = Regex("\\b([A-Z]{3,4})\\.?\\s+(\\d{1,2})(?:,?\\s*(\\d{4}))?\\b")
    private val DAY_MON = Regex("\\b(\\d{1,2})\\s+([A-Z]{3,4})\\b")

    private val TIME_24 = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\b")
    private val TIME_12 = Regex("\\b(1[0-2]|0?[1-9]):([0-5]\\d)\\s*([AP])\\.?M\\.?\\b")

    private val DUE_DATE = Regex(
        "(?:DUE (?:DATE|BY|ON)|PAY BY|BEFORE)\\s*:?\\s*(\\d{1,2}[-/ ]?[A-Z]{3,4}[-/ ]?\\d{2,4}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4})"
    )

    /**
     * @param upperBody the uppercased, whitespace-collapsed message
     * @param receivedAt when the phone received it — both the fallback and the
     *        sanity bound, since a transaction cannot happen long after its own
     *        notification.
     */
    fun extract(upperBody: String, receivedAt: Instant): ExtractedTime {
        val date = findDate(upperBody, receivedAt)
        val time = findTime(upperBody)

        if (date == null && time == null) {
            return ExtractedTime(receivedAt, TimestampSource.MESSAGE_RECEIVED_TIME, false, false)
        }

        val receivedLocal = LocalDateTime.ofInstant(receivedAt, IST)
        val localDate = date ?: receivedLocal.toLocalDate()
        val localTime = time ?: receivedLocal.toLocalTime()
        val candidate = LocalDateTime.of(localDate, localTime).atZone(IST).toInstant()

        // Guard against picking up a due date or statement date as the moment of
        // the transaction: nothing may be dated more than a day after its SMS.
        if (candidate.isAfter(receivedAt.plus(1, ChronoUnit.DAYS))) {
            return ExtractedTime(receivedAt, TimestampSource.MESSAGE_RECEIVED_TIME, false, false)
        }
        // Equally, a transaction 400 days before its SMS is a misparse.
        if (candidate.isBefore(receivedAt.minus(400, ChronoUnit.DAYS))) {
            return ExtractedTime(receivedAt, TimestampSource.MESSAGE_RECEIVED_TIME, false, false)
        }

        return ExtractedTime(
            candidate,
            if (date != null) TimestampSource.MESSAGE_BODY else TimestampSource.MESSAGE_RECEIVED_TIME,
            date != null,
            time != null
        )
    }

    fun extractDueDate(upperBody: String, receivedAt: Instant): LocalDate? {
        val raw = DUE_DATE.find(upperBody)?.groupValues?.get(1) ?: return null
        return findDate(raw, receivedAt, allowFuture = true)
    }

    private fun findDate(text: String, receivedAt: Instant, allowFuture: Boolean = false): LocalDate? {
        val fallbackYear = LocalDateTime.ofInstant(receivedAt, IST).year

        ISO.find(text)?.let { m ->
            return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        DAY_MON_YEAR.find(text)?.let { m ->
            val month = MONTHS[m.groupValues[2]]
            if (month != null) {
                return safeDate(expandYear(m.groupValues[3].toInt()), month, m.groupValues[1].toInt())
            }
        }
        DAY_MONTH_YEAR_NUM.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            // India writes dd-mm; only fall back to mm-dd when dd is impossible.
            val (day, month) = if (a <= 12 && b > 12) Pair(b, a) else Pair(a, b)
            return safeDate(expandYear(m.groupValues[3].toInt()), month, day)
        }
        MON_DAY.find(text)?.let { m ->
            val month = MONTHS[m.groupValues[1]]
            if (month != null) {
                val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: fallbackYear
                val d = safeDate(year, month, m.groupValues[2].toInt())
                return adjustYearless(d, receivedAt, m.groupValues[3].isEmpty(), allowFuture)
            }
        }
        DAY_MON.find(text)?.let { m ->
            val month = MONTHS[m.groupValues[2]]
            if (month != null) {
                val d = safeDate(fallbackYear, month, m.groupValues[1].toInt())
                return adjustYearless(d, receivedAt, true, allowFuture)
            }
        }
        return null
    }

    /**
     * A yearless `12 SEP` in a December SMS means the coming September only for
     * due dates; for transactions it means the one that just passed.
     */
    private fun adjustYearless(
        date: LocalDate?,
        receivedAt: Instant,
        yearless: Boolean,
        allowFuture: Boolean
    ): LocalDate? {
        if (date == null || !yearless) return date
        val received = LocalDateTime.ofInstant(receivedAt, IST).toLocalDate()
        return when {
            allowFuture && date.isBefore(received.minusDays(15)) -> date.plusYears(1)
            !allowFuture && date.isAfter(received.plusDays(1)) -> date.minusYears(1)
            else -> date
        }
    }

    private fun expandYear(year: Int): Int = when {
        year >= 100 -> year
        year >= 70 -> 1900 + year
        else -> 2000 + year
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun findTime(text: String): LocalTime? {
        TIME_12.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt() % 12
            if (m.groupValues[3] == "P") hour += 12
            return LocalTime.of(hour, m.groupValues[2].toInt())
        }
        TIME_24.find(text)?.let { m ->
            val second = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), second)
        }
        return null
    }
}
