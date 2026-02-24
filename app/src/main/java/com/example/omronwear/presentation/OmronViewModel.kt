package com.example.omronwear.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.omronwear.ble.ConnectionState
import com.example.omronwear.ble.OmronBleManager
import com.example.omronwear.settings.MemorySyncPreference
import com.example.omronwear.worker.OmronMemorySyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OmronViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = OmronBleManager(application.applicationContext)

    private val _hasBluetoothPermission = MutableStateFlow(false)
    val hasBluetoothPermission: StateFlow<Boolean> = _hasBluetoothPermission.asStateFlow()

    private val appContext = application.applicationContext

    private val _memorySyncEnabled = MutableStateFlow(MemorySyncPreference.isEnabled(appContext))
    val memorySyncEnabled: StateFlow<Boolean> = _memorySyncEnabled.asStateFlow()

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
    }

    fun disconnect() {
        bleManager.disconnect()
    }
}
