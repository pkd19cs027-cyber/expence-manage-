package com.kharcha.ledger.data

import com.kharcha.ledger.data.mapper.toDomain
import com.kharcha.ledger.data.mapper.toEntity
import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.CanonicalTransaction
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.Money
import com.kharcha.ledger.engine.model.PaymentMethod
import com.kharcha.ledger.engine.model.ReferenceKind
import com.kharcha.ledger.engine.model.SourceRef
import com.kharcha.ledger.engine.model.SourceType
import com.kharcha.ledger.engine.model.TimestampSource
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.model.TransactionStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityMapperTest {

    private val source = SourceRef(
        sourceType = SourceType.SMS,
        sourceId = "42",
        sender = "VM-HDFCBK",
        messageHash = "abcdef0123456789",
        parserVersion = 1,
        receivedAt = Instant.ofEpochMilli(1_756_600_000_000)
    )

    private val transaction = CanonicalTransaction(
        id = "T_1",
        account = AccountRef("HDFC", "4821", AccountType.SAVINGS),
        amount = Money.ofRupees(540L),
        direction = Direction.DEBIT,
        kind = TransactionKind.EXPENSE,
        status = TransactionStatus.CONFIRMED,
        method = PaymentMethod.UPI,
        timestamp = Instant.ofEpochMilli(1_756_600_000_000),
        timestampSource = TimestampSource.MESSAGE_BODY,
        merchant = "Swiggy",
        merchantRaw = "UPI-SWIGGY",
        category = "Food & Dining",
        categoryConfidence = 0.9,
        counterAccount = AccountRef("ICICI", "1923", AccountType.CREDIT_CARD),
        channelApp = "Google Pay",
        reference = "425678901234",
        referenceKind = ReferenceKind.UPI_REF,
        balanceAfter = Money.ofRupees(24_850L),
        confidence = 0.95,
        sources = listOf(source),
        refundedAmount = Money.ofRupees(40L)
    )

    @Test
    fun `a transaction survives a round trip through the database model`() {
        val restored = transaction.toEntity().toDomain(listOf(source))
        assertEquals(transaction, restored)
    }

    @Test
    fun `an unknown enum written by a newer build degrades instead of crashing`() {
        val entity = transaction.toEntity().copy(kind = "SOMETHING_NEW", status = "ALSO_NEW")
        val restored = entity.toDomain()
        assertEquals(TransactionKind.UNKNOWN, restored.kind)
        assertEquals(TransactionStatus.DETECTED, restored.status)
    }

    @Test
    fun `the merchant key is derived so corrections apply across spellings`() {
        val entity = transaction.copy(merchantRaw = "RAZORPAY*SWIGGY").toEntity()
        assertEquals("SWIGGY", entity.merchantKey)
    }
}
