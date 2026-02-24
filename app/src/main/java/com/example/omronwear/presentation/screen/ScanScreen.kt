package com.example.omronwear.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.example.omronwear.R
import com.example.omronwear.ble.ConnectionState
import com.example.omronwear.ble.OmronBleManager
import com.example.omronwear.ble.ScannedDevice

@Composable
fun ScanScreen(
    bleManager: OmronBleManager,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val connectionState by bleManager.connectionState.collectAsState(initial = ConnectionState.Disconnected)
    val scannedDevices by bleManager.scannedDevices.collectAsState(initial = emptyList())
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    LaunchedEffect(Unit) {
        bleManager.startScan()
    }

    TransformingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        state = listState,
    ) {
        item {
            ListHeader(
                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                transformation = SurfaceTransformation(transformationSpec),
            ) {
                Text(
                    text = when (connectionState) {
                        is ConnectionState.Scanning -> stringResource(R.string.scanning)
                        is ConnectionState.Connecting -> stringResource(R.string.scan_title)
                        else -> stringResource(R.string.scan_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        if (scannedDevices.isEmpty() && connectionState is ConnectionState.Scanning) {
            item {
                Text(
                    text = stringResource(R.string.no_devices),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(scannedDevices, key = { it.address }) { device ->
            ScanDeviceChip(
                device = device,
                onClick = { bleManager.connect(device.device) },
            )
        }
    }
}

@Composable
private fun ScanDeviceChip(
    device: ScannedDevice,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column {
            Text(
                text = device.name ?: device.address,
                maxLines = 1,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun ScanScreenPreview() {
    // Preview without BLE manager
    Text("Scan screen")
}
