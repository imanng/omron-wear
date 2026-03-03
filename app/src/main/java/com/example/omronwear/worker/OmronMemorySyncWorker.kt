package com.example.omronwear.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.omronwear.ble.OmronMemorySync
import com.example.omronwear.settings.MemorySyncPreference
import com.example.omronwear.settings.OmronMetricsPreference
import java.util.concurrent.TimeUnit

class OmronMemorySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val address = inputData.getString(KEY_DEVICE_ADDRESS) ?: return Result.failure()
        val list = OmronMemorySync.sync(applicationContext, address)
        if (list.isNotEmpty()) {
            OmronMetricsPreference.saveMemorySyncList(applicationContext, list)
        }
        if (MemorySyncPreference.isEnabled(applicationContext)) {
            enqueueNext(applicationContext, address)
        }
        return if (list.isNotEmpty()) {
            Result.success(workDataOf(KEY_RECORD_COUNT to list.size))
        } else {
            Result.retry()
        }
    }

    companion object {
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_RECORD_COUNT = "record_count"
        const val WORK_NAME = "omron_memory_sync"

        private const val SYNC_INTERVAL_MINUTES = 3L

        private fun buildRequest(deviceAddress: String, delayMinutes: Long) =
            OneTimeWorkRequestBuilder<OmronMemorySyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .setInputData(workDataOf(KEY_DEVICE_ADDRESS to deviceAddress))
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()

        /** Enqueue first memory sync (runs soon), then every 3 min. */
        fun enqueue(context: Context, deviceAddress: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                buildRequest(deviceAddress, 0L),
            )
        }

        /** Called from doWork() to schedule the next run in 3 min. */
        private fun enqueueNext(context: Context, deviceAddress: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                buildRequest(deviceAddress, SYNC_INTERVAL_MINUTES),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
