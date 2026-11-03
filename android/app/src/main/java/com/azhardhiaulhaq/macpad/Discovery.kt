package com.azhardhiaulhaq.macpad

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

internal class MacService(val name: String, val host: InetAddress, val port: Int, val serverIdHex: String?)

/** Finds `_macpad._tcp` services. All state and [onChange] live on the main thread. */
internal class Discovery(context: Context, private val onChange: (List<MacService>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val executor = context.mainExecutor
    private val macs = LinkedHashMap<String, MacService>()
    private var listener: NsdManager.DiscoveryListener? = null

    // API 34+: one live info callback per service name.
    private val watchers = HashMap<String, NsdManager.ServiceInfoCallback>()

    // API 33: resolveService handles one request at a time.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery failed: $errorCode")
                main.post { if (listener === this) listener = null }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { if (listener === this) found(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (listener === this) lost(info.serviceName) }
            }
        }
        listener = l
        Log.d(TAG, "NSD discovery start")
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        Log.d(TAG, "NSD discovery stop")
        try {
            nsd.stopServiceDiscovery(l)
        } catch (_: IllegalArgumentException) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (cb in watchers.values) unwatch(cb)
        }
        watchers.clear()
        resolveQueue.clear()
        if (macs.isNotEmpty()) {
            macs.clear()
            onChange(emptyList())
        }
    }

    private fun found(info: NsdServiceInfo) {
        val name = info.serviceName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (watchers.containsKey(name)) return
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(TAG, "NSD info callback failed for $name: $errorCode")
                    if (watchers[name] === this) watchers.remove(name)
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    if (watchers[name] !== this) return
                    val addresses = serviceInfo.hostAddresses
                    val host = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return
                    update(name, host, serviceInfo)
                }

                override fun onServiceLost() {
                    if (watchers[name] === this) remove(name)
                }

                override fun onServiceInfoCallbackUnregistered() {}
            }
            watchers[name] = cb
            nsd.registerServiceInfoCallback(info, executor, cb)
        } else {
            resolveQueue.addLast(info)
            resolveNext()
        }
    }

    private fun lost(name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            watchers.remove(name)?.let { unwatch(it) }
        }
        remove(name)
    }

    private fun unwatch(cb: NsdManager.ServiceInfoCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            nsd.unregisterServiceInfoCallback(cb)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun remove(name: String) {
        if (macs.remove(name) != null) onChange(macs.values.toList())
    }

    private fun update(name: String, host: InetAddress, info: NsdServiceInfo) {
        Log.d(TAG, "NSD resolved $name at ${host.hostAddress}:${info.port}")
        val id = info.attributes["id"]?.let { String(it, Charsets.US_ASCII).lowercase() }
        macs[name] = MacService(name, host, info.port, id)
        onChange(macs.values.toList())
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        val l = listener ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    resolving = false
                    val host = serviceInfo.host
                    if (listener === l && host != null) update(serviceInfo.serviceName, host, serviceInfo)
                    resolveNext()
                }
            }
        })
    }

    private companion object {
        const val SERVICE_TYPE = "_macpad._tcp"
    }
}
