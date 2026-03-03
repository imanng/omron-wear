package com.example.omronwear.worker

import android.content.Context
import android.util.Log
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class OmronMemorySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val address = inputData.getString(KEY_DEVICE_ADDRESS) ?: return Result.failure()
        Log.d(TAG, "Memory sync started for $address")

        val history = OmronMemorySync.syncWithTime(applicationContext, address, recordsToFetch = 10)
        val list = history.map { it.data }
        if (history.isNotEmpty()) {
            OmronMetricsPreference.saveMemorySyncList(applicationContext, list)
            logLatest3(history)
        } else {
            Log.w(TAG, "Memory sync returned empty list")
        }
        if (MemorySyncPreference.isEnabled(applicationContext)) {
            enqueueNext(applicationContext, address)
        }
        return if (history.isNotEmpty()) {
            Result.success(workDataOf(KEY_RECORD_COUNT to history.size))
        } else {
            Result.retry()
        }
    }

    private fun logLatest3(records: List<OmronMemorySync.HistoricalRecord>) {
        val latest3 = records.takeLast(3).asReversed()
        latest3.forEachIndexed { index, record ->
            val ts = Instant.ofEpochSecond(record.timestampEpochSec).atZone(ZoneId.systemDefault())
            val tsStr = LOG_TIME_FORMATTER.format(ts)
            val line = String.format(
                Locale.US,
                "BG Last3[%d] ts=%s (%d) row=%d temp=%.2fC rh=%.2f%% lx=%d uv=%.2f pressure=%.1fhPa noise=%.2fdB",
                index + 1,
                tsStr,
                record.timestampEpochSec,
                record.data.rowNumber,
                record.data.temperatureC,
                record.data.relativeHumidityPercent,
                record.data.lightLx,
                record.data.uvIndex,
                record.data.pressureHpa,
                record.data.soundNoiseDb,
            )
            Log.d(TAG, line)
        }
    }

    companion object {
        private const val TAG = "Last10RecordsBG"
        private val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
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
