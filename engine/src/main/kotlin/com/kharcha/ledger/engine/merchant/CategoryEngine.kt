package com.kharcha.ledger.engine.merchant

import com.kharcha.ledger.engine.learn.RuleStore
import com.kharcha.ledger.engine.model.MessageClass
import com.kharcha.ledger.engine.model.TransactionKind

data class CategoryVerdict(val category: String, val confidence: Double, val reason: String)

/**
 * Assigns a spend category, in strict precedence order:
 * a rule the user taught beats the merchant dictionary, which beats keywords in
 * the descriptor, which beats the transaction kind.
 */
class CategoryEngine(private val rules: RuleStore = RuleStore.empty()) {

    fun categorize(
        merchantKey: String?,
        merchantCanonical: String?,
        kind: TransactionKind,
        messageClass: MessageClass,
        descriptor: String? = null
    ): CategoryVerdict {
        merchantKey?.let { key ->
            rules.categoryFor(key)?.let { return CategoryVerdict(it, 0.99, "user-rule") }
        }

        merchantCanonical?.let { name ->
            MerchantCatalog.lookupExact(name)?.let {
                return CategoryVerdict(it.category, 0.9, "merchant-catalog")
            }
        }
        merchantKey?.let { key ->
            MerchantCatalog.lookupExact(key)?.let {
                return CategoryVerdict(it.category, 0.9, "merchant-catalog")
            }
            MerchantCatalog.lookupContained(key)?.let {
                return CategoryVerdict(it.category, 0.8, "merchant-catalog-partial")
            }
        }

        kindCategory(kind)?.let { return CategoryVerdict(it, 0.85, "transaction-kind") }
        classCategory(messageClass)?.let { return CategoryVerdict(it, 0.7, "message-class") }

        val haystack = listOfNotNull(merchantKey, descriptor?.uppercase()).joinToString(" ")
        KEYWORDS.firstOrNull { (regex, _) -> regex.containsMatchIn(haystack) }
            ?.let { return CategoryVerdict(it.second, 0.6, "keyword") }

        return CategoryVerdict(Categories.OTHER, 0.2, "fallback")
    }

    private fun kindCategory(kind: TransactionKind): String? = when (kind) {
        TransactionKind.EMI -> Categories.LOANS
        TransactionKind.INVESTMENT -> Categories.INVESTMENTS
        TransactionKind.CASH_WITHDRAWAL -> Categories.CASH
        TransactionKind.TRANSFER, TransactionKind.CARD_PAYMENT -> Categories.TRANSFERS
        TransactionKind.SALARY, TransactionKind.INCOME, TransactionKind.INTEREST,
        TransactionKind.CASHBACK -> Categories.INCOME
        TransactionKind.FEE -> Categories.FEES
        else -> null
    }

    private fun classCategory(messageClass: MessageClass): String? = when (messageClass) {
        MessageClass.BILL_PAYMENT -> Categories.BILLS
        MessageClass.ATM_WITHDRAWAL -> Categories.CASH
        MessageClass.EMI -> Categories.LOANS
        MessageClass.INVESTMENT -> Categories.INVESTMENTS
        else -> null
    }

    private companion object {
        val KEYWORDS: List<Pair<Regex, String>> = listOf(
            Regex("\\b(RESTAURANT|CAFE|COFFEE|BIRYANI|DHABA|HOTEL|FOODS?|BAKERY|SWEETS|CANTEEN|MESS|PIZZA|JUICE|TEA|CHAI)\\b") to Categories.FOOD,
            Regex("\\b(KIRANA|SUPERMARKET|MART|GROCER|PROVISION|VEGETABLE|MILK|DAIRY|DEPARTMENTAL)\\b") to Categories.GROCERIES,
            Regex("\\b(PETROL|DIESEL|FUEL|FILLING STATION|PETROLEUM|GAS STATION)\\b") to Categories.FUEL,
            Regex("\\b(METRO|BUS|TAXI|CAB|AUTO|TOLL|FASTAG|PARKING|RAILWAY)\\b") to Categories.TRANSPORT,
            Regex("\\b(AIRLINES|FLIGHT|TRAVELS|TOURS|HOTEL BOOKING|RESORT|HOLIDAY)\\b") to Categories.TRAVEL,
            Regex("\\b(ELECTRICITY|POWER|WATER|GAS|BROADBAND|RECHARGE|POSTPAID|PREPAID|DTH|BILL)\\b") to Categories.BILLS,
            Regex("\\b(HOSPITAL|CLINIC|PHARMA|MEDICAL|MEDICOS|DIAGNOSTIC|LAB|DENTAL|DOCTOR)\\b") to Categories.HEALTH,
            Regex("\\b(SCHOOL|COLLEGE|UNIVERSITY|ACADEMY|TUITION|COACHING|EDUCATION|FEES)\\b") to Categories.EDUCATION,
            Regex("\\b(RENT|LANDLORD|LEASE|SOCIETY MAINTENANCE|MAINTENANCE)\\b") to Categories.RENT,
            Regex("\\b(INSURANCE|POLICY|PREMIUM|ASSURANCE)\\b") to Categories.INSURANCE,
            Regex("\\b(MUTUAL FUND|SIP|BROKING|SECURITIES|DEMAT|EQUITY|GOLD BOND)\\b") to Categories.INVESTMENTS,
            Regex("\\b(SALON|SPA|BARBER|LAUNDRY|GYM|FITNESS)\\b") to Categories.PERSONAL,
            Regex("\\b(CINEMA|MOVIE|THEATRE|GAMING|SUBSCRIPTION)\\b") to Categories.ENTERTAINMENT,
            Regex("\\b(CHARGES?|FEE|GST|PENALTY|FINE|SURCHARGE)\\b") to Categories.FEES
        )
    }
}
