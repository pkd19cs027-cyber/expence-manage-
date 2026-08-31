package com.kharcha.ledger.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * All background work is local: reading the inbox, reconciling, recomputing
 * insights. Nothing is scheduled to talk to a network, because there is none.
 */
object WorkScheduler {

    private const val INCREMENTAL_IMPORT = "kharcha-incremental-import"
    private const val FULL_IMPORT = "kharcha-full-import"
    private const val PERIODIC_SWEEP = "kharcha-periodic-sweep"
    private const val REPROCESS = "kharcha-reprocess"

    /**
     * Runs shortly after an SMS arrives. The delay lets the messaging app finish
     * writing the message to the provider that the worker reads from.
     */
    fun enqueueIncrementalImport(context: Context) {
        val request = OneTimeWorkRequestBuilder<SmsImportWorker>()
            .setInputData(SmsImportWorker.incrementalInput())
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(INCREMENTAL_IMPORT, ExistingWorkPolicy.REPLACE, request)
    }

    /** The first-run import of the whole inbox. */
    fun enqueueFullImport(context: Context) {
        val request = OneTimeWorkRequestBuilder<SmsImportWorker>()
            .setInputData(SmsImportWorker.fullInput())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FULL_IMPORT, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * A safety net for messages missed while the phone was off or the app was
     * force-stopped, and the moment when an upgraded parser re-reads old
     * messages.
     */
    fun schedulePeriodicSweep(context: Context) {
        val request = PeriodicWorkRequestBuilder<SmsImportWorker>(6, TimeUnit.HOURS)
            .setInputData(SmsImportWorker.incrementalInput())
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_SWEEP, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueReprocess(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReprocessWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(REPROCESS, ExistingWorkPolicy.KEEP, request)
    }
}
