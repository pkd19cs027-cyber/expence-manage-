package com.kharcha.ledger.engine.reconcile

import com.kharcha.ledger.engine.model.AccountRef
import com.kharcha.ledger.engine.model.AccountType
import com.kharcha.ledger.engine.model.CandidateTransaction
import com.kharcha.ledger.engine.sender.SenderRegistry
import java.time.Instant

data class AccountObservation(
    val ref: AccountRef,
    val occurrences: Int,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val sawBalance: Boolean,
    val suggestedName: String
) {
    /**
     * A number that shows up repeatedly in messages the bank sent *to this
     * phone*, especially with a balance attached, is almost certainly the user's
     * own account rather than a payee they typed once.
     */
    val looksLikeUserAccount: Boolean
        get() = sawBalance || occurrences >= 2
}

/**
 * Tracks which accounts belong to the user.
 *
 * Everything interesting downstream depends on this: money moving between two
 * of the user's own accounts is a transfer, not ₹10,000 of spending plus
 * ₹10,000 of income. The app discovers candidates automatically and asks once.
 */
class SelfAccountRegistry(
    confirmed: Set<String> = emptySet(),
    rejected: Set<String> = emptySet()
) {
    private val confirmedKeys = confirmed.toMutableSet()
    private val rejectedKeys = rejected.toMutableSet()

    fun isSelf(ref: AccountRef?): Boolean {
        if (ref == null) return false
        if (ref.type == AccountType.CASH) return true
        return ref.key in confirmedKeys
    }

    fun confirm(ref: AccountRef) {
        confirmedKeys += ref.key
        rejectedKeys -= ref.key
    }

    fun reject(ref: AccountRef) {
        rejectedKeys += ref.key
        confirmedKeys -= ref.key
    }

    fun isRejected(ref: AccountRef): Boolean = ref.key in rejectedKeys

    fun confirmed(): Set<String> = confirmedKeys.toSet()

    companion object {
        /** Rolls up every account seen in a batch of candidates, for the "are these yours?" screen. */
        fun discover(candidates: List<CandidateTransaction>): List<AccountObservation> {
            val byKey = mutableMapOf<String, AccountObservation>()
            candidates.forEach { candidate ->
                listOfNotNull(candidate.account, candidate.counterAccount).forEach { ref ->
                    if (ref.type == AccountType.CASH) return@forEach
                    if (ref.institutionId == "UNKNOWN") return@forEach
                    val sawBalance = candidate.balanceAfter != null && ref == candidate.account
                    val existing = byKey[ref.key]
                    byKey[ref.key] = if (existing == null) {
                        AccountObservation(
                            ref = ref,
                            occurrences = 1,
                            firstSeen = candidate.timestamp,
                            lastSeen = candidate.timestamp,
                            sawBalance = sawBalance,
                            suggestedName = suggestName(ref)
                        )
                    } else {
                        existing.copy(
                            occurrences = existing.occurrences + 1,
                            firstSeen = minOf(existing.firstSeen, candidate.timestamp),
                            lastSeen = maxOf(existing.lastSeen, candidate.timestamp),
                            sawBalance = existing.sawBalance || sawBalance
                        )
                    }
                }
            }
            return byKey.values.sortedByDescending { it.occurrences }
        }

        fun suggestName(ref: AccountRef): String {
            val institution = SenderRegistry.displayName(ref.institutionId)
            val suffix = "•${ref.last4}"
            return when (ref.type) {
                AccountType.CREDIT_CARD -> "$institution Credit Card $suffix"
                AccountType.PREPAID_WALLET -> "$institution Wallet"
                AccountType.CASH -> "Cash"
                AccountType.CURRENT -> "$institution Current $suffix"
                AccountType.LOAN -> "$institution Loan $suffix"
                else -> "$institution Savings $suffix"
            }
        }
    }
}
