package com.kharcha.ledger.engine.learn

import com.kharcha.ledger.engine.model.TransactionKind

/**
 * A user correction, stored locally, that the engine consults on every later
 * message. This is the whole "the app learns" mechanism: no model training, no
 * server — just remembering what the user already told us once.
 */
data class UserRule(
    val id: String,
    val matchKind: MatchKind,
    val matchValue: String,
    val action: Action,
    val actionValue: String,
    val confidence: Double = 1.0,
    val createdAt: java.time.Instant = java.time.Instant.now()
) {
    enum class MatchKind { MERCHANT_KEY, SENDER, ACCOUNT_KEY, DESCRIPTOR_CONTAINS }
    enum class Action { SET_CATEGORY, SET_MERCHANT, SET_KIND, IGNORE }
}

/** Read side of the learned-rule set, consulted by the parsing pipeline. */
interface RuleStore {
    fun categoryFor(merchantKey: String): String?
    fun merchantFor(merchantKey: String): String?
    fun kindFor(merchantKey: String): TransactionKind?
    fun isIgnored(merchantKey: String): Boolean
    fun rules(): List<UserRule>

    companion object {
        fun empty(): RuleStore = InMemoryRuleStore(emptyList())
    }
}

class InMemoryRuleStore(initial: List<UserRule> = emptyList()) : RuleStore {

    private val byMerchant: MutableMap<String, MutableList<UserRule>> = mutableMapOf()
    private val all: MutableList<UserRule> = mutableListOf()

    init {
        initial.forEach { add(it) }
    }

    fun add(rule: UserRule) {
        all += rule
        if (rule.matchKind == UserRule.MatchKind.MERCHANT_KEY) {
            byMerchant.getOrPut(rule.matchValue.uppercase()) { mutableListOf() }.add(rule)
        }
    }

    private fun find(merchantKey: String, action: UserRule.Action): UserRule? =
        byMerchant[merchantKey.uppercase()]?.lastOrNull { it.action == action }

    override fun categoryFor(merchantKey: String): String? =
        find(merchantKey, UserRule.Action.SET_CATEGORY)?.actionValue

    override fun merchantFor(merchantKey: String): String? =
        find(merchantKey, UserRule.Action.SET_MERCHANT)?.actionValue

    override fun kindFor(merchantKey: String): TransactionKind? =
        find(merchantKey, UserRule.Action.SET_KIND)?.actionValue?.let {
            runCatching { TransactionKind.valueOf(it) }.getOrNull()
        }

    override fun isIgnored(merchantKey: String): Boolean =
        find(merchantKey, UserRule.Action.IGNORE) != null

    override fun rules(): List<UserRule> = all.toList()
}
