package com.kharcha.ledger.engine.parse

import com.kharcha.ledger.engine.classify.FinancialMessageClassifier
import com.kharcha.ledger.engine.merchant.MerchantNormalizer
import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.CandidateTransaction
import com.kharcha.ledger.engine.model.Classification
import com.kharcha.ledger.engine.model.Direction
import com.kharcha.ledger.engine.model.Institution
import com.kharcha.ledger.engine.model.MessageClass
import com.kharcha.ledger.engine.model.PaymentMethod
import com.kharcha.ledger.engine.model.RawMessage
import com.kharcha.ledger.engine.model.ReferenceKind
import com.kharcha.ledger.engine.model.SourceRef
import com.kharcha.ledger.engine.model.TransactionKind
import com.kharcha.ledger.engine.sender.SenderRegistry
import java.security.MessageDigest

/**
 * The parser version is stamped onto every parsed row.
 *
 * When the rules improve, the app can re-run the new parser over messages it
 * already holds and upgrade old rows in place — `Merchant: Unknown` becoming
 * `Indian Oil` months later, with no cloud copy of anyone's SMS.
 */
const val PARSER_VERSION: Int = 1

data class ParseResult(
    val classification: Classification,
    val candidate: CandidateTransaction?,
    val rejectionReason: String? = null
)

/**
 * Turns one financial message into one [CandidateTransaction].
 *
 * A candidate is an *observation*, not a ledger entry — three messages about the
 * same ₹540 produce three candidates, and it is the reconciliation engine's job
 * to decide they are one payment.
 */
object TransactionParser {

    fun parse(message: RawMessage): ParseResult {
        val classification = FinancialMessageClassifier.classify(message)
        if (!classification.producesTransaction) {
            return ParseResult(classification, null, "class=${classification.messageClass}")
        }

        val body = message.upperBody
        val institution = SenderRegistry.resolve(message.sender, body)
        val isUpiApp = institution?.kind == Institution.Kind.UPI_APP
        val isWallet = institution?.kind == Institution.Kind.WALLET
        val isCardIssuer = institution?.kind == Institution.Kind.CARD_ISSUER

        val amounts = AmountExtractor.extractAll(body)
        val primary = AmountExtractor.transactionAmount(amounts)
            ?: return ParseResult(classification, null, "no-transaction-amount")

        val time = DateExtractor.extract(body, message.receivedAt)
        val reference = ReferenceExtractor.extract(body)
        val counterparty = MerchantExtractor.extract(message.normalizedBody)
        val normalizedMerchant = MerchantNormalizer.normalize(counterparty?.raw)

        val mentions = AccountExtractor.extract(body, issuerIsCardCompany = isCardIssuer)
        val accounts = resolveAccounts(body, mentions, institution, classification.messageClass, isUpiApp, isWallet)

        val method = detectMethod(body, classification.messageClass)
        val direction = detectDirection(body, primary, classification.messageClass)
        val kind = detectKind(classification.messageClass, direction, method, body)

        val notes = mutableListOf<String>()
        if (isUpiApp && accounts.primary == null) {
            // Rule: a UPI app is an interface, never an account. Booking a
            // Google Pay payment against a "Google Pay account" invents a
            // balance the user does not have.
            notes += "channel-only-source"
        }
        if (time.source == com.kharcha.ledger.engine.model.TimestampSource.MESSAGE_RECEIVED_TIME) {
            notes += "time-from-sms-clock"
        }

        val candidate = CandidateTransaction(
            source = sourceRef(message),
            messageClass = classification.messageClass,
            direction = direction,
            amount = primary.money,
            timestamp = time.instant,
            timestampSource = time.source,
            account = accounts.primary,
            counterAccount = accounts.counter,
            channelApp = if (isUpiApp) institution?.displayName else null,
            method = method,
            kind = kind,
            merchantRaw = counterparty?.raw,
            merchantCanonical = normalizedMerchant?.canonicalName,
            counterpartyVpa = counterparty?.vpa,
            reference = reference?.value,
            referenceKind = reference?.kind ?: ReferenceKind.NONE,
            balanceAfter = AmountExtractor.firstOf(amounts, AmountRole.BALANCE),
            availableLimit = AmountExtractor.firstOf(amounts, AmountRole.AVAILABLE_LIMIT),
            creditLimit = AmountExtractor.firstOf(amounts, AmountRole.CREDIT_LIMIT),
            statementAmount = AmountExtractor.firstOf(amounts, AmountRole.TOTAL_DUE)
                ?: AmountExtractor.firstOf(amounts, AmountRole.STATEMENT),
            minimumDue = AmountExtractor.firstOf(amounts, AmountRole.MINIMUM_DUE),
            dueDate = DateExtractor.extractDueDate(body, message.receivedAt),
            failed = classification.messageClass == MessageClass.FAILED_TRANSACTION,
            confidence = confidence(classification, primary, accounts.primary, reference, normalizedMerchant != null),
            notes = notes
        )
        return ParseResult(classification, candidate)
    }

    private data class ResolvedAccounts(val primary: AccountRef?, val counter: AccountRef?)

    /**
     * Decides which account the money left or entered, and which account (if any)
     * sits on the other side.
     */
    private fun resolveAccounts(
        body: String,
        mentions: List<AccountMention>,
        institution: Institution?,
        messageClass: MessageClass,
        isUpiApp: Boolean,
        isWallet: Boolean
    ): ResolvedAccounts {
        if (mentions.isEmpty()) {
            // A wallet message with no number still moves the wallet balance.
            if (isWallet && institution != null) {
                return ResolvedAccounts(AccountRef(institution.id, "0000", AccountType.PREPAID_WALLET), null)
            }
            return ResolvedAccounts(null, null)
        }

        // A UPI app is an interface, not a bank: unless the body names the
        // funding bank, the owning institution stays unknown rather than
        // inventing a "Google Pay account" the user does not have.
        val fallbackInstitution = if (isUpiApp) "UNKNOWN" else institution?.id ?: "UNKNOWN"

        val refs = mentions.map { mention ->
            val type = when {
                mention.type == AccountType.CREDIT_CARD -> AccountType.CREDIT_CARD
                isWallet -> AccountType.PREPAID_WALLET
                else -> mention.type
            }
            AccountRef(institutionFor(body, mention, fallbackInstitution), mention.last4, type)
        }

        return when (messageClass) {
            MessageClass.ATM_WITHDRAWAL ->
                ResolvedAccounts(refs.first(), AccountRef.CASH)

            MessageClass.CARD_BILL_PAYMENT -> {
                // Either "Rs.20,000 debited from A/c 4821 towards card 1923"
                // (two accounts, the bank first) or the card issuer's own
                // acknowledgement (one account: the card).
                val card = refs.firstOrNull { it.type == AccountType.CREDIT_CARD }
                val bank = refs.firstOrNull { it.type != AccountType.CREDIT_CARD }
                when {
                    bank != null && card != null -> ResolvedAccounts(bank, card)
                    card != null -> ResolvedAccounts(card, null)
                    else -> ResolvedAccounts(refs.first(), refs.getOrNull(1))
                }
            }

            else -> ResolvedAccounts(refs.first(), refs.getOrNull(1))
        }
    }

    /**
     * Banks name the *other* institution when money leaves for it: a card bill
     * paid from HDFC names ICICI right before the card number. Reading the words
     * around each account mention keeps the counter-account from being filed
     * under the sender's bank.
     */
    private fun institutionFor(body: String, mention: AccountMention, fallback: String): String {
        val start = (mention.index - 40).coerceAtLeast(0)
        val end = (mention.index + mention.raw.length).coerceAtMost(body.length)
        return SenderRegistry.fromBody(body.substring(start, end))?.id ?: fallback
    }

    private fun detectMethod(body: String, messageClass: MessageClass): PaymentMethod = when {
        messageClass == MessageClass.ATM_WITHDRAWAL -> PaymentMethod.ATM
        Regex("\\bATM\\b").containsMatchIn(body) -> PaymentMethod.ATM
        Regex("\\bUPI\\b|\\bVPA\\b|@[A-Z]{2,}").containsMatchIn(body) -> PaymentMethod.UPI
        Regex("\\bIMPS\\b").containsMatchIn(body) -> PaymentMethod.IMPS
        Regex("\\bNEFT\\b").containsMatchIn(body) -> PaymentMethod.NEFT
        Regex("\\bRTGS\\b").containsMatchIn(body) -> PaymentMethod.RTGS
        Regex("\\b(CREDIT CARD|DEBIT CARD|CARD ENDING|POS|CARD XX)\\b").containsMatchIn(body) -> PaymentMethod.CARD
        Regex("\\b(WALLET|PAYTM BALANCE|AMAZON PAY BALANCE)\\b").containsMatchIn(body) -> PaymentMethod.WALLET
        Regex("\\b(AUTO ?DEBIT|MANDATE|STANDING INSTRUCTION|AUTOPAY|ECS|NACH|SI )\\b").containsMatchIn(body) -> PaymentMethod.AUTO_DEBIT
        Regex("\\bCHEQUE\\b").containsMatchIn(body) -> PaymentMethod.CHEQUE
        Regex("\\b(NET ?BANKING|INTERNET BANKING)\\b").containsMatchIn(body) -> PaymentMethod.NETBANKING
        else -> PaymentMethod.UNKNOWN
    }

    /**
     * Direction is decided by whichever verb sits closest to the amount that
     * moved: `Rs.540 debited ... Avl bal Rs.24,850 credited to ...` must not be
     * read as a credit because a later clause said so.
     */
    private fun detectDirection(
        body: String,
        primary: AmountReading,
        messageClass: MessageClass
    ): Direction {
        val window = primary.leftContext + " " + primary.raw + " " + primary.rightContext
        val debit = Regex("\\b(DEBITED|DEBIT|SPENT|PAID|WITHDRAWN|SENT|DEDUCTED|CHARGED|PURCHASE|TRANSFERRED)\\b")
            .containsMatchIn(window)
        val credit = Regex("\\b(CREDITED|CREDIT|RECEIVED|DEPOSITED|REFUNDED|REVERSED|ADDED)\\b")
            .containsMatchIn(window)

        return when {
            debit && !credit -> Direction.DEBIT
            credit && !debit -> Direction.CREDIT
            else -> when (messageClass) {
                MessageClass.CREDIT, MessageClass.SALARY, MessageClass.REFUND, MessageClass.REVERSAL,
                MessageClass.CASHBACK, MessageClass.INTEREST, MessageClass.CARD_BILL_PAYMENT -> Direction.CREDIT
                else -> Direction.DEBIT
            }
        }
    }

    private fun detectKind(
        messageClass: MessageClass,
        direction: Direction,
        method: PaymentMethod,
        body: String
    ): TransactionKind = when (messageClass) {
        MessageClass.ATM_WITHDRAWAL -> TransactionKind.CASH_WITHDRAWAL
        MessageClass.SALARY -> TransactionKind.SALARY
        MessageClass.INTEREST -> TransactionKind.INTEREST
        MessageClass.CASHBACK -> TransactionKind.CASHBACK
        MessageClass.EMI -> TransactionKind.EMI
        MessageClass.INVESTMENT -> TransactionKind.INVESTMENT
        MessageClass.BILL_PAYMENT -> TransactionKind.BILL_PAYMENT
        MessageClass.REFUND -> TransactionKind.REFUND
        MessageClass.REVERSAL -> TransactionKind.REVERSAL
        MessageClass.CARD_BILL_PAYMENT -> TransactionKind.CARD_PAYMENT
        MessageClass.BANK_TRANSFER -> if (direction == Direction.DEBIT) TransactionKind.TRANSFER else TransactionKind.INCOME
        MessageClass.FAILED_TRANSACTION -> TransactionKind.UNKNOWN
        else -> when {
            direction == Direction.CREDIT -> TransactionKind.INCOME
            Regex("\\b(CHARGES?|FEE|GST|PENALTY|SURCHARGE)\\b").containsMatchIn(body) -> TransactionKind.FEE
            method == PaymentMethod.ATM -> TransactionKind.CASH_WITHDRAWAL
            else -> TransactionKind.EXPENSE
        }
    }

    private fun confidence(
        classification: Classification,
        amount: AmountReading,
        account: AccountRef?,
        reference: ExtractedReference?,
        hasMerchant: Boolean
    ): Double {
        var score = classification.confidence * 0.6
        score += if (amount.role == AmountRole.TRANSACTION) 0.2 else 0.05
        if (account != null) score += 0.1
        if (reference != null) score += 0.08
        if (hasMerchant) score += 0.05
        return score.coerceIn(0.05, 0.99)
    }

    private fun sourceRef(message: RawMessage) = SourceRef(
        sourceType = message.sourceType,
        sourceId = message.id,
        sender = message.sender,
        messageHash = hash(message.body),
        parserVersion = PARSER_VERSION,
        receivedAt = message.receivedAt
    )

    /**
     * Messages are identified by hash so the app can prove it has already seen a
     * message without keeping its text. Raw bodies are retained only when the
     * user explicitly turns that on.
     */
    fun hash(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body.trim().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
