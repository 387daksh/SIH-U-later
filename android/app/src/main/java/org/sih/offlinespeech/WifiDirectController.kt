package org.sih.offlinespeech

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

data class WifiDirectPeer(val name: String, val address: String)

interface WifiDirectListener {
    fun onWifiDirectStatus(status: String)
    fun onWifiDirectPeers(peers: List<WifiDirectPeer>)
    fun onWifiDirectGroup(isOwner: Boolean, ownerAddress: String?)
}

class WifiDirectController(
    private val activity: Activity,
    private val listener: WifiDirectListener,
) : AutoCloseable {
    companion object {
        private const val INSTANCE = "_sutra"
        private const val SERVICE = "_sutra-speech._tcp"
    }

    private val manager = activity.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(activity, activity.mainLooper) {
        listener.onWifiDirectStatus("Wi-Fi Direct channel lost")
    }
    private val handler = Handler(activity.mainLooper)
    private val devices = LinkedHashMap<String, WifiP2pDevice>()
    private var registered = false
    private var groupSignature: String? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    listener.onWifiDirectStatus(if (enabled) "Wi-Fi Direct ready" else "Enable Wi-Fi")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun discover() {
        requirePermission()
        devices.clear()
        listener.onWifiDirectPeers(emptyList())
        manager.setDnsSdResponseListeners(
            channel,
            WifiP2pManager.DnsSdServiceResponseListener { instance, registration, device ->
                if (instance == INSTANCE && registration.startsWith(SERVICE)) addDevice(device)
            },
            WifiP2pManager.DnsSdTxtRecordListener { fullDomainName, record, device ->
                val matchesService = fullDomainName.contains(INSTANCE) || fullDomainName.contains(SERVICE)
                val matchesProtocol = record["protocol"] == "1"
                val matchesApp = record["app"] == "sutra" || matchesService
                if (matchesService && matchesProtocol && matchesApp) {
                    addDevice(device, record["name"])
                }
            },
        )
        // Sequentially chain advertise -> service search so wpa_supplicant doesn't fail with BUSY (2)
        advertise {
            startServiceSearch()
        }
    }

    private fun startServiceSearch(retryCount: Int = 0) {
        val request = WifiP2pDnsSdServiceRequest.newInstance(INSTANCE, SERVICE)
        serviceRequest = request

        val executeDiscovery = {
            manager.addServiceRequest(channel, request, action(
                success = {
                    manager.discoverServices(channel, action(
                        success = { listener.onWifiDirectStatus("Searching for nearby SUTRA apps...") },
                        failure = { reason ->
                            if (reason == WifiP2pManager.BUSY && retryCount < 3) {
                                listener.onWifiDirectStatus("Wi-Fi Direct busy; retrying in 500ms...")
                                handler.postDelayed({ startServiceSearch(retryCount + 1) }, 500)
                            } else {
                                listener.onWifiDirectStatus("Service search: ${errorText(reason)}")
                            }
                        },
                    ))
                },
                failure = { reason ->
                    if (reason == WifiP2pManager.BUSY && retryCount < 3) {
                        listener.onWifiDirectStatus("Wi-Fi Direct busy; retrying in 500ms...")
                        handler.postDelayed({ startServiceSearch(retryCount + 1) }, 500)
                    } else {
                        listener.onWifiDirectStatus("Could not add search (${errorText(reason)})")
                    }
                },
            ))
        }

        // Even if clearServiceRequests fails (or returns busy), proceed directly to addServiceRequest
        manager.clearServiceRequests(channel, action(
            success = { executeDiscovery() },
            failure = { executeDiscovery() },
        ))
    }

    fun connect(address: String) {
        requirePermission()
        val device = devices[address] ?: throw IllegalArgumentException("Select a discovered SUTRA phone")
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        listener.onWifiDirectStatus("Connecting to ${device.deviceName.ifBlank { device.deviceAddress }}")
        manager.connect(channel, config, action(
            success = {},
            failure = { listener.onWifiDirectStatus("Wi-Fi Direct connect failed ($it)") },
        ))
    }

    fun disconnect() {
        groupSignature = null
        manager.removeGroup(channel, action(
            success = { listener.onWifiDirectStatus("Wi-Fi Direct disconnected") },
            failure = { listener.onWifiDirectStatus("Disconnect failed ($it)") },
        ))
    }

    private fun advertise(onComplete: (() -> Unit)? = null) {
        val service = WifiP2pDnsSdServiceInfo.newInstance(
            INSTANCE,
            SERVICE,
            mapOf("app" to "sutra", "protocol" to "1", "name" to "${Build.MANUFACTURER} ${Build.MODEL}"),
        )
        manager.clearLocalServices(channel, action(
            success = {
                manager.addLocalService(channel, service, action(
                    success = {
                        listener.onWifiDirectStatus("SUTRA receive mode advertised")
                        onComplete?.invoke()
                    },
                    failure = { reason ->
                        listener.onWifiDirectStatus("Could not advertise SUTRA (${errorText(reason)})")
                        onComplete?.invoke()
                    },
                ))
            },
            failure = {
                manager.addLocalService(channel, service, action(
                    success = {
                        listener.onWifiDirectStatus("SUTRA receive mode advertised")
                        onComplete?.invoke()
                    },
                    failure = {
                        onComplete?.invoke()
                    },
                ))
            },
        ))
    }

    private fun errorText(code: Int): String = when (code) {
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.BUSY -> "radio busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "code $code"
    }

    private fun addDevice(device: WifiP2pDevice, advertisedName: String? = null) {
        device.deviceName = advertisedName ?: device.deviceName
        devices[device.deviceAddress] = device
        listener.onWifiDirectPeers(devices.values.map {
            WifiDirectPeer(it.deviceName.ifBlank { "SUTRA phone" }, it.deviceAddress)
        })
    }

    private fun requestConnectionInfo() {
        if (!hasPermission()) return
        manager.requestConnectionInfo(channel) { info ->
            if (!info.groupFormed) {
                groupSignature = null
                return@requestConnectionInfo
            }
            val address = info.groupOwnerAddress?.hostAddress
            val signature = "${info.isGroupOwner}:$address"
            if (signature != groupSignature) {
                groupSignature = signature
                listener.onWifiDirectGroup(info.isGroupOwner, address)
            }
        }
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requirePermission() = check(hasPermission()) { "Grant Nearby devices permission first" }

    private fun action(success: () -> Unit, failure: (Int) -> Unit) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = success()
            override fun onFailure(reason: Int) = failure(reason)
        }

    override fun close() {
        serviceRequest?.let { request -> runCatching { manager.removeServiceRequest(channel, request, action({}, {})) } }
        runCatching { manager.clearLocalServices(channel, action({}, {})) }
        if (registered) {
            runCatching { activity.unregisterReceiver(receiver) }
            registered = false
        }
    }
}
