package com.kharcha.ledger.engine.merchant

/** Canonical spend categories. Kept small on purpose: users do not sort into 40 buckets. */
object Categories {
    const val FOOD = "Food & Dining"
    const val GROCERIES = "Groceries"
    const val SHOPPING = "Shopping"
    const val TRANSPORT = "Transport"
    const val TRAVEL = "Travel"
    const val FUEL = "Fuel"
    const val BILLS = "Bills & Utilities"
    const val ENTERTAINMENT = "Entertainment"
    const val HEALTH = "Health"
    const val EDUCATION = "Education"
    const val RENT = "Rent"
    const val INSURANCE = "Insurance"
    const val INVESTMENTS = "Investments"
    const val LOANS = "EMI & Loans"
    const val FEES = "Fees & Charges"
    const val CASH = "Cash"
    const val TRANSFERS = "Transfers"
    const val INCOME = "Income"
    const val PERSONAL = "Personal & Family"
    const val OTHER = "Other"

    val all = listOf(
        FOOD, GROCERIES, SHOPPING, TRANSPORT, TRAVEL, FUEL, BILLS, ENTERTAINMENT,
        HEALTH, EDUCATION, RENT, INSURANCE, INVESTMENTS, LOANS, FEES, CASH,
        TRANSFERS, INCOME, PERSONAL, OTHER
    )
}

data class MerchantEntry(
    val canonicalName: String,
    val category: String,
    val aliases: List<String>
)

/**
 * The seed merchant dictionary.
 *
 * Aliases are matched against the *normalized* descriptor, so they are written
 * without punctuation or gateway prefixes. Legal names matter: a Swiggy UPI
 * payment often shows as `BUNDL TECHNOLOGIES`, and an Ola ride as
 * `ANI TECHNOLOGIES` — a user staring at those two strings has no idea what
 * they bought.
 */
object MerchantCatalog {

    val entries: List<MerchantEntry> = listOf(
        MerchantEntry("Swiggy", Categories.FOOD, listOf("SWIGGY", "SWIGGYUPI", "BUNDL TECHNOLOGIES", "SWIGGY LIMITED", "SWIGGY IN")),
        MerchantEntry("Swiggy Instamart", Categories.GROCERIES, listOf("SWIGGY INSTAMART", "INSTAMART")),
        MerchantEntry("Zomato", Categories.FOOD, listOf("ZOMATO", "ZOMATO LIMITED", "ZOMATO ONLINE", "ETERNAL")),
        MerchantEntry("Blinkit", Categories.GROCERIES, listOf("BLINKIT", "GROFERS", "BLINK COMMERCE")),
        MerchantEntry("Zepto", Categories.GROCERIES, listOf("ZEPTO", "KIRANAKART")),
        MerchantEntry("BigBasket", Categories.GROCERIES, listOf("BIGBASKET", "BIG BASKET", "INNOVATIVE RETAIL")),
        MerchantEntry("DMart", Categories.GROCERIES, listOf("DMART", "D MART", "AVENUE SUPERMARTS")),
        MerchantEntry("Reliance Smart", Categories.GROCERIES, listOf("RELIANCE SMART", "RELIANCE RETAIL", "RELIANCE FRESH")),
        MerchantEntry("More Retail", Categories.GROCERIES, listOf("MORE RETAIL", "MORE MEGASTORE")),
        MerchantEntry("Amazon", Categories.SHOPPING, listOf("AMAZON", "AMZN", "AMAZON SELLER", "AMAZON IN", "AMAZON RETAIL", "AMAZONIN")),
        MerchantEntry("Flipkart", Categories.SHOPPING, listOf("FLIPKART", "FKRT", "FLIPKART INTERNET", "FLIPKART PAYMENTS")),
        MerchantEntry("Myntra", Categories.SHOPPING, listOf("MYNTRA", "MYNTRA DESIGNS")),
        MerchantEntry("Ajio", Categories.SHOPPING, listOf("AJIO", "RELIANCE AJIO")),
        MerchantEntry("Meesho", Categories.SHOPPING, listOf("MEESHO", "FASHNEAR")),
        MerchantEntry("Nykaa", Categories.SHOPPING, listOf("NYKAA", "FSN ECOMMERCE")),
        MerchantEntry("Croma", Categories.SHOPPING, listOf("CROMA", "INFINITI RETAIL")),
        MerchantEntry("Decathlon", Categories.SHOPPING, listOf("DECATHLON")),
        MerchantEntry("IKEA", Categories.SHOPPING, listOf("IKEA")),
        MerchantEntry("Ola", Categories.TRANSPORT, listOf("OLA", "OLACABS", "ANI TECHNOLOGIES", "OLA MONEY")),
        MerchantEntry("Uber", Categories.TRANSPORT, listOf("UBER", "UBER INDIA", "UBERRIDES")),
        MerchantEntry("Rapido", Categories.TRANSPORT, listOf("RAPIDO", "ROPPEN TRANSPORTATION")),
        MerchantEntry("Namma Yatri", Categories.TRANSPORT, listOf("NAMMA YATRI", "JUSPAY NAMMA")),
        MerchantEntry("Delhi Metro", Categories.TRANSPORT, listOf("DMRC", "DELHI METRO")),
        MerchantEntry("BMTC", Categories.TRANSPORT, listOf("BMTC", "BENGALURU METRO", "BMRCL")),
        MerchantEntry("IRCTC", Categories.TRAVEL, listOf("IRCTC", "INDIAN RAILWAY", "IRCTC WEB")),
        MerchantEntry("MakeMyTrip", Categories.TRAVEL, listOf("MAKEMYTRIP", "MMT", "MAKE MY TRIP")),
        MerchantEntry("Goibibo", Categories.TRAVEL, listOf("GOIBIBO", "IBIBO")),
        MerchantEntry("RedBus", Categories.TRAVEL, listOf("REDBUS", "IBIBO REDBUS")),
        MerchantEntry("IndiGo", Categories.TRAVEL, listOf("INDIGO", "INTERGLOBE AVIATION", "GOINDIGO")),
        MerchantEntry("Air India", Categories.TRAVEL, listOf("AIR INDIA", "AIRINDIA")),
        MerchantEntry("Indian Oil", Categories.FUEL, listOf("INDIAN OIL", "INDIANOIL", "IOCL", "IOC")),
        MerchantEntry("HP Petrol", Categories.FUEL, listOf("HPCL", "HINDUSTAN PETROLEUM", "HP PETROL")),
        MerchantEntry("Bharat Petroleum", Categories.FUEL, listOf("BPCL", "BHARAT PETROLEUM", "BHARATPETROLEUM")),
        MerchantEntry("Shell", Categories.FUEL, listOf("SHELL", "SHELL INDIA")),
        MerchantEntry("Nayara", Categories.FUEL, listOf("NAYARA", "ESSAR OIL")),
        MerchantEntry("Jio", Categories.BILLS, listOf("JIO", "RELIANCE JIO", "JIO PREPAID", "JIOFIBER")),
        MerchantEntry("Airtel", Categories.BILLS, listOf("AIRTEL", "BHARTI AIRTEL", "AIRTEL PAYMENTS")),
        MerchantEntry("Vi", Categories.BILLS, listOf("VODAFONE", "VODAFONE IDEA", "VI POSTPAID", "IDEA CELLULAR")),
        MerchantEntry("BSNL", Categories.BILLS, listOf("BSNL")),
        MerchantEntry("ACT Fibernet", Categories.BILLS, listOf("ACT FIBERNET", "ATRIA CONVERGENCE")),
        MerchantEntry("Tata Power", Categories.BILLS, listOf("TATA POWER", "TPDDL")),
        MerchantEntry("Adani Electricity", Categories.BILLS, listOf("ADANI ELECTRICITY", "ADANI ELECT")),
        MerchantEntry("BESCOM", Categories.BILLS, listOf("BESCOM", "BANGALORE ELECTRICITY")),
        MerchantEntry("MSEDCL", Categories.BILLS, listOf("MSEDCL", "MAHADISCOM")),
        MerchantEntry("BSES", Categories.BILLS, listOf("BSES", "BSES RAJDHANI", "BSES YAMUNA")),
        MerchantEntry("TNEB", Categories.BILLS, listOf("TNEB", "TANGEDCO")),
        MerchantEntry("Indane Gas", Categories.BILLS, listOf("INDANE", "INDANE GAS", "HP GAS", "BHARATGAS")),
        MerchantEntry("Netflix", Categories.ENTERTAINMENT, listOf("NETFLIX", "NETFLIX COM")),
        MerchantEntry("Disney+ Hotstar", Categories.ENTERTAINMENT, listOf("HOTSTAR", "DISNEY HOTSTAR", "NOVI DIGITAL")),
        MerchantEntry("Amazon Prime", Categories.ENTERTAINMENT, listOf("PRIME VIDEO", "AMAZON PRIME")),
        MerchantEntry("Spotify", Categories.ENTERTAINMENT, listOf("SPOTIFY")),
        MerchantEntry("YouTube Premium", Categories.ENTERTAINMENT, listOf("YOUTUBE", "GOOGLE YOUTUBE")),
        MerchantEntry("SonyLIV", Categories.ENTERTAINMENT, listOf("SONYLIV", "SONY LIV")),
        MerchantEntry("ZEE5", Categories.ENTERTAINMENT, listOf("ZEE5", "ZEE ENTERTAINMENT")),
        MerchantEntry("BookMyShow", Categories.ENTERTAINMENT, listOf("BOOKMYSHOW", "BIGTREE ENTERTAINMENT", "BMS")),
        MerchantEntry("PVR INOX", Categories.ENTERTAINMENT, listOf("PVR", "INOX", "PVR INOX")),
        MerchantEntry("Google Play", Categories.ENTERTAINMENT, listOf("GOOGLE PLAY", "GOOGLEPLAY", "GOOGLE IN")),
        MerchantEntry("Apple", Categories.ENTERTAINMENT, listOf("APPLE", "ITUNES", "APPLE SERVICES")),
        MerchantEntry("Domino's", Categories.FOOD, listOf("DOMINOS", "JUBILANT FOODWORKS")),
        MerchantEntry("McDonald's", Categories.FOOD, listOf("MCDONALDS", "HARDCASTLE RESTAURANTS", "WESTLIFE")),
        MerchantEntry("KFC", Categories.FOOD, listOf("KFC", "DEVYANI INTERNATIONAL")),
        MerchantEntry("Burger King", Categories.FOOD, listOf("BURGER KING", "BURGERKING")),
        MerchantEntry("Starbucks", Categories.FOOD, listOf("STARBUCKS", "TATA STARBUCKS")),
        MerchantEntry("Cafe Coffee Day", Categories.FOOD, listOf("CAFE COFFEE DAY", "CCD", "COFFEE DAY")),
        MerchantEntry("Third Wave Coffee", Categories.FOOD, listOf("THIRD WAVE", "THIRDWAVE")),
        MerchantEntry("Chai Point", Categories.FOOD, listOf("CHAI POINT", "MOUNTAIN TRAIL FOODS")),
        MerchantEntry("Apollo Pharmacy", Categories.HEALTH, listOf("APOLLO PHARMACY", "APOLLO HOSPITALS", "APOLLO")),
        MerchantEntry("PharmEasy", Categories.HEALTH, listOf("PHARMEASY", "API HOLDINGS")),
        MerchantEntry("Tata 1mg", Categories.HEALTH, listOf("1MG", "TATA 1MG", "ONEMG")),
        MerchantEntry("Netmeds", Categories.HEALTH, listOf("NETMEDS")),
        MerchantEntry("Practo", Categories.HEALTH, listOf("PRACTO")),
        MerchantEntry("Cult.fit", Categories.HEALTH, listOf("CULTFIT", "CULT FIT", "CUREFIT")),
        MerchantEntry("Byju's", Categories.EDUCATION, listOf("BYJUS", "THINK AND LEARN")),
        MerchantEntry("Unacademy", Categories.EDUCATION, listOf("UNACADEMY", "SORTING HAT")),
        MerchantEntry("Coursera", Categories.EDUCATION, listOf("COURSERA")),
        MerchantEntry("Udemy", Categories.EDUCATION, listOf("UDEMY")),
        MerchantEntry("LIC", Categories.INSURANCE, listOf("LIC", "LIC OF INDIA", "LIFE INSURANCE CORP")),
        MerchantEntry("HDFC Life", Categories.INSURANCE, listOf("HDFC LIFE", "HDFCLIFE")),
        MerchantEntry("ICICI Prudential", Categories.INSURANCE, listOf("ICICI PRUDENTIAL", "ICICI PRU")),
        MerchantEntry("Star Health", Categories.INSURANCE, listOf("STAR HEALTH")),
        MerchantEntry("Acko", Categories.INSURANCE, listOf("ACKO", "ACKO GENERAL")),
        MerchantEntry("Policybazaar", Categories.INSURANCE, listOf("POLICYBAZAAR", "PB FINTECH")),
        MerchantEntry("Zerodha", Categories.INVESTMENTS, listOf("ZERODHA", "ZERODHA BROKING", "COIN ZERODHA")),
        MerchantEntry("Groww", Categories.INVESTMENTS, listOf("GROWW", "NEXTBILLION")),
        MerchantEntry("Upstox", Categories.INVESTMENTS, listOf("UPSTOX", "RKSV SECURITIES")),
        MerchantEntry("SBI Mutual Fund", Categories.INVESTMENTS, listOf("SBI MUTUAL", "SBI MF", "SBIMF")),
        MerchantEntry("HDFC AMC", Categories.INVESTMENTS, listOf("HDFC AMC", "HDFC MUTUAL", "HDFC MF")),
        MerchantEntry("Nippon India MF", Categories.INVESTMENTS, listOf("NIPPON INDIA", "NIPPON MF", "RELIANCE MF")),
        MerchantEntry("Axis Mutual Fund", Categories.INVESTMENTS, listOf("AXIS MUTUAL", "AXIS MF")),
        MerchantEntry("CAMS", Categories.INVESTMENTS, listOf("CAMS", "CAMSONLINE")),
        MerchantEntry("KFintech", Categories.INVESTMENTS, listOf("KFINTECH", "KARVY")),
        MerchantEntry("NoBroker", Categories.RENT, listOf("NOBROKER", "NOBROKER RENT")),
        MerchantEntry("Urban Company", Categories.PERSONAL, listOf("URBAN COMPANY", "URBANCLAP")),
        MerchantEntry("CRED", Categories.FEES, listOf("CRED", "DREAMPLUG")),
        MerchantEntry("Paytm", Categories.OTHER, listOf("PAYTM", "ONE97", "PAYTM SERVICES"))
    )

    private val aliasIndex: Map<String, MerchantEntry> = buildAliasIndex(entries)

    private fun buildAliasIndex(source: List<MerchantEntry>): Map<String, MerchantEntry> {
        val index = mutableMapOf<String, MerchantEntry>()
        source.forEach { entry ->
            index[entry.canonicalName.uppercase()] = entry
            entry.aliases.forEach { alias -> index[alias.uppercase()] = entry }
        }
        return index
    }

    fun lookupExact(normalized: String): MerchantEntry? = aliasIndex[normalized.uppercase()]

    /**
     * Descriptors bury the brand inside noise (`PAYTM SWIGGY BANGALORE`), so an
     * exact miss falls back to the longest alias contained in the descriptor —
     * longest first, so `SWIGGY INSTAMART` beats `SWIGGY`.
     */
    fun lookupContained(normalized: String): MerchantEntry? {
        val haystack = normalized.uppercase()
        return aliasIndex.entries
            .filter { (alias, _) -> alias.length >= 4 && containsToken(haystack, alias) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    private fun containsToken(haystack: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return false
            val before = if (at == 0) ' ' else haystack[at - 1]
            val afterIndex = at + needle.length
            val after = if (afterIndex >= haystack.length) ' ' else haystack[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = at + 1
        }
    }
}
