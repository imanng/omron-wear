package com.example.omronwear.presentation.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.example.omronwear.R
import com.example.omronwear.ble.OmronMemorySync
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun Last10RecordsScreen(
    contentPadding: PaddingValues,
    records: List<OmronMemorySync.HistoricalRecord>,
    loading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    val displayRecords = remember(records) { records.takeLast(3).asReversed() }
    val latestRefreshCallback = rememberUpdatedState(onRefresh)
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
            latestRefreshCallback.value()
            while (true) {
                delay(180_000L)
                latestRefreshCallback.value()
            }
        }
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
                    text = stringResource(R.string.last_10_records_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        if (loading) {
            item {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.loading_records),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (errorMessage != null) {
            item {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (displayRecords.isEmpty()) {
            item {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.no_records),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            itemsIndexed(displayRecords) { index, record ->
                val time = Instant.ofEpochSecond(record.timestampEpochSec).atZone(ZoneId.systemDefault())
                val timeStr = formatter.format(time)
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = "%d. %s\nTemp: %.2f C  RH: %.2f%%\nLuminosity: %d lx  UV Index: %s\nPressure: %.1f hPa  Noise: %.2f dB\nDiscomfort: %s\nHeatstroke: %s\nBattery: %.3f V".format(
                        Locale.US,
                        index + 1,
                        timeStr,
                        record.data.temperatureC,
                        record.data.relativeHumidityPercent,
                        record.data.lightLx,
                        record.data.uvIndexLabel,
                        record.data.pressureHpa,
                        record.data.soundNoiseDb,
                        record.data.discomfortLabel,
                        record.data.heatstrokeLabel,
                        record.data.batteryVoltageV,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Button(
                onClick = onRefresh,
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
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(stringResource(R.string.back))
            }
        }
    }
}
