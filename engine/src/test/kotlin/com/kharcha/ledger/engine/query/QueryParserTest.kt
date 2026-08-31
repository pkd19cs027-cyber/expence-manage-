package com.kharcha.ledger.engine.query

import com.kharcha.ledger.engine.T
import com.kharcha.ledger.engine.merchant.Categories
import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.PaymentMethod
import com.kharcha.ledger.engine.model.TimestampSource
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.model.TransactionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryParserTest {

    private val now = T.at(15, 12, 0, month = 9)

    private fun tx(
        amount: Long,
        merchant: String?,
        category: String,
        day: Int,
        month: Int = 9,
        method: PaymentMethod = PaymentMethod.UPI
    ) = CanonicalTransaction(
        id = "T$day$amount",
        account = AccountRef("HDFC", "4821", AccountType.SAVINGS),
        amount = Money.ofRupees(amount),
        direction = Direction.DEBIT,
        kind = TransactionKind.EXPENSE,
        status = TransactionStatus.CONFIRMED,
        method = method,
        timestamp = T.at(day, 10, 0, month = month),
        timestampSource = TimestampSource.MESSAGE_BODY,
        merchant = merchant,
        category = category
    )

    @Test
    fun `category plus amount bound`() {
        val query = QueryParser.parse("food above 500", now)
        assertEquals(setOf(Categories.FOOD), query.categories)
        assertEquals(Money.ofRupees(500L), query.minAmount)
        assertTrue(query.matches(tx(540, "Swiggy", Categories.FOOD, 10)))
        assertFalse(query.matches(tx(180, "Third Wave Coffee", Categories.FOOD, 10)))
    }

    @Test
    fun `merchant plus relative month`() {
        val query = QueryParser.parse("swiggy last month", now)
        assertEquals(setOf("Swiggy"), query.merchants)
        assertEquals("last month", query.range?.label)
        assertTrue(query.matches(tx(540, "Swiggy", Categories.FOOD, 20, month = 8)))
        assertFalse(query.matches(tx(540, "Swiggy", Categories.FOOD, 10, month = 9)))
    }

    @Test
    fun `payment rail filter`() {
        val query = QueryParser.parse("upi payments this week", now)
        assertEquals(setOf(PaymentMethod.UPI), query.methods)
        assertTrue(query.matches(tx(200, "Zepto", Categories.GROCERIES, 15)))
        assertFalse(query.matches(tx(200, "Zepto", Categories.GROCERIES, 15, method = PaymentMethod.CARD)))
    }

    @Test
    fun `account filter`() {
        val query = QueryParser.parse("expenses from hdfc", now)
        assertEquals("hdfc", query.accountFragment)
        assertTrue(query.matches(tx(200, "Zepto", Categories.GROCERIES, 15)))
    }

    @Test
    fun `credit card spending`() {
        val query = QueryParser.parse("credit card spending", now)
        assertEquals(setOf(PaymentMethod.CARD), query.methods)
    }

    @Test
    fun `large payments`() {
        val query = QueryParser.parse("payments above 10000", now)
        assertEquals(Money.ofRupees(10_000L), query.minAmount)
        assertTrue(query.matches(tx(15_000, "NoBroker", Categories.RENT, 5)))
        assertFalse(query.matches(tx(540, "Swiggy", Categories.FOOD, 5)))
    }

    @Test
    fun `an empty query matches everything`() {
        val query = QueryParser.parse("", now)
        assertTrue(query.matches(tx(540, "Swiggy", Categories.FOOD, 1)))
    }

    @Test
    fun `last N days`() {
        val query = QueryParser.parse("last 7 days", now)
        assertTrue(query.matches(tx(540, "Swiggy", Categories.FOOD, 12)))
        assertFalse(query.matches(tx(540, "Swiggy", Categories.FOOD, 1)))
    }
}
