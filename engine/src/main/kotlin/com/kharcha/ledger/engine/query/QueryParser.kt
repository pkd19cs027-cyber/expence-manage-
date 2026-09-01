package com.kharcha.ledger.engine.query

import com.kharcha.ledger.engine.merchant.Categories
import com.kharcha.ledger.engine.merchant.MerchantCatalog
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.PaymentMethod
import com.kharcha.ledger.engine.model.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DateRange(val from: Instant, val to: Instant, val label: String) {
    operator fun contains(instant: Instant): Boolean = !instant.isBefore(from) && instant.isBefore(to)
}

/**
 * A parsed search. Everything is optional; an empty query matches the whole
 * ledger, which is what an empty search box should do.
 */
data class TransactionQuery(
    val text: String,
    val categories: Set<String> = emptySet(),
    val merchants: Set<String> = emptySet(),
    val methods: Set<PaymentMethod> = emptySet(),
    val kinds: Set<TransactionKind> = emptySet(),
    val direction: Direction? = null,
    val accountFragment: String? = null,
    val minAmount: Money? = null,
    val maxAmount: Money? = null,
    val range: DateRange? = null,
    val freeText: List<String> = emptyList()
) {
    fun matches(tx: CanonicalTransaction): Boolean {
        if (categories.isNotEmpty() && tx.category !in categories) return false
        if (merchants.isNotEmpty() && merchants.none { it.equals(tx.merchant, ignoreCase = true) }) return false
        if (methods.isNotEmpty() && tx.method !in methods) return false
        if (kinds.isNotEmpty() && tx.kind !in kinds) return false
        if (direction != null && tx.direction != direction) return false
        if (accountFragment != null) {
            val account = tx.account ?: return false
            val haystack = "${account.institutionId} ${account.last4}".lowercase()
            if (!haystack.contains(accountFragment.lowercase())) return false
        }
        if (minAmount != null && tx.amount < minAmount) return false
        if (maxAmount != null && tx.amount > maxAmount) return false
        if (range != null && tx.timestamp !in range) return false
        if (freeText.isNotEmpty()) {
            val haystack = listOfNotNull(tx.merchant, tx.merchantRaw, tx.category, tx.channelApp)
                .joinToString(" ").lowercase()
            if (freeText.none { haystack.contains(it) }) return false
        }
        return true
    }
}

/**
 * Turns "food above 500 last month" into a filter, entirely offline.
 *
 * No model is needed for the queries people actually type: a category word, a
 * merchant, an amount bound, a payment rail and a time span cover almost all of
 * them, and anything left over is matched as free text.
 */
object QueryParser {

    private val zone = ZoneId.of("Asia/Kolkata")

    private val CATEGORY_WORDS: Map<String, String> = mapOf(
        "food" to Categories.FOOD, "dining" to Categories.FOOD, "restaurant" to Categories.FOOD,
        "grocery" to Categories.GROCERIES, "groceries" to Categories.GROCERIES,
        "shopping" to Categories.SHOPPING, "shop" to Categories.SHOPPING,
        "transport" to Categories.TRANSPORT, "travel" to Categories.TRAVEL,
        "fuel" to Categories.FUEL, "petrol" to Categories.FUEL,
        "bills" to Categories.BILLS, "bill" to Categories.BILLS, "utilities" to Categories.BILLS,
        "entertainment" to Categories.ENTERTAINMENT, "health" to Categories.HEALTH,
        "medical" to Categories.HEALTH, "education" to Categories.EDUCATION,
        "rent" to Categories.RENT, "insurance" to Categories.INSURANCE,
        "investment" to Categories.INVESTMENTS, "investments" to Categories.INVESTMENTS,
        "emi" to Categories.LOANS, "loan" to Categories.LOANS, "loans" to Categories.LOANS,
        "cash" to Categories.CASH, "fees" to Categories.FEES
    )

    private val METHOD_WORDS: Map<String, PaymentMethod> = mapOf(
        "upi" to PaymentMethod.UPI,
        "card" to PaymentMethod.CARD,
        "atm" to PaymentMethod.ATM,
        "neft" to PaymentMethod.NEFT,
        "imps" to PaymentMethod.IMPS,
        "rtgs" to PaymentMethod.RTGS,
        "wallet" to PaymentMethod.WALLET,
        "netbanking" to PaymentMethod.NETBANKING
    )

    private val AMOUNT_ABOVE = Regex("(?:above|over|more than|greater than|>)\\s*(?:rs\\.?|inr|₹)?\\s*([\\d,]+)")
    private val AMOUNT_BELOW = Regex("(?:below|under|less than|<)\\s*(?:rs\\.?|inr|₹)?\\s*([\\d,]+)")
    private val AMOUNT_BETWEEN = Regex("between\\s*(?:rs\\.?|inr|₹)?\\s*([\\d,]+)\\s*(?:and|-|to)\\s*(?:rs\\.?|inr|₹)?\\s*([\\d,]+)")
    private val ACCOUNT = Regex("(?:from|on|in)\\s+(hdfc|icici|sbi|axis|kotak|federal|canara|bob|pnb|idfc|indusind|yes|rbl)\\b")
    private val LAST_N_DAYS = Regex("last\\s+(\\d{1,3})\\s+days?")

    private val STOP_WORDS = setOf(
        "show", "me", "my", "all", "the", "a", "an", "of", "in", "on", "for", "at",
        "spent", "spend", "spending", "payments", "payment", "expenses", "expense",
        "transactions", "transaction", "and", "with", "was", "were", "what", "how", "much"
    )

    fun parse(raw: String, now: Instant): TransactionQuery {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return TransactionQuery(raw)

        val consumed = mutableSetOf<String>()
        val categories = mutableSetOf<String>()
        val merchants = mutableSetOf<String>()
        val methods = mutableSetOf<PaymentMethod>()
        val kinds = mutableSetOf<TransactionKind>()
        var direction: Direction? = null
        var minAmount: Money? = null
        var maxAmount: Money? = null

        AMOUNT_BETWEEN.find(text)?.let { m ->
            minAmount = Money.parseAmountToken(m.groupValues[1])
            maxAmount = Money.parseAmountToken(m.groupValues[2])
            consumed += m.value.split(" ")
        }
        AMOUNT_ABOVE.find(text)?.let { m ->
            minAmount = Money.parseAmountToken(m.groupValues[1])
            consumed += m.value.split(" ")
        }
        AMOUNT_BELOW.find(text)?.let { m ->
            maxAmount = Money.parseAmountToken(m.groupValues[1])
            consumed += m.value.split(" ")
        }

        val account = ACCOUNT.find(text)?.let { m ->
            consumed += m.value.split(" ")
            m.groupValues[1]
        }

        val range = parseRange(text, now)?.also { consumed += it.second }?.first

        if (text.contains("credit card")) {
            methods += PaymentMethod.CARD
            consumed += listOf("credit", "card")
        }
        if (text.contains("refund")) {
            kinds += TransactionKind.REFUND
            consumed += "refund"
        }
        if (text.contains("income") || text.contains("salary")) {
            direction = Direction.CREDIT
            if (text.contains("salary")) kinds += TransactionKind.SALARY
            consumed += listOf("income", "salary")
        }

        val words = text.split(Regex("[^a-z0-9₹.]+")).filter { it.isNotBlank() }
        words.forEach { word ->
            if (word in consumed) return@forEach
            CATEGORY_WORDS[word]?.let { categories += it; consumed += word }
            METHOD_WORDS[word]?.let { methods += it; consumed += word }
        }

        // Merchant names can be two words ("third wave"), so match against the
        // whole query rather than word by word.
        MerchantCatalog.entries.forEach { entry ->
            val names = listOf(entry.canonicalName) + entry.aliases
            if (names.any { text.contains(it.lowercase()) }) {
                merchants += entry.canonicalName
                consumed += entry.canonicalName.lowercase().split(" ")
            }
        }

        val freeText = words.filter { it !in consumed && it !in STOP_WORDS && it.length > 2 && !it.all { c -> c.isDigit() } }

        return TransactionQuery(
            text = raw,
            categories = categories,
            merchants = merchants,
            methods = methods,
            kinds = kinds,
            direction = direction,
            accountFragment = account,
            minAmount = minAmount,
            maxAmount = maxAmount,
            range = range,
            freeText = if (merchants.isEmpty() && categories.isEmpty()) freeText else emptyList()
        )
    }

    private fun parseRange(text: String, now: Instant): Pair<DateRange, List<String>>? {
        val today = now.atZone(zone).toLocalDate()

        fun range(from: LocalDate, to: LocalDate, label: String) =
            DateRange(from.atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant(), label)

        LAST_N_DAYS.find(text)?.let { m ->
            val days = m.groupValues[1].toLong()
            return range(today.minusDays(days), today.plusDays(1), "last $days days") to m.value.split(" ")
        }

        return when {
            text.contains("today") ->
                range(today, today.plusDays(1), "today") to listOf("today")
            text.contains("yesterday") ->
                range(today.minusDays(1), today, "yesterday") to listOf("yesterday")
            text.contains("this week") ->
                range(today.minusDays((today.dayOfWeek.value - 1).toLong()), today.plusDays(1), "this week") to listOf("this", "week")
            text.contains("last week") -> {
                val startOfThisWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
                range(startOfThisWeek.minusWeeks(1), startOfThisWeek, "last week") to listOf("last", "week")
            }
            text.contains("this month") ->
                range(today.withDayOfMonth(1), today.plusDays(1), "this month") to listOf("this", "month")
            text.contains("last month") -> {
                val startOfThisMonth = today.withDayOfMonth(1)
                range(startOfThisMonth.minusMonths(1), startOfThisMonth, "last month") to listOf("last", "month")
            }
            text.contains("this year") ->
                range(today.withDayOfYear(1), today.plusDays(1), "this year") to listOf("this", "year")
            else -> null
        }
    }
}
