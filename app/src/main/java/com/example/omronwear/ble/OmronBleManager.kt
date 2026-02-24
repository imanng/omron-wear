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
import com.example.omronwear.settings.MemorySyncPreference
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

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _latestData = MutableStateFlow<LatestData?>(null)
    val latestData: StateFlow<LatestData?> = _latestData.asStateFlow()

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
            readLatestDataCharacteristic(gatt, char)
            startPolling()
            if (MemorySyncPreference.isEnabled(context)) {
                OmronMemorySyncWorker.enqueue(context, gatt.device.address)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == OmronGattConstants.LATEST_DATA_CHAR_UUID) {
                _latestData.value = parseLatestData(value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == OmronGattConstants.LATEST_DATA_CHAR_UUID) {
                _latestData.value = parseLatestData(value)
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

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
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

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L   // 1 minute
        private const val RSSI_INTERVAL_MS = 60_000L  // 1 minute
    }
}
