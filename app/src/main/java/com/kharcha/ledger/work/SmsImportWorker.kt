package com.kharcha.ledger.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kharcha.ledger.data.repository.LedgerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Reads new messages and folds them into the ledger.
 *
 * The worker carries no message text in its input data — WorkManager keeps that
 * in its own unencrypted database — only a flag saying how much of the inbox to
 * re-read.
 */
@HiltWorker
class SmsImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LedgerRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val full = inputData.getBoolean(KEY_FULL, false)
        val summary = if (full) repository.importFullInbox() else repository.importIncremental()
        Result.success(
            workDataOf(
                KEY_NEW to summary.newTransactions,
                KEY_MERGED to summary.mergedIntoExisting,
                KEY_REVIEW to summary.reviewItems
            )
        )
    }.getOrElse { error ->
        // A failed import must never lose messages: they are simply not marked
        // processed, so the next run picks them up again.
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val KEY_FULL = "full"
        const val KEY_NEW = "new_transactions"
        const val KEY_MERGED = "merged"
        const val KEY_REVIEW = "review_items"

        fun incrementalInput(): Data = workDataOf(KEY_FULL to false)

        fun fullInput(): Data = workDataOf(KEY_FULL to true)
    }
}
