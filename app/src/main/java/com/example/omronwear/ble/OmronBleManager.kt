package com.example.omronwear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.omronwear.settings.MemorySyncPreference
import com.example.omronwear.settings.OmronMetricsPreference
import com.example.omronwear.worker.OmronMemorySyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun Context.getBluetoothAdapter(): BluetoothAdapter? =
    (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val deviceAddress: String, val rssi: Int) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String?,
    val address: String,
    val rssi: Int,
)

@SuppressLint("MissingPermission")
class OmronBleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy { context.getBluetoothAdapter() }
    private val leScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollingJob: Job? = null
    private var rssiJob: Job? = null
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var blockingReadUuid: UUID? = null
    @Volatile
    private var blockingReadResult: ByteArray? = null
    @Volatile
    private var blockingReadLatch: CountDownLatch? = null
    @Volatile
    private var blockingWriteUuid: UUID? = null
    @Volatile
    private var blockingWriteLatch: CountDownLatch? = null
    @Volatile
    private var blockingWriteStatus: Int = BluetoothGatt.GATT_FAILURE

    /** Latest-data poll interval: 1 min when memory sync off (1 datapoint/min), 3 min when on. */
    private var latestDataPollIntervalMs: Long = POLL_INTERVAL_MS_WHEN_SYNC_OFF

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _latestData = MutableStateFlow<LatestData?>(null)
    val latestData: StateFlow<LatestData?> = _latestData.asStateFlow()

    private val _lastDataUpdateTimeMs = MutableStateFlow<Long?>(null)
    val lastDataUpdateTimeMs: StateFlow<Long?> = _lastDataUpdateTimeMs.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.Connecting
                    gatt.readRemoteRssi()
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    pollingJob?.cancel()
                    pollingJob = null
                    rssiJob?.cancel()
                    rssiJob = null
                    try { gatt.close() } catch (_: Exception) { }
                    bluetoothGatt = null
                    _connectionState.value = ConnectionState.Disconnected
                    _latestData.value = null
                    _lastDataUpdateTimeMs.value = null
                    _rssi.value = null
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.Error("Connection failed: $status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Service discovery failed")
                return
            }
            val service = gatt.getService(OmronGattConstants.SENSOR_SERVICE_UUID)
                ?: run {
                    _connectionState.value = ConnectionState.Error("OMRON Sensor Service not found")
                    return
                }
            val char = service.getCharacteristic(OmronGattConstants.LATEST_DATA_CHAR_UUID)
                ?: run {
                    _connectionState.value = ConnectionState.Error("Latest data characteristic not found")
                    return
            }
            bluetoothGatt = gatt
            _connectionState.value = ConnectionState.Connected(gatt.device.address, _rssi.value ?: 0)
            scope.launch(Dispatchers.IO) {
                configureFlashRecordingDefaults(gatt)
                withContext(Dispatchers.Main) {
                    if (bluetoothGatt !== gatt) return@withContext
                    readLatestDataCharacteristic(gatt, char)
                    startPolling()
                    if (MemorySyncPreference.isEnabled(context)) {
                        OmronMemorySyncWorker.enqueue(context, gatt.device.address)
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid == blockingReadUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    blockingReadResult = value
                }
                blockingReadLatch?.countDown()
            }
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == OmronGattConstants.LATEST_DATA_CHAR_UUID) {
                parseLatestData(value)?.let { data ->
                    _latestData.value = data
                    _lastDataUpdateTimeMs.value = System.currentTimeMillis()
                    OmronMetricsPreference.saveLatestData(context, data)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == blockingWriteUuid) {
                blockingWriteStatus = status
                blockingWriteLatch?.countDown()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == OmronGattConstants.LATEST_DATA_CHAR_UUID) {
                parseLatestData(value)?.let { data ->
                    _latestData.value = data
                    _lastDataUpdateTimeMs.value = System.currentTimeMillis()
                    OmronMetricsPreference.saveLatestData(context, data)
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
                val state = _connectionState.value
                if (state is ConnectionState.Connected) {
                    _connectionState.value = state.copy(rssi = rssi)
                }
            }
        }
    }

    private fun readLatestDataCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.readCharacteristic(characteristic)
        } catch (_: SecurityException) { }
    }

    private fun pollIntervalFromPreference(): Long =
        if (MemorySyncPreference.isEnabled(context)) POLL_INTERVAL_MS_WHEN_SYNC_ON else POLL_INTERVAL_MS_WHEN_SYNC_OFF

    /**
     * Updates latest-data polling interval from current memory-sync preference and restarts
     * polling if connected. Call when user toggles memory sync.
     */
    fun updatePollingIntervalFromMemorySyncPreference() {
        latestDataPollIntervalMs = pollIntervalFromPreference()
        if (pollingJob != null) {
            startPolling()
        }
    }

    private fun startPolling() {
        latestDataPollIntervalMs = pollIntervalFromPreference()
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(latestDataPollIntervalMs)
                withContext(Dispatchers.Main) {
                    val g = bluetoothGatt ?: return@withContext
                    val service = g.getService(OmronGattConstants.SENSOR_SERVICE_UUID) ?: return@withContext
                    val char = service.getCharacteristic(OmronGattConstants.LATEST_DATA_CHAR_UUID) ?: return@withContext
                    try { g.readCharacteristic(char) } catch (_: SecurityException) { }
                }
            }
        }
        rssiJob?.cancel()
        rssiJob = scope.launch {
            while (isActive) {
                delay(RSSI_INTERVAL_MS)
                withContext(Dispatchers.Main) {
                    try { bluetoothGatt?.readRemoteRssi() } catch (_: SecurityException) { }
                }
            }
        }
    }

    fun startScan() {
        if (leScanner == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth LE not available")
            return
        }
        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            leScanner?.startScan(null, settings, scanCallback)
        } catch (_: SecurityException) {
            _connectionState.value = ConnectionState.Error("Bluetooth permission denied")
        }
    }

    fun stopScan() {
        try {
            leScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) { }
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: ""
            val advertisesOmronService = result.scanRecord?.serviceUuids?.any { parcelUuid ->
                parcelUuid.uuid == OmronGattConstants.SENSOR_SERVICE_UUID
            } == true
            val nameMatches = name.isNotEmpty() &&
                OmronGattConstants.OMRON_DEVICE_NAME_PREFIXES.any { name.startsWith(it) }
            if (nameMatches || advertisesOmronService) {
                val displayName = when {
                    name.isNotEmpty() -> name
                    advertisesOmronService -> "Omron Sensor"
                    else -> "Unknown"
                }
                val existing = _scannedDevices.value
                val updated = existing.filter { it.address != device.address } +
                    ScannedDevice(device, displayName, device.address, result.rssi)
                _scannedDevices.value = updated.sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scannedDevices.value = emptyList()
            _connectionState.value = ConnectionState.Error("Scan failed: $errorCode")
        }
    }

    fun connect(device: BluetoothDevice) {
        disconnect()
        stopScan()
        _connectionState.value = ConnectionState.Connecting
        try {
            bluetoothGatt = device.connectGatt(context.applicationContext, false, gattCallback)
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.Error("Permission denied")
        }
    }

    fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        rssiJob?.cancel()
        rssiJob = null
        OmronMemorySyncWorker.cancel(context)
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) { }
        bluetoothGatt = null
        _connectionState.value = ConnectionState.Disconnected
        _latestData.value = null
        _lastDataUpdateTimeMs.value = null
        _rssi.value = null
    }

    fun refreshLatestData() {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(OmronGattConstants.SENSOR_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(OmronGattConstants.LATEST_DATA_CHAR_UUID) ?: return
        try {
            gatt.readCharacteristic(char)
            gatt.readRemoteRssi()
        } catch (_: SecurityException) { }
    }

    fun fetchLastRecords(recordsToFetch: Int = 10): List<OmronMemorySync.HistoricalRecord> {
        if (recordsToFetch <= 0) return emptyList()
        val gatt = bluetoothGatt ?: return emptyList()

        val shouldRestartPolling = _connectionState.value is ConnectionState.Connected
        pollingJob?.cancel()
        pollingJob = null
        rssiJob?.cancel()
        rssiJob = null

        return try {
            fetchHistoricalDataBlocking(gatt, recordsToFetch)
        } finally {
            if (shouldRestartPolling && bluetoothGatt != null) {
                startPolling()
            }
        }
    }

    private fun configureFlashRecordingDefaults(gatt: BluetoothGatt) {
        val settingService = gatt.getService(OmronGattConstants.SETTING_SERVICE_UUID) ?: return
        val intervalChar = settingService.getCharacteristic(OmronGattConstants.MEASUREMENT_INTERVAL_CHAR_UUID) ?: return
        val controlService = gatt.getService(OmronGattConstants.CONTROL_SERVICE_UUID) ?: return
        val timeInfoChar = controlService.getCharacteristic(OmronGattConstants.TIME_INFORMATION_CHAR_UUID) ?: return

        val currentIntervalSec = readCharacteristicBlocking(gatt, intervalChar)?.let(::parseUInt16Le)
        if (currentIntervalSec != DEFAULT_FLASH_RECORDING_INTERVAL_SEC) {
            val intervalPayload = byteArrayOf(
                (DEFAULT_FLASH_RECORDING_INTERVAL_SEC and 0xFF).toByte(),
                (DEFAULT_FLASH_RECORDING_INTERVAL_SEC shr 8 and 0xFF).toByte(),
            )
            val ok = writeCharacteristicBlocking(gatt, intervalChar, intervalPayload)
            if (!ok) {
                Log.w(TAG, "Failed to write measurement interval 0x3011 = $DEFAULT_FLASH_RECORDING_INTERVAL_SEC")
            }
        }

        val unixSec = (System.currentTimeMillis() / 1000L).coerceAtLeast(1L)
        val timePayload = byteArrayOf(
            (unixSec and 0xFF).toByte(),
            (unixSec shr 8 and 0xFF).toByte(),
            (unixSec shr 16 and 0xFF).toByte(),
            (unixSec shr 24 and 0xFF).toByte(),
        )
        val timeOk = writeCharacteristicBlocking(gatt, timeInfoChar, timePayload)
        if (!timeOk) {
            Log.w(TAG, "Failed to write time information 0x3031")
        }
    }

    private fun fetchHistoricalDataBlocking(
        gatt: BluetoothGatt,
        recordsToFetch: Int,
    ): List<OmronMemorySync.HistoricalRecord> {
        val service = gatt.getService(OmronGattConstants.SENSOR_SERVICE_UUID) ?: return emptyList()
        val latestPageChar = service.getCharacteristic(OmronGattConstants.LATEST_PAGE_CHAR_UUID) ?: return emptyList()
        val requestPageChar = service.getCharacteristic(OmronGattConstants.REQUEST_PAGE_CHAR_UUID) ?: return emptyList()
        val responseFlagChar = service.getCharacteristic(OmronGattConstants.RESPONSE_FLAG_CHAR_UUID) ?: return emptyList()
        val responseDataChar = service.getCharacteristic(OmronGattConstants.RESPONSE_DATA_CHAR_UUID) ?: return emptyList()

        var latestPage = readCharacteristicBlocking(gatt, latestPageChar)?.let { parseLatestPage(it) } ?: return emptyList()
        if (isLatestPageStale(latestPage)) {
            Log.w(
                TAG,
                "Latest page time looks stale (unix=${latestPage.unixTime}, interval=${latestPage.measurementIntervalSec}); reapplying time/interval",
            )
            configureFlashRecordingDefaults(gatt)
            latestPage = readCharacteristicBlocking(gatt, latestPageChar)?.let { parseLatestPage(it) } ?: latestPage
        }
        var page = latestPage.latestPage
        var row = latestPage.latestRow
        var remaining = recordsToFetch
        val intervalSec = latestPage.measurementIntervalSec.toLong().coerceAtLeast(1L)
        val results = mutableListOf<OmronMemorySync.HistoricalRecord>()

        while (remaining > 0) {
            val payload = byteArrayOf(
                (page and 0xFF).toByte(),
                (page shr 8 and 0xFF).toByte(),
                (row and 0xFF).toByte(),
            )
            if (!writeCharacteristicBlocking(gatt, requestPageChar, payload)) break
            val responseFlag = waitResponseFlagCompleted(gatt, responseFlagChar) ?: break
            val pageBaseUnixSec = responseFlag.unixTime

            val rowsInThisPage = row + 1
            val toRead = minOf(remaining, rowsInThisPage)
            repeat(toRead) {
                readCharacteristicBlocking(gatt, responseDataChar)
                    ?.let { parseLatestData(it) }
                    ?.let { data ->
                        val timestampSec = (pageBaseUnixSec + data.rowNumber.toLong() * intervalSec).coerceAtLeast(0L)
                        results.add(
                            OmronMemorySync.HistoricalRecord(
                                data = data,
                                timestampEpochSec = timestampSec,
                            ),
                        )
                    }
            }
            remaining -= toRead
            if (remaining <= 0) break

            if (toRead == rowsInThisPage) {
                page -= 1
                if (page < 0) break
                row = 12
            } else {
                row -= toRead
            }
        }

        return results.reversed()
    }

    private fun readCharacteristicBlocking(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): ByteArray? {
        val latch = CountDownLatch(1)
        blockingReadUuid = characteristic.uuid
        blockingReadResult = null
        blockingReadLatch = latch
        val started = try {
            gatt.readCharacteristic(characteristic)
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            blockingReadUuid = null
            blockingReadLatch = null
            return null
        }
        latch.await(BLOCKING_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val result = blockingReadResult
        blockingReadUuid = null
        blockingReadResult = null
        blockingReadLatch = null
        return result
    }

    private fun writeCharacteristicBlocking(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
    ): Boolean {
        val latch = CountDownLatch(1)
        blockingWriteUuid = characteristic.uuid
        blockingWriteLatch = latch
        blockingWriteStatus = BluetoothGatt.GATT_FAILURE
        characteristic.value = payload
        val started = try {
            gatt.writeCharacteristic(characteristic)
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            blockingWriteUuid = null
            blockingWriteLatch = null
            return false
        }
        val completed = latch.await(BLOCKING_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val statusOk = blockingWriteStatus == BluetoothGatt.GATT_SUCCESS
        blockingWriteUuid = null
        blockingWriteLatch = null
        return completed && statusOk
    }

    private fun waitResponseFlagCompleted(
        gatt: BluetoothGatt,
        responseFlagChar: BluetoothGattCharacteristic,
    ): ResponseFlag? {
        repeat(BLOCKING_RESPONSE_FLAG_MAX_POLLS) {
            val value = readCharacteristicBlocking(gatt, responseFlagChar) ?: return null
            val flag = parseResponseFlag(value) ?: return null
            if (flag.isCompleted) return flag
            if (flag.isFailed) return null
            Thread.sleep(BLOCKING_RESPONSE_FLAG_POLL_MS.toLong())
        }
        return null
    }

    private fun parseUInt16Le(value: ByteArray): Int? {
        if (value.size < 2) return null
        return (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
    }

    private fun isLatestPageStale(page: LatestPage): Boolean {
        val nowSec = System.currentTimeMillis() / 1000L
        val intervalSec = page.measurementIntervalSec.toLong().coerceAtLeast(1L)
        val latestRecordUnixSec = page.unixTime + page.latestRow.toLong() * intervalSec
        val thresholdSec = maxOf(intervalSec * 2L, 180L)
        return latestRecordUnixSec <= 0L || (nowSec - latestRecordUnixSec) > thresholdSec
    }

    companion object {
        private const val TAG = "OmronBleManager"
        /** 1 min when memory sync off: 1 datapoint per minute. */
        private const val POLL_INTERVAL_MS_WHEN_SYNC_OFF = 60_000L
        /** 3 min when memory sync on (records from OmronMemorySyncWorker). */
        private const val POLL_INTERVAL_MS_WHEN_SYNC_ON = 180_000L
        private const val DEFAULT_FLASH_RECORDING_INTERVAL_SEC = 60
        private const val RSSI_INTERVAL_MS = 60_000L
        private const val BLOCKING_READ_TIMEOUT_MS = 5_000L
        private const val BLOCKING_WRITE_TIMEOUT_MS = 3_000L
        private const val BLOCKING_RESPONSE_FLAG_POLL_MS = 100
        private const val BLOCKING_RESPONSE_FLAG_MAX_POLLS = 50
    }
}
