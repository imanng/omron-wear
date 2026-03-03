package com.example.omronwear.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import com.example.omronwear.OmronWearApp
import com.example.omronwear.R
import com.example.omronwear.ble.ConnectionState
import com.example.omronwear.presentation.screen.DashboardScreen
import com.example.omronwear.presentation.screen.Last10RecordsScreen
import com.example.omronwear.presentation.screen.ScanScreen
import com.example.omronwear.presentation.theme.OmronWearTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = requiredPermissions().all { result[it] == true }
        (application as? OmronWearApp)?.viewModel?.setPermissionGranted(allGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OmronWearApp
        val vm = app.viewModel!!
        if (requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            vm.setPermissionGranted(true)
        }
        setContent {
            OmronWearTheme {
                WearApp(
                    viewModel = vm,
                    requestPermissions = { permissionLauncher.launch(requiredPermissions().toTypedArray()) },
                )
            }
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }
}

@Composable
fun WearApp(
    viewModel: OmronViewModel,
    requestPermissions: () -> Unit,
) {
    val connectionState by viewModel.bleManager.connectionState.collectAsState(initial = ConnectionState.Disconnected)
    val permissionGranted by viewModel.hasBluetoothPermission.collectAsState(initial = false)

    AppScaffold {
        when {
            !permissionGranted -> {
                ScreenScaffold(
                    content = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Button(onClick = requestPermissions) {
                                Text(stringResource(R.string.permission_required))
                            }
                        }
                    },
                )
            }
            connectionState is ConnectionState.Connected -> {
                val memorySyncEnabled by viewModel.memorySyncEnabled.collectAsState(initial = true)
                val last10Records by viewModel.last10Records.collectAsState(initial = emptyList())
                val last10Loading by viewModel.last10Loading.collectAsState(initial = false)
                val last10Error by viewModel.last10Error.collectAsState(initial = null)
                var showLast10Screen by rememberSaveable { mutableStateOf(false) }
                ScreenScaffold(
                    content = { contentPadding ->
                        if (showLast10Screen) {
                            Last10RecordsScreen(
                                contentPadding = contentPadding,
                                records = last10Records,
                                loading = last10Loading,
                                errorMessage = last10Error,
                                onRefresh = { viewModel.fetchLast10Records() },
                                onBack = { showLast10Screen = false },
                            )
                        } else {
                            DashboardScreen(
                                bleManager = viewModel.bleManager,
                                contentPadding = contentPadding,
                                memorySyncEnabled = memorySyncEnabled,
                                onMemorySyncEnabledChange = { viewModel.setMemorySyncEnabled(it) },
                                onOpenLast10Records = {
                                    showLast10Screen = true
                                },
                                onDisconnect = { viewModel.disconnect() },
                            )
                        }
                    },
                )
            }
            else -> {
                ScreenScaffold(
                    content = { contentPadding ->
                        ScanScreen(
                            bleManager = viewModel.bleManager,
                            contentPadding = contentPadding,
                        )
                    },
                )
            }
        }
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@Composable
fun DefaultPreview() {
    MaterialTheme {
        Text("Omron Wear")
    }
}
