package com.kharcha.ledger.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kharcha.ledger.data.repository.LedgerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs after an app update that ships a better parser: old messages are read
 * again on-device and their transactions rebuilt, so improvements reach history
 * and not just new messages.
 */
@HiltWorker
class ReprocessWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LedgerRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        repository.reprocessOutdated()
        repository.reprocessRecent()
        Result.success()
    }.getOrElse { Result.failure() }
}
