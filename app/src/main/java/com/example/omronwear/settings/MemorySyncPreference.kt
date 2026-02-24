package com.example.omronwear.settings

import android.content.Context

private const val PREFS_NAME = "omron_wear_prefs"
private const val KEY_MEMORY_SYNC_ENABLED = "memory_sync_enabled"

object MemorySyncPreference {
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEMORY_SYNC_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MEMORY_SYNC_ENABLED, enabled)
            .apply()
    }
}
