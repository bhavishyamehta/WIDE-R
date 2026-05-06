package com.david.adbtest.data.repository

import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import com.david.adbtest.data.dns.DnsDiscover
import com.david.adbtest.data.executor.AdbExecutor
import com.david.adbtest.data.permission.PermissionChecker
import com.david.adbtest.data.permission.WirelessDebugManager
import com.david.adbtest.data.prefs.PreferencesManager
import com.david.adbtest.domain.model.AdbState
import com.david.adbtest.domain.model.PairingInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface AdbRepository {
    fun checkAndConnect(): Flow<AdbState>
    fun pairAndConnect(pairingInfo: PairingInfo): Flow<AdbState>
}

class AdbRepositoryImpl(
    private val context: Context,
    private val adbExecutor: AdbExecutor,
    private val permissionChecker: PermissionChecker,
    private val wirelessDebugManager: WirelessDebugManager,
    private val prefsManager: PreferencesManager,
    private val dnsDiscover: DnsDiscover
) : AdbRepository {

    override fun checkAndConnect(): Flow<AdbState> = flow {
        try {
            var hasPermission = permissionChecker.hasSecureSettingsPermission()

            if (!hasPermission) {
                emit(AdbState.Loading("⚠️ WRITE_SECURE_SETTINGS permission not found.\nWill auto-grant after connection..."))
            }

            if (!prefsManager.hasPairedBefore()) {
                emit(AdbState.NeedsPairing())
                return@flow
            }

            emit(AdbState.Loading("Checking wireless debugging..."))

            // Wait for wireless debugging to be enabled (LADB style)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!wirelessDebugManager.isWirelessDebuggingEnabled()) {
                    emit(AdbState.Loading("Wireless debugging OFF!\nWaiting for it to be enabled..."))
                    while (!wirelessDebugManager.isWirelessDebuggingEnabled()) {
                        delay(1000)
                    }
                    emit(AdbState.Loading("✓ Wireless debugging enabled!"))
                }
            }

            // Disable mobile data always on (LADB does this first)
            try {
                if (hasPermission && wirelessDebugManager.isMobileDataAlwaysOnEnabled()) {
                    emit(AdbState.Loading("Disabling 'Mobile data always on'..."))
                    wirelessDebugManager.disableMobileDataAlwaysOn()
                }
            } catch (e: Exception) {
                adbExecutor.debug("Failed to disable mobile data always on: ${e.message}")
            }

            // ALWAYS cycle wireless debugging (LADB style - critical for fresh port)
            emit(AdbState.Loading("🔄 Cycling wireless debugging..."))
            try {
                wirelessDebugManager.cycleWirelessDebugging()
            } catch (e: Exception) {
                adbExecutor.debug("Wireless cycling failed: ${e.message}")
                emit(AdbState.Loading("⚠️ Wireless cycling failed, continuing..."))
            }

            // Reset DNS state before starting (CRITICAL FIX!)
            DnsDiscover.reset()

            // Start DNS discovery
            emit(AdbState.Loading("🔍 Starting DNS discovery..."))
            dnsDiscover.scanAdbPorts()

            // Wait for DNS resolver (LADB style - exact timing)
            val nowTime = System.currentTimeMillis()
            val maxTimeoutTime = nowTime + 10_000
            val minDnsScanTime = (DnsDiscover.aliveTime ?: nowTime) + 3_000

            while (true) {
                val currentTime = System.currentTimeMillis()
                val pendingResolves = DnsDiscover.pendingResolves.get()

                // Wait for minimum scan time AND pending resolves to finish
                if (currentTime >= minDnsScanTime && !pendingResolves) {
                    adbExecutor.debug("DNS resolver done")
                    emit(AdbState.Loading("✓ DNS resolver done"))
                    break
                }

                // Or timeout after 10 seconds
                if (currentTime >= maxTimeoutTime) {
                    adbExecutor.debug("DNS resolver timeout")
                    emit(AdbState.Loading("⚠️ DNS resolver timeout, continuing..."))
                    break
                }

                emit(AdbState.Loading("⏳ Awaiting DNS resolver..."))
                delay(1000)
            }

            val discoveredPort = DnsDiscover.adbPort
            if (discoveredPort != null) {
                adbExecutor.debug("Best ADB port discovered: $discoveredPort")
                emit(AdbState.Loading("🔍 Discovered Port: $discoveredPort"))
            } else {
                adbExecutor.debug("No ADB port discovered, using fallback...")
                emit(AdbState.Loading("⚠️ No port discovered, using fallback"))
            }

            // Start ADB server
            emit(AdbState.Loading("Starting ADB server..."))
            val startServerOutput = adbExecutor.executeCommand(listOf("start-server"))
            adbExecutor.debug("Start server result: $startServerOutput")
            delay(1000)

            // Connect to device (LADB style - blocking wait)
            emit(AdbState.Loading("Connecting to device..."))

            var connected = false
            var connectedDeviceSerial: String? = null

            if (discoveredPort != null) {
                // Use discovered port
                adbExecutor.debug("Connecting to localhost:$discoveredPort")
                val connectOutput = adbExecutor.executeCommand(listOf("connect", "localhost:$discoveredPort"))
                adbExecutor.debug("Connect output: $connectOutput")

                // Use wait-for-device to block until connected (LADB style)
                emit(AdbState.Loading("Waiting for device..."))
                val waitOutput = adbExecutor.executeCommandWithTimeout(listOf("wait-for-device"), 60)
                adbExecutor.debug("Wait-for-device output: $waitOutput")

                if (!waitOutput.contains("timeout")) {
                    connected = true
                }
            } else {
                // Fallback: use wait-for-device directly
                adbExecutor.debug("Using wait-for-device fallback")
                emit(AdbState.Loading("Waiting for device..."))
                val waitOutput = adbExecutor.executeCommandWithTimeout(listOf("wait-for-device"), 60)
                adbExecutor.debug("Wait-for-device output: $waitOutput")

                if (!waitOutput.contains("timeout")) {
                    connected = true
                }
            }

            if (connected) {
                // Get device list
                val devices = adbExecutor.getConnectedDevices()
                adbExecutor.debug("Connected! Found ${devices.size} device(s)")
                devices.forEach { adbExecutor.debug("Device: $it") }

                if (devices.isNotEmpty()) {
                    connectedDeviceSerial = devices.firstOrNull()?.split("\t")?.firstOrNull()
                    adbExecutor.debug("Device serial: $connectedDeviceSerial")

                    // Re-check permission
                    hasPermission = permissionChecker.hasSecureSettingsPermission()

                    // Auto-grant permission if needed
                    if (!hasPermission) {
                        emit(AdbState.Loading("🔐 Auto-granting permission..."))
                        delay(1000)

                        val grantSuccess = adbExecutor.startShellAndGrantPermission(connectedDeviceSerial)

                        if (grantSuccess) {
                            emit(AdbState.Loading("✓ Permission granted!"))
                            delay(2000)

                            hasPermission = permissionChecker.hasSecureSettingsPermission()

                            if (hasPermission) {
                                emit(AdbState.Loading("✅ Permission verified!"))
                            } else {
                                emit(AdbState.Loading("⚠️ Restart app to activate"))
                            }
                        }
                        delay(1000)
                    }

                    val output = buildString {
                        appendLine("✅ Auto-Connected!")
                        appendLine("\n━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("\n📱 Connected Device:")
                        devices.forEach { appendLine(it) }
                        if (discoveredPort != null) {
                            appendLine("\n🔍 Discovered Port: $discoveredPort")
                        }
                        appendLine("\n━━━━━━━━━━━━━━━━━━━━━")
                        if (!hasPermission) {
                            appendLine("\n🔐 Permission: Auto-granted")
                            appendLine("⚠️ Restart app for full functionality")
                        }
                        appendLine("\n💡 Ready for ADB commands!")
                    }
                    emit(AdbState.Success(output))
                } else {
                    adbExecutor.debug("wait-for-device succeeded but no devices in list")
                    emit(AdbState.Error("❌ Connection anomaly!\n\nDevice connected but not visible.\n\nPlease:\n1. Click 'Retry'\n2. Restart Wireless Debugging"))
                }
            } else {
                adbExecutor.debug("Connection timeout")
                emit(AdbState.Error("❌ Connection timeout!\n\nPlease try:\n1. Click 'Retry'\n2. Restart Wireless Debugging\n3. Make sure WiFi is ON\n4. Use 'Reset & Re-pair' if issue persists"))
            }

        } catch (e: Exception) {
            adbExecutor.debug("Error in checkAndConnect: ${e.message}")
            e.printStackTrace()
            emit(AdbState.Error("Error: ${e.message}"))
        }
    }

    override fun pairAndConnect(pairingInfo: PairingInfo): Flow<AdbState> = flow {
        try {
            val hasPermission = permissionChecker.hasSecureSettingsPermission()

            if (!hasPermission) {
                emit(AdbState.Loading("⚠️ WRITE_SECURE_SETTINGS permission not found.\nWill auto-grant after pairing..."))
            }

            // Disable mobile data always on (if permission exists)
            if (hasPermission && wirelessDebugManager.isMobileDataAlwaysOnEnabled()) {
                emit(AdbState.Loading("Disabling 'Mobile data always on'..."))
                wirelessDebugManager.disableMobileDataAlwaysOn()
            }

            // Cycle wireless debugging (if permission exists)
            if (hasPermission) {
                emit(AdbState.Loading("🔄 Cycling wireless debugging..."))
                wirelessDebugManager.cycleWirelessDebugging()
            } else {
                emit(AdbState.Loading("⚠️ Skipping wireless cycling (no permission yet)"))
                delay(2000)
            }

            // Kill server
            emit(AdbState.Loading("Preparing ADB..."))
            adbExecutor.executeCommand(listOf("kill-server"))
            delay(1000)

            // Pair
            emit(AdbState.Loading("⏳ Pairing with device...\nPort: ${pairingInfo.port}\nCode: ${pairingInfo.pairingCode}\n\nPlease wait..."))
            val pairSuccess = adbExecutor.pairDevice(pairingInfo.port, pairingInfo.pairingCode)

            if (!pairSuccess) {
                emit(AdbState.Error("❌ Pairing failed!\n\nPlease check:\n• Port number is correct (${pairingInfo.port})\n• Pairing code is correct\n• Pairing dialog is still open on device\n\nTry:\n1. Generate new pairing code\n2. Enter details again\n3. Tap 'Pair' quickly"))
                return@flow
            }

            // Save paired status
            prefsManager.setPaired(true)
            emit(AdbState.Loading("✅ Pairing successful!\nSaved for future auto-connect.\n\nStarting connection..."))
            delay(2000)

            // Start DNS discovery
            emit(AdbState.Loading("🔍 Starting DNS discovery..."))
            dnsDiscover.scanAdbPorts()

            // Wait for DNS resolver
            val nowTime = System.currentTimeMillis()
            val maxTimeoutTime = nowTime + 10_000
            val minDnsScanTime = (DnsDiscover.aliveTime ?: nowTime) + 3_000

            while (true) {
                val currentTime = System.currentTimeMillis()
                val pendingResolves = DnsDiscover.pendingResolves.get()

                if (currentTime >= minDnsScanTime && !pendingResolves) {
                    emit(AdbState.Loading("✓ DNS resolver done"))
                    break
                }

                if (currentTime >= maxTimeoutTime) {
                    emit(AdbState.Loading("⚠️ DNS resolver timeout, continuing..."))
                    break
                }

                emit(AdbState.Loading("⏳ Awaiting DNS resolver..."))
                delay(1000)
            }

            val discoveredPort = DnsDiscover.adbPort
            if (discoveredPort != null) {
                adbExecutor.debug("Best ADB port discovered: $discoveredPort")
                emit(AdbState.Loading("🔍 Discovered Port: $discoveredPort"))
            } else {
                adbExecutor.debug("No ADB port discovered, fallback...")
                emit(AdbState.Loading("⚠️ No port discovered, using fallback"))
            }

            // Start ADB server
            emit(AdbState.Loading("Starting ADB server..."))
            adbExecutor.executeCommand(listOf("start-server"))
            delay(3000)

            // Connect using discovered port
            emit(AdbState.Loading("Connecting to device..."))
            if (discoveredPort != null) {
                val connectOutput = adbExecutor.executeCommand(listOf("connect", "localhost:$discoveredPort"))
                adbExecutor.debug("Connect result: $connectOutput")
                delay(2000)
            } else {
                adbExecutor.executeCommand(listOf("wait-for-device"))
                delay(2000)
            }

            // Check devices and auto-grant permission
            var connected = false
            var connectedDeviceSerial: String? = null

            for (attempt in 1..15) {
                emit(AdbState.Loading("🔍 Attempt $attempt/15: Looking for devices..."))
                val devices = adbExecutor.getConnectedDevices()

                if (devices.isNotEmpty()) {
                    connected = true

                    // Get device serial
                    connectedDeviceSerial = devices.firstOrNull()?.split("\t")?.firstOrNull()

                    // Auto-grant permission if not already granted
                    if (!hasPermission) {
                        emit(AdbState.Loading("🔐 Auto-granting WRITE_SECURE_SETTINGS permission...\n(This is a one-time setup)"))
                        delay(1000)

                        val grantSuccess = adbExecutor.startShellAndGrantPermission(connectedDeviceSerial)

                        if (grantSuccess) {
                            emit(AdbState.Loading("✓ Permission grant command sent!"))
                            delay(2000)

                            // Verify permission
                            val permissionVerified = permissionChecker.hasSecureSettingsPermission()
                            if (permissionVerified) {
                                emit(AdbState.Loading("✅ Permission verified!\nFull functionality enabled."))
                            } else {
                                emit(AdbState.Loading("⚠️ Permission grant pending.\nRestart app to activate."))
                            }
                        } else {
                            emit(AdbState.Loading("⚠️ Auto-grant may have failed.\nContinuing anyway..."))
                        }
                        delay(1000)
                    }

                    val output = buildString {
                        appendLine("✅ Successfully Connected!")
                        appendLine("\n━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("\n📱 Connected Device:")
                        devices.forEach { appendLine(it) }
                        if (discoveredPort != null) {
                            appendLine("\n🔍 Discovered Port: $discoveredPort")
                        }
                        appendLine("\n━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("\n💡 Setup Complete!")
                        appendLine("• Pairing: ✅ Saved")
                        if (!hasPermission) {
                            appendLine("• Permission: ✅ Auto-granted")
                            if (!permissionChecker.hasSecureSettingsPermission()) {
                                appendLine("\n⚠️ Note: Restart app for full functionality")
                            }
                        } else {
                            appendLine("• Permission: ✅ Already granted")
                        }
                        appendLine("• Auto-connect: ✅ Enabled")
                        appendLine("\n━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("\n🎉 Next time app opens,")
                        appendLine("it will auto-connect instantly!")
                    }
                    emit(AdbState.Success(output))
                    break
                }

                if (attempt < 15) delay(2000)
            }

            if (!connected) {
                emit(AdbState.Error("❌ Connection timeout after pairing!\n\nPairing was successful, but auto-connection failed.\n\nTry:\n1. Restart Wireless Debugging\n2. Restart app (it will auto-connect)\n3. Re-pair if issue persists"))
            }

        } catch (e: Exception) {
            emit(AdbState.Error("Error: ${e.message}"))
        }
    }
}