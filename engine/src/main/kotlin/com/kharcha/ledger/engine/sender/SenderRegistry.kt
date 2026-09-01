package com.kharcha.ledger.engine.sender

import com.kharcha.ledger.engine.model.Institution

/**
 * Resolves Indian transactional sender IDs to institutions.
 *
 * TRAI headers arrive as `<2-letter operator/circle>-<6-char header>` and often
 * carry a trailing service class: `VM-HDFCBK`, `AD-SBIINB-S`, `JD-ICICIT-T`.
 * Older devices sometimes surface the bare header, and DLT registrations differ
 * per circle, so the same bank reaches one phone as `HDFCBK` and another as
 * `AD-HDFCBK-S`.
 *
 * A resolved sender is a *hint*, never a decision: the pipeline still requires
 * message structure and an account number before it books anything.
 */
object SenderRegistry {

    private val BANK = Institution.Kind.BANK
    private val CARD = Institution.Kind.CARD_ISSUER
    private val UPI_APP = Institution.Kind.UPI_APP
    private val WALLET = Institution.Kind.WALLET
    private val NBFC = Institution.Kind.NBFC
    private val BROKER = Institution.Kind.BROKER

    val institutions: Map<String, Institution> = listOf(
        Institution("HDFC", "HDFC Bank", BANK),
        Institution("ICICI", "ICICI Bank", BANK),
        Institution("SBI", "State Bank of India", BANK),
        Institution("AXIS", "Axis Bank", BANK),
        Institution("KOTAK", "Kotak Mahindra Bank", BANK),
        Institution("FEDERAL", "Federal Bank", BANK),
        Institution("CANARA", "Canara Bank", BANK),
        Institution("BOB", "Bank of Baroda", BANK),
        Institution("PNB", "Punjab National Bank", BANK),
        Institution("UNION", "Union Bank of India", BANK),
        Institution("INDIAN", "Indian Bank", BANK),
        Institution("IOB", "Indian Overseas Bank", BANK),
        Institution("CBI", "Central Bank of India", BANK),
        Institution("IDFC", "IDFC FIRST Bank", BANK),
        Institution("INDUSIND", "IndusInd Bank", BANK),
        Institution("YES", "YES Bank", BANK),
        Institution("IDBI", "IDBI Bank", BANK),
        Institution("RBL", "RBL Bank", BANK),
        Institution("AUBANK", "AU Small Finance Bank", BANK),
        Institution("BANDHAN", "Bandhan Bank", BANK),
        Institution("UCO", "UCO Bank", BANK),
        Institution("SIB", "South Indian Bank", BANK),
        Institution("KVB", "Karur Vysya Bank", BANK),
        Institution("CSB", "CSB Bank", BANK),
        Institution("DCB", "DCB Bank", BANK),
        Institution("JK", "J&K Bank", BANK),
        Institution("PAYTMBANK", "Paytm Payments Bank", BANK),
        Institution("AIRTELBANK", "Airtel Payments Bank", BANK),
        Institution("FINOBANK", "Fino Payments Bank", BANK),
        Institution("HSBC", "HSBC India", BANK),
        Institution("CITI", "Citibank", BANK),
        Institution("SCB", "Standard Chartered", BANK),
        Institution("DBS", "DBS Bank India", BANK),
        Institution("SBICARD", "SBI Card", CARD),
        Institution("AMEX", "American Express", CARD),
        Institution("ONECARD", "OneCard", CARD),
        Institution("GPAY", "Google Pay", UPI_APP),
        Institution("PHONEPE", "PhonePe", UPI_APP),
        Institution("PAYTM", "Paytm", UPI_APP),
        Institution("BHIM", "BHIM", UPI_APP),
        Institution("AMAZONPAY", "Amazon Pay", WALLET),
        Institution("MOBIKWIK", "MobiKwik", WALLET),
        Institution("FREECHARGE", "Freecharge", WALLET),
        Institution("CRED", "CRED", UPI_APP),
        Institution("SLICE", "slice", NBFC),
        Institution("JUPITER", "Jupiter", NBFC),
        Institution("FI", "Fi Money", NBFC),
        Institution("NAVI", "Navi", NBFC),
        Institution("BAJAJ", "Bajaj Finserv", NBFC),
        Institution("HDFCSEC", "HDFC Securities", BROKER),
        Institution("ZERODHA", "Zerodha", BROKER),
        Institution("GROWW", "Groww", BROKER),
        Institution("UPSTOX", "Upstox", BROKER),
        Institution("CAMS", "CAMS", BROKER),
        Institution("KFINTECH", "KFintech", BROKER),
        Institution("CASH", "Cash", Institution.Kind.UNKNOWN)
    ).associateBy { it.id }

    /**
     * DLT headers to institution ids. Multiple headers legitimately map to one
     * institution: HDFC alone sends as HDFCBK, HDFCBN, HDFCB and HDFCSL.
     */
    private val headerToInstitution: Map<String, String> = buildMap<String, String> {
        fun register(id: String, vararg headers: String) {
            headers.forEach { header -> put(header, id) }
        }

        register("HDFC", "HDFCBK", "HDFCBN", "HDFCB", "HDFCBANK", "HDFCSL", "HDFCPG", "HDFCUPI")
        register("ICICI", "ICICIB", "ICICIT", "ICICIBANK", "ICICIS", "ICICIU", "ICICIP")
        register("SBI", "SBIINB", "SBIUPI", "SBIBNK", "SBIPSG", "ATMSBI", "SBIBK", "CBSSBI", "SBIACC")
        register("AXIS", "AXISBK", "AXISB", "AXISBANK", "AXISCD")
        register("KOTAK", "KOTAKB", "KOTAKM", "KMBANK", "KOTAK")
        register("FEDERAL", "FEDBNK", "FEDERL", "FEDBANK")
        register("CANARA", "CANBNK", "CANARA", "CANBK")
        register("BOB", "BOBTXN", "BOBSMS", "BOBIBN", "BOBCRD")
        register("PNB", "PNBSMS", "PNBBNK", "PUNBNK")
        register("UNION", "UNIONB", "UBIN", "UNIONBK")
        register("INDIAN", "INDBNK", "INDIANBK")
        register("IOB", "IOBCHN", "IOBBNK")
        register("CBI", "CENTBK", "CBIN")
        register("IDFC", "IDFCFB", "IDFCBK", "IDFCFIRST")
        register("INDUSIND", "INDUSB", "INDBNKI", "INDUSIND")
        register("YES", "YESBNK", "YESBK")
        register("IDBI", "IDBIBK", "IDBIBANK")
        register("RBL", "RBLBNK", "RBLBK", "RBLCRD")
        register("AUBANK", "AUBANK", "AUSFBL")
        register("BANDHAN", "BDNBNK", "BANDHN")
        register("UCO", "UCOBNK")
        register("SIB", "SIBSMS")
        register("KVB", "KVBANK", "KVBTXN")
        register("CSB", "CSBBNK")
        register("DCB", "DCBBNK")
        register("JK", "JKBANK")
        register("PAYTMBANK", "PYTMBK", "PAYTMB")
        register("AIRTELBANK", "AIRBNK", "APBANK")
        register("FINOBANK", "FINOBK")
        register("HSBC", "HSBCIN", "HSBCBK")
        register("CITI", "CITIBK", "CITIBANK")
        register("SCB", "SCBANK", "SCBIND", "STANCH")
        register("DBS", "DBSBNK", "DBSBANK")
        register("SBICARD", "SBICRD", "SBICARD")
        register("AMEX", "AMEXIN", "AMEX")
        register("ONECARD", "ONECRD", "ONECARD")
        register("GPAY", "GOOGLE", "GPAY", "GOOGPY", "GPAYIN")
        register("PHONEPE", "PHONPE", "PHONEPE", "PHNPE")
        register("PAYTM", "PAYTMS", "PAYTM", "PYTMPB")
        register("BHIM", "BHIMPE", "BHIMUP", "NPCIBH")
        register("AMAZONPAY", "AMZNPY", "AMAZON", "AMZPAY")
        register("MOBIKWIK", "MBKWIK", "MOBIKW")
        register("FREECHARGE", "FRCHRG", "FREECH")
        register("CRED", "CREDCB", "CREDIT", "CREDPY")
        register("SLICE", "SLICEI", "SLICEC")
        register("JUPITER", "JUPITR")
        register("FI", "FIMNEY", "EPIFIM")
        register("NAVI", "NAVIIN")
        register("BAJAJ", "BAJAJF", "BFLTXN")
        register("HDFCSEC", "HDFCSC")
        register("ZERODHA", "ZERODH", "ZRDHA")
        register("GROWW", "GROWWI", "GROWW")
        register("UPSTOX", "UPSTOX")
        register("CAMS", "CAMSIN", "CAMSMF")
        register("KFINTECH", "KFINTC", "KARVYM")
    }

    /** Body phrases used when the sender header is unknown or masked. */
    private val bodyHints: List<Pair<Regex, String>> = listOf(
        Regex("\\bHDFC\\b") to "HDFC",
        Regex("\\bICICI\\b") to "ICICI",
        Regex("\\bSBI\\b|STATE BANK OF INDIA") to "SBI",
        Regex("\\bAXIS\\b") to "AXIS",
        Regex("\\bKOTAK\\b") to "KOTAK",
        Regex("FEDERAL BANK") to "FEDERAL",
        Regex("CANARA BANK") to "CANARA",
        Regex("BANK OF BARODA|\\bBOB\\b") to "BOB",
        Regex("PUNJAB NATIONAL|\\bPNB\\b") to "PNB",
        Regex("UNION BANK") to "UNION",
        Regex("\\bIDFC\\b") to "IDFC",
        Regex("INDUSIND") to "INDUSIND",
        Regex("\\bYES BANK\\b") to "YES",
        Regex("\\bIDBI\\b") to "IDBI",
        Regex("\\bRBL\\b") to "RBL",
        Regex("GOOGLE PAY|\\bGPAY\\b") to "GPAY",
        Regex("PHONEPE") to "PHONEPE",
        Regex("\\bPAYTM\\b") to "PAYTM",
        Regex("AMAZON PAY") to "AMAZONPAY",
        Regex("SBI ?CARD") to "SBICARD"
    )

    /**
     * Strips the operator prefix and service suffix from a TRAI header.
     * `AD-HDFCBK-S` and `VM-HDFCBK` both reduce to `HDFCBK`.
     */
    fun normalizeSenderId(sender: String): String {
        var s = sender.trim().uppercase()
        // Numeric senders (long codes) are returned as-is; they carry no header.
        if (s.any { it.isDigit() } && s.none { it.isLetter() }) return s
        s = s.replace(Regex("^[A-Z]{2}[-.]"), "")
        s = s.replace(Regex("[-.][A-Z]$"), "")
        return s.replace(Regex("[^A-Z0-9]"), "")
    }

    /** Resolves an institution from the sender header alone. */
    fun fromSender(sender: String): Institution? {
        val header = normalizeSenderId(sender)
        if (header.isEmpty()) return null
        headerToInstitution[header]?.let { return institutions[it] }
        // Some circles append a digit or extra letter to the registered header.
        val relaxed = headerToInstitution.entries.firstOrNull { (key, _) ->
            header.length >= 6 && header.startsWith(key)
        }
        return relaxed?.let { institutions[it.value] }
    }

    /** Resolves purely from phrases in the message text. */
    fun fromBody(upperBody: String): Institution? {
        val hit = bodyHints.firstOrNull { it.first.containsMatchIn(upperBody) } ?: return null
        return institutions[hit.second]
    }

    /** Resolves from sender first, then from phrases in the body. */
    fun resolve(sender: String, upperBody: String): Institution? =
        fromSender(sender) ?: fromBody(upperBody)

    fun byId(id: String?): Institution =
        id?.let { institutions[it] } ?: Institution.UNKNOWN

    fun displayName(id: String?): String = byId(id).displayName
}
