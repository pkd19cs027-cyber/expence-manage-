package com.kharcha.ledger.engine.model

/** A bank, card issuer, wallet or UPI application the engine knows about. */
data class Institution(
    val id: String,
    val displayName: String,
    val kind: Kind
) {
    enum class Kind {
        BANK,
        CARD_ISSUER,
        /** Google Pay, PhonePe, Paytm UPI: a payment interface, not a money store. */
        UPI_APP,
        /** Paytm wallet, Amazon Pay balance: actually holds money. */
        WALLET,
        NBFC,
        BROKER,
        UNKNOWN
    }

    companion object {
        val UNKNOWN = Institution("UNKNOWN", "Unknown", Kind.UNKNOWN)
    }
}

/**
 * A reference to an account as it appeared in one message: an institution plus
 * the last four digits. Messages write the same account a dozen ways
 * (`A/c XX4821`, `Acct XXXX4821`, `account ending 4821`), so identity is always
 * institution + last four, never the raw string.
 */
data class AccountRef(
    val institutionId: String,
    val last4: String,
    val type: AccountType = AccountType.UNKNOWN
) {
    val key: String get() = "$institutionId:$last4:${typeGroup()}"

    /**
     * A card and a savings account at the same bank with the same last four are
     * different accounts; savings vs unknown are not worth splitting.
     */
    private fun typeGroup(): String = when (type) {
        AccountType.CREDIT_CARD -> "CARD"
        AccountType.PREPAID_WALLET -> "WALLET"
        AccountType.CASH -> "CASH"
        AccountType.LOAN -> "LOAN"
        else -> "BANK"
    }

    companion object {
        /** The synthetic wallet that ATM withdrawals move money into. */
        val CASH = AccountRef("CASH", "0000", AccountType.CASH)
    }
}

/** An account the app believes belongs to the user. */
data class KnownAccount(
    val ref: AccountRef,
    val displayName: String,
    val verifiedByUser: Boolean = false,
    val balance: Money? = null,
    val balanceAt: java.time.Instant? = null,
    val creditLimit: Money? = null,
    val outstanding: Money? = null,
    val statementDue: Money? = null,
    val minimumDue: Money? = null,
    val dueDate: java.time.LocalDate? = null
) {
    val isCreditCard: Boolean get() = ref.type == AccountType.CREDIT_CARD
}
