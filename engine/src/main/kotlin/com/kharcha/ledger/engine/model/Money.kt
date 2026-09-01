package com.kharcha.ledger.engine.model

import kotlin.math.abs

/**
 * Money is stored as an integral number of paise. Rupee amounts in Indian SMS
 * are always at most two decimal places, so a [Long] of paise is exact where a
 * [Double] of rupees is not: `540.10 + 0.20` must never drift.
 */
@JvmInline
value class Money(val paise: Long) : Comparable<Money> {

    val rupees: Double get() = paise / 100.0

    operator fun plus(other: Money) = Money(paise + other.paise)

    operator fun minus(other: Money) = Money(paise - other.paise)

    operator fun times(factor: Int) = Money(paise * factor)

    fun abs() = Money(abs(paise))

    fun isZero() = paise == 0L

    fun isPositive() = paise > 0

    override fun compareTo(other: Money): Int = paise.compareTo(other.paise)

    /** Renders with Indian digit grouping: 1,00,000.50 rather than 100,000.50. */
    fun format(withSymbol: Boolean = true, withPaise: Boolean = paise % 100L != 0L): String {
        val negative = paise < 0
        val total = abs(paise)
        val whole = total / 100
        val fraction = total % 100
        val grouped = groupIndian(whole)
        val body = if (withPaise) "$grouped.${fraction.toString().padStart(2, '0')}" else grouped
        return buildString {
            if (negative) append('-')
            if (withSymbol) append('₹')
            append(body)
        }
    }

    override fun toString(): String = format()

    companion object {
        val ZERO = Money(0)

        fun ofRupees(rupees: Long) = Money(rupees * 100)

        fun ofRupees(rupees: Double) = Money(Math.round(rupees * 100))

        /**
         * Parses the numeric portion of an Indian money token, e.g. `1,00,000.50`,
         * `5000`, `5,000.00`. Returns null when the token is not a plain number.
         */
        fun parseAmountToken(token: String): Money? {
            val cleaned = token.trim().replace(",", "").removeSuffix(".")
            if (cleaned.isEmpty()) return null
            if (!cleaned.all { it.isDigit() || it == '.' }) return null
            val dot = cleaned.indexOf('.')
            return if (dot < 0) {
                cleaned.toLongOrNull()?.let { Money(it * 100) }
            } else {
                if (cleaned.indexOf('.', dot + 1) >= 0) return null
                val whole = cleaned.substring(0, dot).ifEmpty { "0" }.toLongOrNull() ?: return null
                val fracRaw = cleaned.substring(dot + 1)
                if (fracRaw.length > 2) return null
                val frac = fracRaw.padEnd(2, '0').toLongOrNull() ?: return null
                Money(whole * 100 + frac)
            }
        }

        private fun groupIndian(value: Long): String {
            val digits = value.toString()
            if (digits.length <= 3) return digits
            val head = digits.dropLast(3)
            val tail = digits.takeLast(3)
            val grouped = StringBuilder()
            var count = 0
            for (i in head.indices.reversed()) {
                grouped.append(head[i])
                count++
                if (count % 2 == 0 && i != 0) grouped.append(',')
            }
            return grouped.reverse().toString() + "," + tail
        }
    }
}
