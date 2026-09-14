package org.sih.offlinespeech

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

data class WifiDirectPeer(val name: String, val address: String)
interface WifiDirectListener {
    fun onWifiDirectStatus(status: String)
    fun onWifiDirectPeers(peers: List<WifiDirectPeer>)
    fun onWifiDirectGroup(isOwner: Boolean, ownerAddress: String?)
    fun onWifiDirectGroupLost() = Unit
}

@SuppressLint("MissingPermission")
class WifiDirectController(private val context: Context, private val listener: WifiDirectListener,
    private val nodeId: String = "legacy") : AutoCloseable {
    companion object {
        private const val INSTANCE = "_sutra"
        private const val SERVICE = "_sutra-speech._tcp"
    }
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private val devices = LinkedHashMap<String, WifiDirectPeer>()
    private val candidates = LinkedHashMap<String, AutoPeer>()
    private var registered = false
    private var automatic = false
    private var closed = false
    private var operation = false
    private var operationEpoch = 0
    private var connectingUntil = 0L
    private var grouped = false
    private var owner = false
    private var permissionReported = false
    private var lastAttempt = 0L
    private var retries = 0
    private var idleSince = 0L
    private val retryDiscover = Runnable { if (automatic && !closed) discover() }
    private val tick = object : Runnable {
        override fun run() {
            if (!automatic || closed) return
            if (hasPermission()) {
                permissionReported = false
                requestConnectionInfo()
                if (!grouped || owner) discover()
            } else if (!permissionReported) {
                listener.onWifiDirectStatus("Allow Nearby devices to connect automatically")
                permissionReported = true
            }
            handler.postDelayed(this, 15_000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) {
                        operationEpoch++; operation = false; connectingUntil = 0
                        grouped = false; owner = false
                        listener.onWifiDirectGroupLost()
                        listener.onWifiDirectStatus("Wi-Fi Direct disabled; enable Wi-Fi and check hotspot settings")
                    } else if (automatic) { handler.removeCallbacks(tick); handler.post(tick) }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }
    private fun localService() = WifiP2pDnsSdServiceInfo.newInstance(INSTANCE, SERVICE, mapOf(
        "app" to "sutra", "protocol" to "1", "node" to nodeId,
        "role" to if (!grouped) "idle" else if (owner) "owner" else "client",
        "name" to "${Build.MANUFACTURER} ${Build.MODEL}"))

    /** A chain that dies between clearLocalServices and addLocalService leaves this
     *  phone invisible to everyone else, so always put the advert back on failure. */
    private fun readvertise() {
        val ch = channel ?: return
        manager.addLocalService(ch, localService(), action({}, {}))
    }

    private fun scheduleRetry() {
        if (!automatic || closed) return
        retries = (retries + 1).coerceAtMost(4)
        handler.removeCallbacks(retryDiscover)
        handler.postDelayed(retryDiscover, 2_000L shl (retries - 1))
    }

    private fun openChannel(): WifiP2pManager.Channel {
        channel?.let { return it }
        return manager.initialize(context, Looper.getMainLooper()) {
            channel = null
            operationEpoch++; operation = false; grouped = false; owner = false
            listener.onWifiDirectGroupLost()
            listener.onWifiDirectStatus("Wi-Fi Direct channel lost; reconnecting")
        }.also { channel = it }
    }
    fun start() {
        if (registered || closed) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }
        registered = true
        openChannel()
    }
    fun startAutomatic() {
        if (closed) return
        start()
        automatic = true
        retries = 0
        idleSince = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    fun stopAutomatic() {
        automatic = false
        retries = 0
        operationEpoch++; operation = false; connectingUntil = 0
        handler.removeCallbacksAndMessages(null)
        channel?.let { runCatching { manager.stopPeerDiscovery(it, action({}, {})) } }
    }
    fun discover() {
        if (closed || !hasPermission() || operation || (grouped && !owner)) return
        val now = SystemClock.elapsedRealtime()
        if (now < connectingUntil) return
        if (connectingUntil != 0L) {
            connectingUntil = 0
            channel?.let { manager.cancelConnect(it, action({}, {})) }
            return
        }
        val ch = openChannel()
        operation = true
        val epoch = ++operationEpoch
        val fail: (Int) -> Unit = { reason ->
            if (epoch == operationEpoch && !closed) {
                operation = false
                readvertise()
                listener.onWifiDirectStatus(if (reason == WifiP2pManager.BUSY)
                    "Wi-Fi Direct busy; will retry automatically. Check hotspot if this persists."
                    else "Wi-Fi Direct search failed ($reason); retrying automatically")
                scheduleRetry()
            }
        }
        fun step(next: () -> Unit) = action({
            if (!closed && epoch == operationEpoch) next()
        }, fail)
        handler.postDelayed({
            if (epoch == operationEpoch && operation) {
                operationEpoch++; operation = false
                readvertise()
                listener.onWifiDirectStatus("Wi-Fi Direct search timed out; retrying")
                scheduleRetry()
            }
        }, 12_000)
        manager.setDnsSdResponseListeners(ch, { instance, registration, device ->
            if (!closed && instance == INSTANCE && registration.startsWith(SERVICE)) {
                devices[device.deviceAddress] = WifiDirectPeer(device.deviceName.ifBlank { "SUTRA phone" }, device.deviceAddress)
                listener.onWifiDirectPeers(devices.values.toList())
            }
        }, { domain, record, device ->
            if (!closed && domain.contains(INSTANCE) && record["app"] == "sutra" && record["protocol"] == "1") {
                val address = device.deviceAddress
                devices[address] = WifiDirectPeer(record["name"] ?: device.deviceName, address)
                val node = record["node"]
                if (node != null && node.length <= 64 && record["role"] in listOf("idle", "owner", "client")) {
                    candidates[address] = AutoPeer(address, node, record["role"]!!, SystemClock.elapsedRealtime())
                }
                listener.onWifiDirectPeers(devices.values.toList())
            }
        })
        // Stop the old search first. Never run discoverPeers and discoverServices in parallel.
        manager.stopPeerDiscovery(ch, step {
            manager.clearLocalServices(ch, step {
                manager.addLocalService(ch, localService(), step {
                    manager.clearServiceRequests(ch, step {
                        manager.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(INSTANCE, SERVICE), step {
                            manager.discoverServices(ch, step {
                                operation = false
                                retries = 0
                                listener.onWifiDirectStatus("Automatically searching for SUTRA phones")
                                handler.postDelayed({
                                    if (automatic && !closed && epoch == operationEpoch && !grouped &&
                                        SystemClock.elapsedRealtime() - lastAttempt > 30_000) {
                                        val now = SystemClock.elapsedRealtime()
                                        chooseAutoPeer(nodeId, candidates.values, now, idleSince)
                                            ?.let { connect(it.address) }
                                    }
                                }, 5_000)
                            })
                        })
                    })
                })
            })
        })
    }
    fun connect(address: String) {
        check(hasPermission()) { "Grant Nearby devices permission first" }
        require(devices.containsKey(address)) { "Select a discovered SUTRA phone" }
        if (closed || grouped || SystemClock.elapsedRealtime() < connectingUntil) return
        operationEpoch++; operation = false
        val attempt = SystemClock.elapsedRealtime()
        lastAttempt = attempt
        connectingUntil = attempt + 30_000
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = if (candidates[address]?.role == "owner") 0 else 15
        }
        val ch = openChannel()
        // Stopping discovery first ages the peer out of wpa_supplicant's cache within milliseconds
        // (P2P-FIND-STOPPED -> P2P-DEVICE-LOST), so the immediately-following connect gets dropped
        // by the framework as an unknown peer. Connect directly while the peer is still fresh.
        manager.connect(ch, config, action({
            listener.onWifiDirectStatus("Connecting to ${devices[address]?.name}; accept Android's prompt if shown")
        }, {
            connectingUntil = 0
            listener.onWifiDirectStatus("Wi-Fi connection failed ($it); automatic retry pending")
            scheduleRetry()
        }))
    }
    private fun requestConnectionInfo() {
        if (closed || !hasPermission()) return
        val ch = openChannel()
        manager.requestConnectionInfo(ch) { info ->
            if (closed || channel !== ch) return@requestConnectionInfo
            val changed = grouped != info.groupFormed || owner != info.isGroupOwner
            if (grouped && !info.groupFormed) listener.onWifiDirectGroupLost()
            grouped = info.groupFormed
            owner = info.isGroupOwner
            if (!grouped && changed) idleSince = SystemClock.elapsedRealtime()
            if (grouped) {
                connectingUntil = 0
                idleSince = SystemClock.elapsedRealtime()
                listener.onWifiDirectGroup(owner, info.groupOwnerAddress?.hostAddress)
                if (changed) {
                    operationEpoch++; operation = false
                    // Refresh the advertised role so later phones join this existing group.
                    if (owner) handler.post { discover() }
                    else manager.clearLocalServices(ch, action({
                        if (!closed && grouped && !owner) manager.addLocalService(ch,
                            WifiP2pDnsSdServiceInfo.newInstance(INSTANCE, SERVICE, mapOf(
                                "app" to "sutra", "protocol" to "1", "node" to nodeId,
                                "role" to "client", "name" to "${Build.MANUFACTURER} ${Build.MODEL}")),
                            action({}, {}))
                    }, {}))
                }
            }
        }
    }
    fun disconnect() {
        stopAutomatic()
        grouped = false; owner = false
        channel?.let { ch ->
            runCatching { manager.cancelConnect(ch, action({}, {})) }
            runCatching { manager.removeGroup(ch, action({}, {})) }
        }
        listener.onWifiDirectGroupLost()
    }
    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
    private fun action(success: () -> Unit, failure: (Int) -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = success()
        override fun onFailure(reason: Int) = failure(reason)
    }
    override fun close() {
        closed = true
        stopAutomatic()
        channel?.let { ch ->
            runCatching { manager.clearServiceRequests(ch, action({}, {})) }
            runCatching { manager.clearLocalServices(ch, action({}, {})) }
            if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        }
        channel = null
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}
