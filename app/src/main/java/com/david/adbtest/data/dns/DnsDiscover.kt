package com.david.adbtest.data.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DNS"

class DnsDiscover private constructor(
    private val context: Context,
    private val nsdManager: NsdManager
) {
    private var started = false
    private var bestExpirationTime: Long? = null
    private var bestServiceName: String? = null
    private var pendingServices: MutableList<NsdServiceInfo> = Collections.synchronizedList(ArrayList())

    companion object {
        private var instance: DnsDiscover? = null
        var adbPort: Int? = null
        var pendingResolves = AtomicBoolean(false)
        var aliveTime: Long? = null

        fun getInstance(context: Context, nsdManager: NsdManager): DnsDiscover {
            return instance ?: DnsDiscover(context, nsdManager).also { instance = it }
        }

        // Reset DNS state (CRITICAL for re-scans)
        fun reset() {
            adbPort = null
            pendingResolves.set(false)
            aliveTime = null
        }
    }

    fun scanAdbPorts() {
        // CRITICAL FIX: Allow re-scanning by resetting started flag
        if (started) {
            Log.w(TAG, "Discovery already started, resetting...")
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop discovery: ${e.message}")
            }
            started = false
            // Clear previous state
            pendingServices.clear()
            adbPort = null
            bestExpirationTime = null
            bestServiceName = null
        }

        started = true
        aliveTime = System.currentTimeMillis()
        pendingResolves.set(false)

        try {
            nsdManager.discoverServices(
                "_adb-tls-connect._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery: ${e.message}")
            started = false
        }
    }

    private fun getLocalIpAddress(): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses

                    while (addresses.hasMoreElements()) {
                        val inetAddress = addresses.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    private fun updateIfNewest(serviceInfo: NsdServiceInfo) {
        val port = serviceInfo.port
        val expirationTime = parseExpirationTime(serviceInfo.toString())
        val serviceName = serviceInfo.serviceName

        fun getHighestNumberedString(strings: List<String>): String {
            return strings.maxByOrNull {
                """\((\d+)\)""".toRegex().find(it)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            } ?: strings.first()
        }

        fun update() {
            adbPort = port
            bestExpirationTime = expirationTime
            bestServiceName = serviceName
            Log.d(TAG, "Updated best match: $adbPort, $bestServiceName, $bestExpirationTime")
        }

        if (adbPort == null) {
            Log.d(TAG, "ADB port not yet set, updating best match...")
            update()
            return
        }

        if (expirationTime != null) {
            if (bestExpirationTime == null) {
                Log.d(TAG, "Expiration time not yet set, updating best match...")
                update()
                return
            }

            if (expirationTime > bestExpirationTime!!) {
                Log.d(TAG, "Expiration time is better, updating best match...")
                update()
                return
            } else {
                return
            }
        }

        if (serviceName == getHighestNumberedString(listOf(bestServiceName ?: "", serviceName))) {
            Log.d(TAG, "Service name is newer, updating best match...")
            update()
            return
        }
    }

    private fun parseExpirationTime(rawString: String): Long? {
        val regex = """expirationTime: (\S+)""".toRegex()
        val expirationTimeStr = regex.find(rawString)?.groupValues?.get(1)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        return try {
            dateFormat.parse(expirationTimeStr ?: "")?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        Log.d(TAG, "Resolve successful: $serviceInfo")
        Log.d(TAG, "Port: ${serviceInfo.port}")

        val ipAddress = getLocalIpAddress()
        val discoveredAddress = serviceInfo.host.hostAddress
        if (ipAddress != null && discoveredAddress != ipAddress) {
            Log.d(TAG, "IP does not match device")
            return
        }

        if (serviceInfo.port == 0) {
            Log.d(TAG, "Port is zero, skipping...")
            return
        }

        updateIfNewest(serviceInfo)
    }

    private fun resolveService(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode: $serviceInfo")
                when (errorCode) {
                    NsdManager.FAILURE_ALREADY_ACTIVE -> {
                        resolveService(serviceInfo)
                    }
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handleResolvedService(serviceInfo)
                pendingServices.removeAll { it.serviceName == serviceInfo.serviceName }
                if (pendingServices.isEmpty()) {
                    pendingResolves.set(false)
                }
                Log.d(TAG, "Service resolved, pending: ${pendingServices.size}")
            }
        }

        try {
            nsdManager.resolveService(service, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve service: ${e.message}")
            pendingServices.removeAll { it.serviceName == service.serviceName }
            if (pendingServices.isEmpty()) {
                pendingResolves.set(false)
            }
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Service discovery started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service discovery: $service")
            pendingServices.add(service)
            pendingResolves.set(true)
            resolveService(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(TAG, "Service lost: $service")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            started = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            started = false
        }
    }
}