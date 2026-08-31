package com.kharcha.ledger.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.kharcha.ledger.work.WorkScheduler

/**
 * Wakes the importer when a new SMS arrives.
 *
 * Deliberately, the message text is *not* passed to the worker. WorkManager
 * persists task inputs to its own unencrypted database, so instead the receiver
 * only says "something new arrived" and the worker re-reads the inbox into the
 * encrypted store. A short delay covers the gap before the default SMS app has
 * written the message to the provider.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val looksFinancial = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                .any { message -> message?.messageBody?.let(::mentionsMoney) == true }
        }.getOrDefault(true)

        if (looksFinancial) {
            WorkScheduler.enqueueIncrementalImport(context)
        }
    }

    /** A cheap pre-filter; the real classification happens in the engine. */
    private fun mentionsMoney(body: String): Boolean =
        MONEY.containsMatchIn(body.uppercase())

    private companion object {
        val MONEY = Regex("(RS\\.?|INR|₹)\\s*\\d|DEBITED|CREDITED|SPENT|WITHDRAWN")
    }
}
