package com.david.adbtest.di

import android.content.Context
import android.net.nsd.NsdManager
import com.david.adbtest.data.dns.DnsDiscover
import com.david.adbtest.data.executor.AdbExecutor
import com.david.adbtest.data.permission.PermissionChecker
import com.david.adbtest.data.permission.WirelessDebugManager
import com.david.adbtest.data.prefs.PreferencesManager
import com.david.adbtest.data.repository.AdbRepository
import com.david.adbtest.data.repository.AdbRepositoryImpl
import com.david.adbtest.presentation.viewmodel.MainViewModel

object AppContainer {
    private var repository: AdbRepository? = null
    private var prefsManager: PreferencesManager? = null

    fun providePrefsManager(context: Context): PreferencesManager {
        return prefsManager ?: PreferencesManager(context.applicationContext).also {
            prefsManager = it
        }
    }

    fun provideRepository(context: Context): AdbRepository {
        return repository ?: AdbRepositoryImpl(
            context = context.applicationContext,
            adbExecutor = AdbExecutor(context.applicationContext),
            permissionChecker = PermissionChecker(context.applicationContext),
            wirelessDebugManager = WirelessDebugManager(context.applicationContext),
            prefsManager = providePrefsManager(context),
            dnsDiscover = DnsDiscover.getInstance(
                context.applicationContext,
                context.getSystemService(Context.NSD_SERVICE) as NsdManager
            )
        ).also { repository = it }
    }

    fun provideViewModel(context: Context): MainViewModel {
        return MainViewModel(
            repository = provideRepository(context),
            prefsManager = providePrefsManager(context)
        )
    }
}