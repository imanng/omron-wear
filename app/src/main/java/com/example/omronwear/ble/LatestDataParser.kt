package com.example.omronwear.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed Latest data (characteristic 0x3001) from OMRON 2JCIE-BL01.
 * Units per Communication Interface Manual Table 8.
 */
data class LatestData(
    val rowNumber: Int,
    val temperatureC: Double,
    val relativeHumidityPercent: Double,
    val lightLx: Int,
    val uvIndex: Double,
    val pressureHpa: Double,
    val soundNoiseDb: Double,
    val discomfortIndex: Double,
    val heatstrokeWbgtC: Double,
    val batteryVoltageV: Double,
) {
    /** UV Index display label (WHO-style bands) */
    val uvIndexLabel: String = uvIndexToLabel(uvIndex)

    /** Discomfort index display label */
    val discomfortLabel: String = discomfortToLabel(discomfortIndex)

    /** Heatstroke risk display label (WBGT-based) */
    val heatstrokeLabel: String = heatstrokeToLabel(heatstrokeWbgtC)
}

/** WHO-style UV Index bands */
fun uvIndexToLabel(index: Double): String = when {
    index < 0 -> "—"
    index <= 2.0 -> "Low"
    index <= 5.0 -> "Moderate"
    index <= 7.0 -> "High"
    index <= 10.0 -> "Very High"
    else -> "Extreme"
}

/** Discomfort index bands */
fun discomfortToLabel(index: Double): String = when {
    index < 55 -> "Cold"
    index < 60 -> "Cool"
    index < 65 -> "Comfortable"
    index < 70 -> "Slightly warm"
    index < 75 -> "Warm"
    index < 80 -> "Hot"
    else -> "Very hot"
}

/** Heatstroke risk (WBGT approx.) bands */
fun heatstrokeToLabel(wbgtC: Double): String = when {
    wbgtC < 25 -> "Low"
    wbgtC < 28 -> "Caution"
    wbgtC < 31 -> "Warning"
    else -> "Danger"
}

/** Latest page (0x3002) — 9 bytes: UNIX time, interval, latest page, latest row */
data class LatestPage(
    val unixTime: Long,
    val measurementIntervalSec: Int,
    val latestPage: Int,
    val latestRow: Int,
)

/** Response flag (0x3004) — 5 bytes: update flag (0=Retrieving, 1=Completed, 2=Failed), UNIX time */
data class ResponseFlag(
    val updateFlag: Int,
    val unixTime: Long,
) {
    val isCompleted: Boolean get() = updateFlag == 1
    val isFailed: Boolean get() = updateFlag == 2
}

fun parseLatestPage(value: ByteArray?): LatestPage? {
    if (value == null || value.size < 9) return null
    val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
    return LatestPage(
        unixTime = buf.int.toLong() and 0xFFFFFFFFL,
        measurementIntervalSec = buf.short.toInt() and 0xFFFF,
        latestPage = buf.short.toInt() and 0xFFFF,
        latestRow = buf.get().toInt() and 0xFF,
    )
}

fun parseResponseFlag(value: ByteArray?): ResponseFlag? {
    if (value == null || value.size < 5) return null
    val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
    return ResponseFlag(
        updateFlag = buf.get().toInt() and 0xFF,
        unixTime = buf.int.toLong() and 0xFFFFFFFFL,
    )
}

/**
 * Parses 19-byte Latest data characteristic value (little-endian).
 * @return null if data is invalid (wrong length)
 */
fun parseLatestData(value: ByteArray?): LatestData? {
    if (value == null || value.size < 19) return null
    val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
    return LatestData(
        rowNumber = buf.get().toInt() and 0xFF,
        temperatureC = buf.short.toInt() / 100.0,
        relativeHumidityPercent = buf.short.toInt() / 100.0,
        lightLx = buf.short.toInt(),
        uvIndex = buf.short.toInt() / 100.0,
        pressureHpa = buf.short.toInt() / 10.0,
        soundNoiseDb = buf.short.toInt() / 100.0,
        discomfortIndex = buf.short.toInt() / 100.0,
        heatstrokeWbgtC = buf.short.toInt() / 100.0,
        batteryVoltageV = (buf.short.toInt() and 0xFFFF) / 1000.0,
    )
}
