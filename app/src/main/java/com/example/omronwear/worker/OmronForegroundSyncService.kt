package com.example.omronwear.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.omronwear.OmronWearApp
import com.example.omronwear.R
import com.example.omronwear.ble.ConnectionState
import com.example.omronwear.ble.OmronMemorySync
import com.example.omronwear.settings.OmronMetricsPreference
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OmronForegroundSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private var currentDeviceAddress: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address.isNullOrBlank()) {
                    Log.w(TAG, "Missing device address, stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundInternal(address)
                if (syncJob?.isActive != true || currentDeviceAddress != address) {
                    currentDeviceAddress = address
                    startSyncLoop()
                }
                return START_STICKY
            }
            else -> return START_STICKY
        }
    }

    override fun onDestroy() {
        syncJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            while (isActive) {
                syncOnce()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private fun syncOnce() {
        val app = application as? OmronWearApp
        val manager = app?.viewModel?.bleManager
        val state = manager?.connectionState?.value
        if (manager == null || state !is ConnectionState.Connected) {
            Log.w(TAG, "Skip sync tick: BLE not connected")
            return
        }

        val records = manager.fetchLastRecords(recordsToFetch = 10)
        if (records.isEmpty()) {
            Log.w(TAG, "Foreground sync returned empty list")
            return
        }

        OmronMetricsPreference.saveMemorySyncList(
            applicationContext,
            records.map { it.data },
        )
        logLatest3(records)
    }

    private fun logLatest3(records: List<OmronMemorySync.HistoricalRecord>) {
        val latest3 = records.takeLast(3).asReversed()
        latest3.forEachIndexed { index, record ->
            val ts = Instant.ofEpochSecond(record.timestampEpochSec).atZone(ZoneId.systemDefault())
            val tsStr = LOG_TIME_FORMATTER.format(ts)
            val line = String.format(
                Locale.US,
                "FG Last3[%d] ts=%s (%d) row=%d temp=%.2fC rh=%.2f%% lx=%d uv=%.2f pressure=%.1fhPa noise=%.2fdB",
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

    private fun startForegroundInternal(deviceAddress: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.fg_sync_title))
            .setContentText(getString(R.string.fg_sync_text, deviceAddress))
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "Last10RecordsFGS"
        private const val ACTION_START = "com.example.omronwear.worker.action.START_FG_SYNC"
        private const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        private const val CHANNEL_ID = "omron_fg_sync_channel"
        private const val CHANNEL_NAME = "Omron Background Sync"
        private const val NOTIFICATION_ID = 12001
        private const val SYNC_INTERVAL_MS = 180_000L
        private val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun start(context: Context, deviceAddress: String) {
            val intent = Intent(context, OmronForegroundSyncService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OmronForegroundSyncService::class.java))
        }
    }
}
