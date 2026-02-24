package com.example.omronwear.presentation.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.SurfaceTransformation
import com.example.omronwear.R
import com.example.omronwear.ble.LatestData
import com.example.omronwear.ble.OmronBleManager

@Composable
fun DashboardScreen(
    bleManager: OmronBleManager,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onDisconnect: () -> Unit,
) {
    val latestData by bleManager.latestData.collectAsState(initial = null)
    val rssi by bleManager.rssi.collectAsState(initial = null)
    val connectionState by bleManager.connectionState.collectAsState(initial = com.example.omronwear.ble.ConnectionState.Disconnected)
    val deviceAddress = (connectionState as? com.example.omronwear.ble.ConnectionState.Connected)?.deviceAddress ?: ""
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

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
                    text = deviceAddress.ifEmpty { stringResource(R.string.dashboard_title) },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (rssi != null) {
                    Text(
                        text = "${stringResource(R.string.rssi)}: ${rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            DataRow(
                label = stringResource(R.string.temp),
                value = latestData?.let { "%.2f °C".format(it.temperatureC) } ?: stringResource(R.string.no_data),
            )
        }
        item {
            DataRow(
                label = stringResource(R.string.rh),
                value = latestData?.let { "%.2f %%".format(it.relativeHumidityPercent) } ?: stringResource(R.string.no_data),
            )
        }
        item {
            DataRow(
                label = stringResource(R.string.luminosity),
                value = latestData?.let { "${it.lightLx} lx" } ?: stringResource(R.string.no_data),
            )
        }
        item {
            DataRow(
                label = stringResource(R.string.uv_index),
                value = latestData?.uvIndexLabel ?: stringResource(R.string.no_data),
            )
        }
        item {
            DataRow(
                label = stringResource(R.string.pressure),
                value = latestData?.let { "%.1f hPa".format(it.pressureHpa) } ?: stringResource(R.string.no_data),
            )
        }
        item {
            DataRow(
                label = stringResource(R.string.noise),
                value = latestData?.let { "%.2f dB".format(it.soundNoiseDb) } ?: stringResource(R.string.no_data),
            )
        }
        item {
            DataRow(
                label = stringResource(R.string.discomfort),
                value = latestData?.discomfortLabel ?: stringResource(R.string.no_data),
            )
        }
        item {
            DataRow(
                label = stringResource(R.string.heatstroke),
                value = latestData?.heatstrokeLabel ?: stringResource(R.string.no_data),
            )
        }
        item {
            DataRow(
                label = stringResource(R.string.battery),
                value = latestData?.let { "%.3f V".format(it.batteryVoltageV) } ?: stringResource(R.string.no_data),
            )
        }
        item {
            Button(
                onClick = { bleManager.refreshLatestData() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(stringResource(R.string.refresh))
            }
        }
        item {
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(stringResource(R.string.disconnect))
            }
        }
    }
}

@Composable
private fun DataRow(
    label: String,
    value: String,
) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
    )
}
