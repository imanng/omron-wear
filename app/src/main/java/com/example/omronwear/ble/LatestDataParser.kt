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
