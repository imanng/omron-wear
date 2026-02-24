package com.example.omronwear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-shot sync: connect to OMRON by address, read last [RECORDS_TO_FETCH] stored
 * records from flash (Request page / Response data), disconnect.
 * Designed for use from WorkManager (blocking).
 */
@SuppressLint("MissingPermission")
object OmronMemorySync {

    private const val RECORDS_TO_FETCH = 10
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 5_000L
    private const val WRITE_TIMEOUT_MS = 3_000L
    private const val RESPONSE_FLAG_POLL_MS = 100
    private const val RESPONSE_FLAG_MAX_POLLS = 50

    fun sync(context: Context, deviceAddress: String): List<LatestData> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }

        val connectLatch = CountDownLatch(1)
        var connectSuccess = false
        var readLatch: CountDownLatch? = null
        var lastReadResult: ByteArray? = null
        var writeLatch: CountDownLatch? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!connectSuccess) connectLatch.countDown()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                connectSuccess = status == BluetoothGatt.GATT_SUCCESS
                connectLatch.countDown()
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) lastReadResult = value
                readLatch?.countDown()
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                writeLatch?.countDown()
            }
        }

        var gatt: BluetoothGatt? = null
        try {
            gatt = device.connectGatt(context.applicationContext, false, callback)
            if (!connectLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !connectSuccess || gatt == null) {
                return emptyList()
            }
            val service = gatt.getService(OmronGattConstants.SENSOR_SERVICE_UUID) ?: return emptyList()
            val latestPageChar = service.getCharacteristic(OmronGattConstants.LATEST_PAGE_CHAR_UUID) ?: return emptyList()
            val requestPageChar = service.getCharacteristic(OmronGattConstants.REQUEST_PAGE_CHAR_UUID) ?: return emptyList()
            val responseFlagChar = service.getCharacteristic(OmronGattConstants.RESPONSE_FLAG_CHAR_UUID) ?: return emptyList()
            val responseDataChar = service.getCharacteristic(OmronGattConstants.RESPONSE_DATA_CHAR_UUID) ?: return emptyList()

            fun readBlocking(char: BluetoothGattCharacteristic): ByteArray? {
                readLatch = CountDownLatch(1)
                lastReadResult = null
                if (!gatt!!.readCharacteristic(char)) return null
                readLatch!!.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                return lastReadResult
            }

            val latestPage = readBlocking(latestPageChar)?.let { parseLatestPage(it) } ?: return emptyList()
            var page = latestPage.latestPage
            var row = latestPage.latestRow
            val results = mutableListOf<LatestData>()
            var remaining = RECORDS_TO_FETCH

            while (remaining > 0) {
                val payload = byteArrayOf(
                    (page and 0xFF).toByte(),
                    (page shr 8 and 0xFF).toByte(),
                    (row and 0xFF).toByte(),
                )
                requestPageChar.value = payload
                writeLatch = CountDownLatch(1)
                if (!gatt!!.writeCharacteristic(requestPageChar)) break
                if (!writeLatch!!.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) break

                if (!waitResponseFlagCompleted(::readBlocking, responseFlagChar)) break
                val rowsInThisPage = row + 1
                val toRead = minOf(remaining, rowsInThisPage)
                repeat(toRead) {
                    readBlocking(responseDataChar)?.let { parseLatestData(it) }?.let { results.add(it) }
                }
                remaining -= toRead
                if (remaining <= 0) break
                if (rowsInThisPage >= toRead) {
                    row -= toRead
                } else {
                    page -= 1
                    if (page < 0) break
                    row = 12
                }
            }

            return results.reversed()
        } finally {
            try {
                gatt?.close()
            } catch (_: Exception) { }
        }
    }

    private fun waitResponseFlagCompleted(
        readBlocking: (BluetoothGattCharacteristic) -> ByteArray?,
        responseFlagChar: BluetoothGattCharacteristic,
    ): Boolean {
        repeat(RESPONSE_FLAG_MAX_POLLS) {
            val value = readBlocking(responseFlagChar) ?: return false
            val flag = parseResponseFlag(value) ?: return false
            if (flag.isCompleted) return true
            if (flag.isFailed) return false
            Thread.sleep(RESPONSE_FLAG_POLL_MS.toLong())
        }
        return false
    }
}
