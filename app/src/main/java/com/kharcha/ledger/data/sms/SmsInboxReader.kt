package com.kharcha.ledger.data.sms

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.kharcha.ledger.engine.model.RawMessage
import com.kharcha.ledger.engine.model.SourceType
import java.time.Instant
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the SMS inbox. Nothing here writes, deletes or forwards anything: the
 * app is a reader of messages the bank already sent.
 */
@Singleton
class SmsInboxReader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param since only messages received after this instant, so incremental
     *        imports stay cheap on a phone with 20,000 messages.
     * @param limit a hard cap, because the first import on an old phone must not
     *        run for minutes.
     */
    fun read(since: Instant? = null, limit: Int = 5_000): List<RawMessage> {
        if (!hasPermission()) return emptyList()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        val selection = since?.let { "${Telephony.Sms.DATE} > ?" }
        val args = since?.let { arrayOf(it.toEpochMilli().toString()) }

        val messages = mutableListOf<RawMessage>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            args,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (cursor.moveToNext()) {
                val body = cursor.getString(bodyColumn) ?: continue
                messages += RawMessage(
                    id = cursor.getString(idColumn) ?: continue,
                    sender = cursor.getString(addressColumn).orEmpty(),
                    body = body,
                    receivedAt = Instant.ofEpochMilli(cursor.getLong(dateColumn)),
                    sourceType = SourceType.SMS
                )
            }
        }
        return messages.sortedBy { it.receivedAt }
    }
}
