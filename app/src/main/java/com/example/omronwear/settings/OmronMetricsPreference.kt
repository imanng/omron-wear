package com.example.omronwear.settings

import android.content.Context
import com.example.omronwear.ble.LatestData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS_NAME = "omron_wear_prefs"
private const val KEY_PREFIX = "omron_metrics_"
private const val KEY_MEMORY_SYNC_LIST = "omron_memory_sync_list"
private const val KEY_MEMORY_SYNC_TIME_MS = "omron_memory_sync_time_ms"
private const val KEY_DATE_FORMAT = "ddMMyy_HHmm"

/** Delimiter for serialized LatestData fields (order matches [serialize]/[deserialize]). */
private const val DELIM = "|"

object OmronMetricsPreference {

    /** Key for current minute, e.g. "omron_metrics_260226_1310". */
    fun keyForNow(): String =
        KEY_PREFIX + SimpleDateFormat(KEY_DATE_FORMAT, Locale.US).format(Date())

    /** Saves [data] under key "omron_metrics_ddMMyy_HHmm" for the current time. */
    fun saveLatestData(context: Context, data: LatestData) {
        val key = keyForNow()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, serialize(data))
            .apply()
    }

    /** Reads LatestData for the given key (e.g. "omron_metrics_260226_1310"), or null if missing/invalid. */
    fun getLatestData(context: Context, key: String): LatestData? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return deserialize(raw)
    }

    /** Saves the last memory-sync record list (used when memory sync is on). */
    fun saveMemorySyncList(context: Context, list: List<LatestData>) {
        val value = list.joinToString(separator = "\n") { serialize(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEMORY_SYNC_LIST, value)
            .putLong(KEY_MEMORY_SYNC_TIME_MS, System.currentTimeMillis())
            .apply()
    }

    /** Reads the last memory-sync record list, or empty list if none. */
    fun getMemorySyncList(context: Context): List<LatestData> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MEMORY_SYNC_LIST, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split("\n").mapNotNull { deserialize(it) }
    }

    /** Time (epoch ms) when the memory-sync list was last saved, or null if never. */
    fun getMemorySyncTimeMs(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ms = prefs.getLong(KEY_MEMORY_SYNC_TIME_MS, -1L)
        return if (ms < 0) null else ms
    }

    private fun serialize(data: LatestData): String {
        fun d(v: Double) = java.lang.String.format(Locale.US, "%.6f", v)
        return listOf(
            data.rowNumber.toString(),
            d(data.temperatureC),
            d(data.relativeHumidityPercent),
            data.lightLx.toString(),
            d(data.uvIndex),
            d(data.pressureHpa),
            d(data.soundNoiseDb),
            d(data.discomfortIndex),
            d(data.heatstrokeWbgtC),
            d(data.batteryVoltageV),
        ).joinToString(DELIM)
    }

    private fun deserialize(raw: String): LatestData? {
        val parts = raw.split(DELIM)
        if (parts.size != 10) return null
        return try {
            LatestData(
                rowNumber = parts[0].toInt(),
                temperatureC = parts[1].toDouble(),
                relativeHumidityPercent = parts[2].toDouble(),
                lightLx = parts[3].toInt(),
                uvIndex = parts[4].toDouble(),
                pressureHpa = parts[5].toDouble(),
                soundNoiseDb = parts[6].toDouble(),
                discomfortIndex = parts[7].toDouble(),
                heatstrokeWbgtC = parts[8].toDouble(),
                batteryVoltageV = parts[9].toDouble(),
            )
        } catch (_: Exception) {
            null
        }
    }
}
