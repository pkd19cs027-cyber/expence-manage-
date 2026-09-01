package com.kharcha.ledger.engine.merchant

data class NormalizedMerchant(
    val canonicalName: String,
    val normalizedKey: String,
    val category: String?,
    val known: Boolean,
    val confidence: Double
)

/**
 * Turns a payment descriptor into a stable merchant identity.
 *
 * `UPI/SWIGGY/4256`, `RAZORPAY*SWIGGY`, `WWW SWIGGY IN` and `SWIGGYUPI` all
 * describe the same shop. Until they collapse to one key, duplicate detection is
 * weaker, categories have to be re-taught per spelling, and the spending report
 * shows the same merchant four times.
 */
object MerchantNormalizer {

    /** Payment gateways prepend themselves to the real merchant name. */
    private val GATEWAY_PREFIXES = listOf(
        "RAZORPAY", "RAZP", "RZP", "PAYU", "PAYUMONEY", "BILLDESK", "BILL DESK", "CCAVENUE",
        "INSTAMOJO", "EAZYPAY", "PINELABS", "PINE LABS", "PAYTM", "PHONEPE", "GPAY",
        "GOOGLEPAY", "BHARATPE", "CASHFREE", "JUSPAY", "ATOM", "TECHPROCESS", "WORLDLINE",
        "MSWIPE", "EZETAP", "PAYPHI", "PAYNIMO", "SABPAISA", "BIL", "POS", "ECOM", "NBIL"
    )

    private val LEGAL_SUFFIXES = listOf(
        "PRIVATE LIMITED", "PVT LTD", "PVT LIMITED", "PRIVATE LTD", "LIMITED", "LTD",
        "LLP", "INC", "CORP", "CORPORATION", "COMPANY", "ENTERPRISES", "SERVICES",
        "TECHNOLOGIES", "TECHNOLOGY", "SOLUTIONS", "RETAIL", "STORES", "STORE", "INDIA"
    )

    private val NOISE_TOKENS = setOf(
        "UPI", "P2M", "P2A", "PAYMENT", "PAY", "TXN", "TRANSACTION", "PURCHASE", "ONLINE",
        "WWW", "COM", "IN", "CO", "NET", "ORG", "APP", "MERCHANT", "COLLECT", "AUTOPAY",
        "MANDATE", "REF", "NA", "NULL", "OTHERS", "MISC"
    )

    fun normalize(raw: String?): NormalizedMerchant? {
        if (raw.isNullOrBlank()) return null
        val key = normalizeKey(raw)
        if (key.isBlank()) return null

        MerchantCatalog.lookupExact(key)?.let {
            return NormalizedMerchant(it.canonicalName, key, it.category, true, 0.97)
        }
        MerchantCatalog.lookupContained(key)?.let {
            return NormalizedMerchant(it.canonicalName, key, it.category, true, 0.85)
        }
        return NormalizedMerchant(titleCase(key), key, null, false, 0.5)
    }

    /**
     * The normalized key is what duplicate detection compares and what user
     * corrections are stored against, so it must be stable across spellings:
     * uppercase, punctuation-free, gateway- and suffix-stripped, digit-free.
     */
    fun normalizeKey(raw: String): String {
        var s = raw.uppercase()

        s = s.replace(Regex("[@][A-Z0-9.\\-_]+"), " ")           // VPA handles
        s = s.replace(Regex("[^A-Z0-9 ]"), " ")                   // punctuation -> space
        s = s.replace(Regex("\\b\\d{4,}\\b"), " ")                // reference numbers
        s = s.replace(Regex("\\s+"), " ").trim()

        for (prefix in GATEWAY_PREFIXES) {
            if (s == prefix) return prefix                        // the gateway *is* the merchant
            if (s.startsWith("$prefix ")) s = s.removePrefix("$prefix ").trim()
        }

        var changed = true
        while (changed) {
            changed = false
            for (suffix in LEGAL_SUFFIXES) {
                if (s.endsWith(" $suffix")) {
                    s = s.removeSuffix(" $suffix").trim()
                    changed = true
                }
            }
        }

        val tokens = s.split(" ").filter { it.isNotBlank() && it !in NOISE_TOKENS }
        val cleaned = tokens.joinToString(" ").trim()

        // Descriptors like `SWIGGYUPI` glue the rail onto the brand.
        return cleaned.removeSuffix("UPI").trim().ifBlank { s }
    }

    fun titleCase(key: String): String = key.split(" ").joinToString(" ") { word ->
        when {
            word.length <= 3 && word.all { it.isLetter() } -> word          // DMRC, KFC, LIC
            else -> word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
