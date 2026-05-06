package com.david.adbtest.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PAIRED = "paired_before"
    }

    fun hasPairedBefore(): Boolean = prefs.getBoolean(KEY_PAIRED, false)
    fun setPaired(paired: Boolean) = prefs.edit { putBoolean(KEY_PAIRED, paired) }
    fun clearPairing() = prefs.edit { remove(KEY_PAIRED) }
}