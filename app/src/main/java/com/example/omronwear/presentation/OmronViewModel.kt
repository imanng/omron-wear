package com.example.omronwear.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.omronwear.ble.OmronBleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OmronViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = OmronBleManager(application.applicationContext)

    private val _hasBluetoothPermission = MutableStateFlow(false)
    val hasBluetoothPermission: StateFlow<Boolean> = _hasBluetoothPermission.asStateFlow()

    fun setPermissionGranted(granted: Boolean) {
        _hasBluetoothPermission.value = granted
    }

    fun disconnect() {
        bleManager.disconnect()
    }
}
