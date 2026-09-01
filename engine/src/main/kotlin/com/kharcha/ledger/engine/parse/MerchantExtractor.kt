package com.kharcha.ledger.engine.parse

data class ExtractedCounterparty(
    val raw: String,
    val vpa: String? = null,
    val rule: String
)

/**
 * Pulls the "who" out of a transaction message.
 *
 * Indian descriptors are written for the bank's ledger, not for humans:
 * `UPI-SWIGGY-SWIGGY@YBL-HDFC0000001-425678901234-PAYMENT FROM PHONE`.
 * The job here is only to isolate the counterparty span; turning it into
 * "Swiggy" is [com.kharcha.ledger.engine.merchant.MerchantNormalizer]'s work.
 */
object MerchantExtractor {

    private val VPA = Regex("\\b([A-Za-z0-9._\\-]{2,})@([A-Za-z]{2,})\\b")

    /**
     * Words that end a merchant span. Descriptors run straight into the rest of
     * the sentence, so the span has to be cut at the first banking keyword.
     */
    private val TERMINATORS = listOf(
        " ON ", " ON-", " REF ", " REF.", " REF:", " REF-", " UPI ", " UPI-", " UPI/", " AVL ",
        " A/C", " AC ", " ACCT", " ACCOUNT", " NOT ", " IF ", " CALL ", " DT ", " DATED ",
        " YOUR ", " TXN", " INFO", " BAL", " RS", " INR", " VIA ", " AT ", " FROM ", " TO ",
        " WITH ", " IS ", " WAS ", " HAS ", " AND ", " CARD", " SMS ", " TOTAL", " THANK",
        " CLICK", " HTTP", " DOWNLOAD", " NEVER", " CUSTOMER", " HELPLINE", " BLOCK"
    )

    private val RULES: List<Pair<Regex, String>> = listOf(
        // HDFC: "Info: UPI-SWIGGY", ICICI: "Info: BIL*BESCOM"
        Regex("\\bINFO\\s*[:\\-]\\s*([^.;]{2,60})", RegexOption.IGNORE_CASE) to "info",
        // HDFC UPI: "UPI/425678901234/SWIGGY" or "UPI/P2M/425678901234/SWIGGY"
        Regex("\\bUPI[/\\-](?:P2M[/\\-]|P2A[/\\-])?\\d{6,22}[/\\-]([A-Za-z][^/\\-;.]{1,40})") to "upi-slash",
        // "UPI-SWIGGY-SWIGGY@YBL-..."
        Regex("\\bUPI[\\-/]([A-Za-z][A-Za-z0-9 .&']{1,40})") to "upi-dash",
        Regex("\\b(?:TOWARDS|IN FAVOUR OF|FAVOURING)\\s+([A-Za-z][^.;]{1,60})", RegexOption.IGNORE_CASE) to "towards",
        Regex("\\b(?:TRF TO|TRANSFERRED TO|SENT TO|PAID TO|PAYMENT TO)\\s+([A-Za-z][^.;]{1,60})", RegexOption.IGNORE_CASE) to "paid-to",
        Regex("\\bAT\\s+([A-Za-z][^.;]{1,60})", RegexOption.IGNORE_CASE) to "at",
        Regex("\\b(?:CREDITED BY|RECEIVED FROM|FROM)\\s+([A-Za-z][^.;]{1,60})", RegexOption.IGNORE_CASE) to "from",
        Regex("\\bTO\\s+([A-Za-z][^.;]{1,60})", RegexOption.IGNORE_CASE) to "to"
    )

    fun extract(normalizedBody: String): ExtractedCounterparty? {
        val vpa = VPA.find(normalizedBody)?.value

        for ((regex, name) in RULES) {
            val candidate = regex.find(normalizedBody)?.groupValues?.get(1) ?: continue
            val cleaned = trimSpan(candidate)
            if (isUsable(cleaned)) return ExtractedCounterparty(cleaned, vpa, name)
        }

        if (vpa != null) {
            val handle = vpa.substringBefore('@')
            if (isUsable(handle)) return ExtractedCounterparty(handle, vpa, "vpa")
        }
        return null
    }

    private fun trimSpan(raw: String): String {
        var span = " " + raw.trim().replace(Regex("\\s+"), " ")
        val upper = span.uppercase()
        var cut = span.length
        for (terminator in TERMINATORS) {
            val idx = upper.indexOf(terminator, startIndex = 1)
            if (idx in 1 until cut) cut = idx
        }
        span = span.substring(0, cut)
        return span.trim().trim('-', '*', '/', ':', ',', '.', '\'', '"')
    }

    private fun isUsable(candidate: String): Boolean {
        if (candidate.length < 2) return false
        if (candidate.none { it.isLetter() }) return false
        val upper = candidate.uppercase()
        if (upper in NON_MERCHANTS) return false
        // A span that is mostly digits is a reference number, not a shop.
        val digits = candidate.count { it.isDigit() }
        return digits <= candidate.length / 2
    }

    private val NON_MERCHANTS = setOf(
        "YOU", "YOUR", "THE", "A", "AN", "SELF", "ME", "BANK", "ACCOUNT", "CARD",
        "UPI", "IMPS", "NEFT", "RTGS", "ATM", "CASH", "PAYMENT", "TRANSACTION", "TXN",
        "DEBIT", "CREDIT", "SUCCESS", "SUCCESSFUL", "AVAILABLE", "BALANCE"
    )
}
