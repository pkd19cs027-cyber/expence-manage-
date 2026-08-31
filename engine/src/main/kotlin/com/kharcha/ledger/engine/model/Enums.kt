package com.kharcha.ledger.engine.model

/** Which way the money moved relative to the account named in the message. */
enum class Direction { DEBIT, CREDIT }

/** The rail the money travelled on. */
enum class PaymentMethod {
    UPI,
    CARD,
    ATM,
    NETBANKING,
    NEFT,
    IMPS,
    RTGS,
    WALLET,
    AUTO_DEBIT,
    CHEQUE,
    CASH,
    UNKNOWN;

    /**
     * Two messages about one real payment often disagree on the rail: the bank
     * says UPI, the merchant app says "wallet", a card auth says CARD while the
     * settlement says NETBANKING. Compatible methods may be merged.
     */
    fun isCompatibleWith(other: PaymentMethod): Boolean = when {
        this == other -> true
        this == UNKNOWN || other == UNKNOWN -> true
        this in UPI_LIKE && other in UPI_LIKE -> true
        this in BANK_TRANSFER && other in BANK_TRANSFER -> true
        this == CARD && other == NETBANKING -> true
        this == NETBANKING && other == CARD -> true
        else -> false
    }

    private companion object {
        val UPI_LIKE = setOf(UPI, WALLET, NETBANKING)
        val BANK_TRANSFER = setOf(NEFT, IMPS, RTGS, NETBANKING)
    }
}

/**
 * What the movement *means* to the user's finances. This is deliberately not the
 * same as [Direction]: a ₹10,000 debit can be an expense, a transfer to another
 * of the user's own accounts, an investment, or a credit-card bill payment, and
 * only two of those are spending.
 */
enum class TransactionKind {
    EXPENSE,
    INCOME,
    SALARY,
    TRANSFER,
    INVESTMENT,
    EMI,
    CARD_PAYMENT,
    CASH_WITHDRAWAL,
    CASH_EXPENSE,
    REFUND,
    REVERSAL,
    CASHBACK,
    INTEREST,
    FEE,
    BILL_PAYMENT,
    UNKNOWN;

    /** Only these reduce "money available to spend" in the spending reports. */
    val countsAsSpending: Boolean
        get() = this == EXPENSE || this == EMI || this == BILL_PAYMENT || this == FEE || this == CASH_EXPENSE

    val countsAsIncome: Boolean
        get() = this == INCOME || this == SALARY || this == CASHBACK || this == INTEREST
}

/** The lifecycle of a reconstructed transaction. */
enum class TransactionStatus {
    DETECTED,
    CONFIRMED,
    PENDING,
    FAILED,
    REVERSED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    DUPLICATE,
    TRANSFER,
    IGNORED;

    /** Failed, reversed and duplicate rows stay for traceability but never spend. */
    val hasFinancialImpact: Boolean
        get() = this != FAILED && this != DUPLICATE && this != IGNORED && this != REVERSED
}

/** How two transactions relate to each other in the transaction graph. */
enum class LinkRelation {
    DUPLICATE_OF,
    REVERSAL_OF,
    REFUND_OF,
    TRANSFER_PAIR,
    CARD_PAYMENT_FOR
}

/** Where a financial event came from. */
enum class SourceType { SMS, NOTIFICATION, RECEIPT, MANUAL, IMPORT }

enum class AccountType { SAVINGS, CURRENT, CREDIT_CARD, PREPAID_WALLET, CASH, LOAN, UNKNOWN }

/** Whether a transaction time came from the message text or the SMS clock. */
enum class TimestampSource { MESSAGE_BODY, MESSAGE_RECEIVED_TIME, USER }

/**
 * The first-stage classification of an incoming message. Only a subset of these
 * ever become ledger entries; the rest exist so the pipeline can prove it looked
 * at a message and deliberately decided it was not money moving.
 */
enum class MessageClass(val producesTransaction: Boolean) {
    DEBIT(true),
    CREDIT(true),
    UPI_TRANSACTION(true),
    CARD_SPEND(true),
    ATM_WITHDRAWAL(true),
    BANK_TRANSFER(true),
    REFUND(true),
    REVERSAL(true),
    SALARY(true),
    EMI(true),
    INVESTMENT(true),
    BILL_PAYMENT(true),
    CARD_BILL_PAYMENT(true),
    CASHBACK(true),
    INTEREST(true),
    FAILED_TRANSACTION(true),
    MANDATE(false),
    BALANCE_ALERT(false),
    CARD_STATEMENT(false),
    OTP(false),
    PROMOTIONAL(false),
    NOT_FINANCIAL(false),
    UNKNOWN(false)
}
