package com.kharcha.ledger.engine.parse

import com.kharcha.ledger.engine.model.ReferenceKind

data class ExtractedReference(val value: String, val kind: ReferenceKind)

/**
 * Reference numbers are the strongest duplicate signal that exists: two messages
 * carrying UPI ref `425678901234` are the same payment, full stop, regardless of
 * who sent them or how they worded it.
 *
 * Extraction is deliberately conservative — a wrong reference merges two real
 * payments into one and silently loses money from the ledger.
 */
object ReferenceExtractor {

    private data class Rule(val regex: Regex, val kind: ReferenceKind, val group: Int = 1)

    private val RULES = listOf(
        Rule(
            Regex("\\bUPI\\s*(?:REF(?:ERENCE)?|TXN|TRANSACTION|RRN)?\\s*(?:NO\\.?|ID|#)?\\s*[:.\\-]?\\s*(\\d{9,22})\\b"),
            ReferenceKind.UPI_REF
        ),
        // `UPI/425678901234/SWIGGY` and `UPI/P2M/425678901234/SWIGGY`
        Rule(Regex("\\bUPI[/:\\-](?:[A-Z0-9]{1,8}[/:\\-])?(\\d{9,22})\\b"), ReferenceKind.UPI_REF),
        Rule(Regex("\\bUTR\\s*(?:NO\\.?|ID|#)?\\s*[:.\\-]?\\s*([A-Z0-9]{9,22})\\b"), ReferenceKind.UTR),
        Rule(Regex("\\bRRN\\s*(?:NO\\.?|#)?\\s*[:.\\-]?\\s*(\\d{9,16})\\b"), ReferenceKind.RRN),
        Rule(
            Regex("\\b(?:IMPS|NEFT|RTGS)\\s*(?:REF(?:ERENCE)?|TXN)?\\s*(?:NO\\.?|ID|#)?\\s*[:.\\-]?\\s*([A-Z0-9]{8,22})\\b"),
            ReferenceKind.UTR
        ),
        Rule(
            Regex("\\b(?:REF(?:ERENCE)?|TXN|TRANSACTION|TRAN)\\s*(?:NO\\.?|ID|#)?\\s*[:.\\-]?\\s*([A-Z0-9]{6,22})\\b"),
            ReferenceKind.TXN_ID
        ),
        Rule(Regex("\\bCHEQUE\\s*(?:NO\\.?)?\\s*[:.\\-]?\\s*(\\d{5,10})\\b"), ReferenceKind.CHEQUE)
    )

    private val PRIORITY = listOf(
        ReferenceKind.UPI_REF, ReferenceKind.UTR, ReferenceKind.RRN,
        ReferenceKind.CHEQUE, ReferenceKind.TXN_ID
    )

    fun extract(upperBody: String): ExtractedReference? {
        val found = mutableListOf<ExtractedReference>()
        for (rule in RULES) {
            val m = rule.regex.find(upperBody) ?: continue
            val value = m.groupValues.getOrNull(rule.group)?.trim().orEmpty()
            if (!isPlausible(value)) continue
            found += ExtractedReference(value, rule.kind)
        }
        if (found.isEmpty()) return null
        return found.minByOrNull { PRIORITY.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }
    }

    private fun isPlausible(value: String): Boolean {
        if (value.length < 6) return false
        if (value.none { it.isDigit() }) return false
        // Reject dates and years masquerading as references.
        if (value.length <= 8 && value.all { it.isDigit() } && value.startsWith("20")) return false
        return true
    }
}
