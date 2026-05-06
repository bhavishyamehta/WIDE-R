package com.david.adbtest.data.manager

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.delay

class WirelessDebugManager(context: Context) {
    private val appContext = context.applicationContext

    fun isWirelessDebuggingEnabled(): Boolean {
        return Settings.Global.getInt(appContext.contentResolver, "adb_wifi_enabled", 0) == 1
    }

    fun isMobileDataAlwaysOnEnabled(): Boolean {
        return Settings.Global.getInt(appContext.contentResolver, "mobile_data_always_on", 0) == 1
    }

    suspend fun disableMobileDataAlwaysOn() {
        if (isMobileDataAlwaysOnEnabled()) {
            Settings.Global.putInt(appContext.contentResolver, "mobile_data_always_on", 0)
            delay(3000)
        }
    }

    suspend fun cycleWirelessDebugging() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // OFF
            if (isWirelessDebuggingEnabled()) {
                Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 0)
                delay(3000)
            }
            // ON
            Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 1)
            delay(3000)
            // OFF
            Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 0)
            delay(3000)
            // ON
            Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 1)
            delay(3000)
        }
    }
}