package com.example.omronwear.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.omronwear.ble.ConnectionState
import com.example.omronwear.ble.LatestData
import com.example.omronwear.ble.OmronBleManager
import com.example.omronwear.ble.OmronMemorySync
import com.example.omronwear.settings.MemorySyncPreference
import com.example.omronwear.settings.OmronMetricsPreference
import com.example.omronwear.worker.OmronMemorySyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class OmronViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = OmronBleManager(application.applicationContext)

    private val _hasBluetoothPermission = MutableStateFlow(false)
    val hasBluetoothPermission: StateFlow<Boolean> = _hasBluetoothPermission.asStateFlow()

    private val appContext = application.applicationContext

    private val _memorySyncEnabled = MutableStateFlow(MemorySyncPreference.isEnabled(appContext))
    val memorySyncEnabled: StateFlow<Boolean> = _memorySyncEnabled.asStateFlow()

    private val _memorySyncRecords = MutableStateFlow<List<LatestData>>(OmronMetricsPreference.getMemorySyncList(appContext))
    val memorySyncRecords: StateFlow<List<LatestData>> = _memorySyncRecords.asStateFlow()

    private val _memorySyncTimeMs = MutableStateFlow<Long?>(OmronMetricsPreference.getMemorySyncTimeMs(appContext))
    val memorySyncTimeMs: StateFlow<Long?> = _memorySyncTimeMs.asStateFlow()

    private val _last10Records = MutableStateFlow<List<OmronMemorySync.HistoricalRecord>>(emptyList())
    val last10Records: StateFlow<List<OmronMemorySync.HistoricalRecord>> = _last10Records.asStateFlow()

    private val _last10Loading = MutableStateFlow(false)
    val last10Loading: StateFlow<Boolean> = _last10Loading.asStateFlow()

    private val _last10Error = MutableStateFlow<String?>(null)
    val last10Error: StateFlow<String?> = _last10Error.asStateFlow()

    init {
        WorkManager.getInstance(getApplication()).getWorkInfosForUniqueWorkLiveData(OmronMemorySyncWorker.WORK_NAME)
            .observeForever { workInfos ->
                if (workInfos.any { it.state == WorkInfo.State.SUCCEEDED }) {
                    _memorySyncRecords.value = OmronMetricsPreference.getMemorySyncList(appContext)
                    _memorySyncTimeMs.value = OmronMetricsPreference.getMemorySyncTimeMs(appContext)
                }
            }
    }

    fun refreshMemorySyncRecords() {
        _memorySyncRecords.value = OmronMetricsPreference.getMemorySyncList(appContext)
        _memorySyncTimeMs.value = OmronMetricsPreference.getMemorySyncTimeMs(appContext)
    }

    fun setPermissionGranted(granted: Boolean) {
        _hasBluetoothPermission.value = granted
    }

    fun setMemorySyncEnabled(enabled: Boolean) {
        MemorySyncPreference.setEnabled(appContext, enabled)
        _memorySyncEnabled.value = enabled
        if (!enabled) {
            OmronMemorySyncWorker.cancel(appContext)
        } else {
            val state = bleManager.connectionState.value
            if (state is ConnectionState.Connected) {
                OmronMemorySyncWorker.enqueue(appContext, state.deviceAddress)
            }
        }
        bleManager.updatePollingIntervalFromMemorySyncPreference()
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun fetchLast10Records() {
        val state = bleManager.connectionState.value
        if (state !is ConnectionState.Connected) {
            Log.w(TAG, "fetchLast10Records skipped: device is not connected")
            _last10Error.value = "Device is not connected"
            return
        }

        _last10Loading.value = true
        _last10Error.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val records = bleManager.fetchLastRecords(recordsToFetch = 10)
                logLatest3Records(records)
                withContext(Dispatchers.Main) {
                    _last10Records.value = records
                    if (records.isEmpty()) {
                        _last10Error.value = "No records found"
                    }
                    _last10Loading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchLast10Records failed", e)
                withContext(Dispatchers.Main) {
                    _last10Records.value = emptyList()
                    _last10Error.value = "Failed to load records"
                    _last10Loading.value = false
                }
            }
        }
    }

    private fun logLatest3Records(records: List<OmronMemorySync.HistoricalRecord>) {
        val latest3 = records.takeLast(3).asReversed()
        if (latest3.isEmpty()) {
            Log.d(TAG, "Last3Records: empty")
            return
        }
        latest3.forEachIndexed { index, record ->
            val ts = Instant.ofEpochSecond(record.timestampEpochSec).atZone(ZoneId.systemDefault())
            val tsStr = LOG_TIME_FORMATTER.format(ts)
            val line = String.format(
                Locale.US,
                "Last3Records[%d] ts=%s (%d) row=%d temp=%.2fC rh=%.2f%% lx=%d uv=%.2f pressure=%.1fhPa noise=%.2fdB",
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
        private const val TAG = "Last10Records"
        private val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
