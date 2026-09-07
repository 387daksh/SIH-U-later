package org.sih.offlinespeech

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class BluetoothPeer(val name: String, val address: String)

/**
 * Full BLE mesh transport modelled after Bitchat.
 *
 * Every phone simultaneously advertises the SUTRA service UUID and scans for it.
 * When two phones discover each other, the one with the larger localNodeId connects
 * as GATT central; the other becomes the GATT peripheral/server.  Both roles can be
 * active concurrently, so on a 3-phone mesh A > B > C:
 *   A: central to B and C
 *   B: central to C; peripheral to A
 *   C: peripheral to A and B
 *
 * All SUTRA and VoiceBurst frames are broadcast to every active link.
 * RFCOMM and Android pairing are never used.
 */
class BluetoothPeerTransport(
    private val context: Context,
    private val listener: PeerTransportListener,
    sourceId: Long,
) : AutoCloseable {

    companion object {
        // Bitchat's production discovery UUIDs reused for SUTRA.
        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        private val CLIENT_CONFIGURATION_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)
        private const val MANUFACTURER_ID = 0x5349   // "SI"
        private const val MAX_FRAME_BYTES = SutraCodec.MAX_PAYLOAD_BYTES + 1024
        private const val REQUESTED_MTU = 247
        private const val MAX_MESH_LINKS = 7  // typical BLE stack ceiling
    }

    // ── Per-link state ─────────────────────────────────────────────────────────

    /** State for a connection that WE initiated (central role). */
    private inner class CentralEntry(val address: String) {
        var gatt: BluetoothGatt? = null
        var characteristic: BluetoothGattCharacteristic? = null
        var ready = false
        var mtu = 23
        val writes = ArrayDeque<ByteArray>()
        val assembler = BleFrameAssembler(MAX_FRAME_BYTES)
    }

    /** State for a connection that a REMOTE peer initiated (we are peripheral/server). */
    private inner class ServerEntry(val device: BluetoothDevice) {
        val notifications = ArrayDeque<ByteArray>()
        val assembler = BleFrameAssembler(MAX_FRAME_BYTES)
    }

    // ── Fields ─────────────────────────────────────────────────────────────────

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager.adapter
    private val localNodeId = sourceId.toInt()  // Int comparison for deterministic role

    private val discovered         = ConcurrentHashMap<String, BluetoothDevice>()
    private val addressToRemoteInt  = ConcurrentHashMap<String, Int>()  // BLE addr -> peer's localNodeId
    private val centralEntries = ConcurrentHashMap<String, CentralEntry>()
    private val serverEntries  = ConcurrentHashMap<String, ServerEntry>()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var advertising = false
    private var serviceReady = false

    // ── Public API ─────────────────────────────────────────────────────────────

    val isConnected: Boolean get() =
        centralEntries.values.any { it.ready } || serverEntries.isNotEmpty()

    val connectedCount: Int get() =
        centralEntries.values.count { it.ready } + serverEntries.size

    /** Returns the 32-bit nodeId advertised by a peer (used for location correlation). */
    fun nodeIntForAddress(address: String): Int? = addressToRemoteInt[address]

    /** All BLE-visible SUTRA phones, whether connected or not. */
    @SuppressLint("MissingPermission")
    fun nearbyPeers(): List<BluetoothPeer> = discovered.values.map { d ->
        BluetoothPeer(d.name?.takeIf(String::isNotBlank) ?: "Nearby SUTRA phone", d.address)
    }.sortedBy(BluetoothPeer::name)

    /** Only currently active mesh peers. */
    @SuppressLint("MissingPermission")
    fun connectedPeers(): List<BluetoothPeer> {
        val result = mutableListOf<BluetoothPeer>()
        centralEntries.values.filter { it.ready }.forEach { e ->
            val dev = discovered[e.address]
            val name = dev?.name?.takeIf(String::isNotBlank) ?: "Phone …${e.address.takeLast(5)}"
            result += BluetoothPeer(name, e.address)
        }
        serverEntries.keys.forEach { addr ->
            val dev = discovered[addr] ?: serverEntries[addr]?.device
            @SuppressLint("MissingPermission")
            val name = dev?.name?.takeIf(String::isNotBlank) ?: "Phone …${addr.takeLast(5)}"
            result += BluetoothPeer(name, addr)
        }
        return result.distinctBy { it.address }
    }

    /** Start BLE advertising + scanning.  Safe to call multiple times. */
    @SuppressLint("MissingPermission")
    fun listen() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            listener.onTransportStatus("Bluetooth is off — turn it on to join the mesh")
            return
        }
        ensureGattServer()
        startAdvertising()
        startScanning()
        listener.onTransportStatus("Joining mesh — scanning for nearby phones (no pairing needed)")
    }

    /** Explicit connect to a discovered address (e.g. UI-initiated). */
    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = discovered[address]
            ?: throw IllegalArgumentException("That nearby phone is no longer visible")
        connectCentral(device)
    }

    /**
     * Broadcast bytes to EVERY connected peer (both central and server links).
     * Call this for voice bursts and SUTRA messages; replaces the old single-peer send().
     */
    fun broadcast(bytes: ByteArray) {
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Invalid mesh frame size" }
        var sent = false
        // --- central links (we initiated) ---
        centralEntries.values.filter { it.ready }.forEach { entry ->
            synchronized(entry) {
                val g = entry.gatt ?: return@forEach
                val c = entry.characteristic ?: return@forEach
                BleFrameCodec.fragment(bytes, max(20, entry.mtu - 3)).forEach(entry.writes::addLast)
                writeNextCentralLocked(entry, g, c)
            }
            sent = true
        }
        // --- server links (remote initiated) ---
        val ch = serverCharacteristic
        serverEntries.values.forEach { se ->
            if (ch == null) return@forEach
            synchronized(se) {
                BleFrameCodec.fragment(bytes, 20).forEach(se.notifications::addLast)
                notifyNextServerLocked(se, ch)
            }
            sent = true
        }
        if (!sent) error("No peers are connected in the mesh")
    }

    /** send() is kept for interface compatibility; it delegates to broadcast(). */
    fun send(bytes: ByteArray) = broadcast(bytes)

    // ── Private helpers ────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun ensureGattServer() {
        if (gattServer != null) return
        val srv = manager.openGattServer(context, serverCallback) ?: run {
            listener.onTransportStatus("BLE GATT server could not start")
            return
        }
        val ch = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                CLIENT_CONFIGURATION_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ))
        }
        gattServer = srv
        serverCharacteristic = ch
        srv.addService(BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .apply { addCharacteristic(ch) })
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (advertising || !serviceReady) return
        val adv = adapter?.bluetoothLeAdvertiser ?: run {
            listener.onTransportStatus("This phone cannot advertise BLE")
            return
        }
        advertiser = adv
        val nodeBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(localNodeId).array()
        adv.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build(),
            AdvertiseData.Builder()
                .addServiceUuid(SERVICE_PARCEL_UUID)
                .addManufacturerData(MANUFACTURER_ID, nodeBytes)
                .build(),
            advertiseCallback,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (scanning) return
        val sc = adapter?.bluetoothLeScanner ?: run {
            listener.onTransportStatus("This phone cannot scan BLE")
            return
        }
        scanner = sc
        sc.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL_UUID).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        scanning = true
    }

    @SuppressLint("MissingPermission")
    private fun connectCentral(device: BluetoothDevice) {
        val address = device.address
        if (centralEntries.containsKey(address)) return   // already connecting/connected
        if (centralEntries.size + serverEntries.size >= MAX_MESH_LINKS) return
        val entry = CentralEntry(address)
        centralEntries[address] = entry
        entry.gatt = device.connectGatt(context, false, makeCentralCallback(entry), BluetoothDevice.TRANSPORT_LE)
    }

    @Suppress("DEPRECATION")
    private fun writeNextCentralLocked(
        entry: CentralEntry,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
    ) {
        val frag = entry.writes.firstOrNull() ?: return
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = frag
        @SuppressLint("MissingPermission")
        if (!gatt.writeCharacteristic(ch)) {
            entry.writes.clear()
            listener.onTransportStatus("BLE write failed to …${entry.address.takeLast(5)}")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun notifyNextServerLocked(se: ServerEntry, ch: BluetoothGattCharacteristic) {
        val srv = gattServer ?: return
        val frag = se.notifications.firstOrNull() ?: return
        ch.value = frag
        @Suppress("DEPRECATION")
        srv.notifyCharacteristicChanged(se.device, ch, false)
    }

    private fun updateMeshStatus() {
        val n = connectedCount
        listener.onTransportStatus(when (n) {
            0 -> "Mesh: scanning — no phones found yet"
            1 -> "Mesh: 1 phone connected — ready to speak"
            else -> "Mesh: $n phones connected — ready to speak"
        })
    }

    // ── BLE callbacks ──────────────────────────────────────────────────────────

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(s: AdvertiseSettings) { advertising = true }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            listener.onTransportStatus("BLE advertising failed ($errorCode)")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: ScanResult) {
            val device = result.device ?: return
            val mfr = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            if (mfr.size != 4) return
            val remoteId = ByteBuffer.wrap(mfr).order(ByteOrder.BIG_ENDIAN).int
            if (remoteId == localNodeId) return
            discovered[device.address] = device
            addressToRemoteInt[device.address] = remoteId
            // Deterministic role: the phone with the larger nodeId is the central for this pair.
            if (Integer.compareUnsigned(localNodeId, remoteId) > 0) connectCentral(device)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onTransportStatus("BLE scan failed ($errorCode)")
        }
    }

    /** Creates an independent GATT callback per outgoing (central) connection. */
    private fun makeCentralCallback(entry: CentralEntry) = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(REQUESTED_MTU)
                gatt.discoverServices()
            } else {
                gatt.close()
                centralEntries.remove(entry.address)
                updateMeshStatus()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtuValue: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) synchronized(entry) { entry.mtu = mtuValue }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID) ?: return
            synchronized(entry) { entry.characteristic = ch }
            gatt.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CLIENT_CONFIGURATION_UUID) ?: return
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(desc)
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorWrite(gatt: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
            if (desc.uuid != CLIENT_CONFIGURATION_UUID || status != BluetoothGatt.GATT_SUCCESS) return
            synchronized(entry) { entry.ready = true }
            updateMeshStatus()
            // Signal service to broadcast our GPS location to this new peer.
            listener.onTransportMessage(MeshLocationCodec.SENTINEL)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            synchronized(entry) {
                if (status == BluetoothGatt.GATT_SUCCESS && entry.writes.isNotEmpty())
                    entry.writes.removeFirst()
                else if (status != BluetoothGatt.GATT_SUCCESS)
                    entry.writes.clear()
                val g = entry.gatt ?: return
                val c = entry.characteristic ?: return
                writeNextCentralLocked(entry, g, c)
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != CHARACTERISTIC_UUID) return
            entry.assembler.offer(ch.value ?: return)?.let(listener::onTransportMessage)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == SERVICE_UUID) {
                serviceReady = true
                startAdvertising()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverEntries.remove(device.address)
                updateMeshStatus()
            }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val enabled = descriptor.uuid == CLIENT_CONFIGURATION_UUID &&
                value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            if (enabled) {
                discovered[device.address] = device
                serverEntries.getOrPut(device.address) { ServerEntry(device) }
                updateMeshStatus()
                // Signal service to send our GPS location to this new peer.
                listener.onTransportMessage(MeshLocationCodec.SENTINEL)
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId,
                if (enabled) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val accepted = characteristic.uuid == CHARACTERISTIC_UUID &&
                !preparedWrite && offset == 0 && value != null
            if (responseNeeded) gattServer?.sendResponse(device, requestId,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (accepted) {
                val se = serverEntries.getOrPut(device.address) {
                    discovered[device.address] = device
                    ServerEntry(device)
                }
                se.assembler.offer(requireNotNull(value))?.let(listener::onTransportMessage)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val se = serverEntries[device.address] ?: return
            val ch = serverCharacteristic ?: return
            synchronized(se) {
                if (status == BluetoothGatt.GATT_SUCCESS && se.notifications.isNotEmpty())
                    se.notifications.removeFirst()
                else if (status != BluetoothGatt.GATT_SUCCESS)
                    se.notifications.clear()
                notifyNextServerLocked(se, ch)
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun disconnect() {
        centralEntries.values.forEach { e ->
            synchronized(e) { e.writes.clear(); e.ready = false }
            e.gatt?.disconnect(); e.gatt?.close(); e.gatt = null
        }
        centralEntries.clear()
        serverEntries.clear()
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        disconnect()
        scanner?.stopScan(scanCallback); scanner = null; scanning = false
        advertiser?.stopAdvertising(advertiseCallback); advertiser = null; advertising = false
        gattServer?.close(); gattServer = null; serverCharacteristic = null; serviceReady = false
    }
}

// ── Frame codec — Bitchat-compatible fragment planner + assembly buffer ──────────
//
// Wire format (matches BLEOutboundFragmentPlanner / BLEFragmentAssemblyBuffer):
//   [fragmentID : 8 bytes, random]
//   [index      : UInt16 big-endian]
//   [total      : UInt16 big-endian]
//   [data       : remainder]
//
// Each per-link BleFrameAssembler tracks multiple in-flight fragment streams
// simultaneously (identified by fragmentID), reassembles them out-of-order,
// evicts the oldest stream when the in-flight cap is exceeded, and expires
// incomplete streams after STREAM_TTL_MS.  This faithfully ports bitchat's
// BLEFragmentAssemblyBuffer behaviour to Kotlin / Android.

/** Fragments a SUTRA frame into MTU-sized BLE writes (Bitchat wire format). */
internal object BleFrameCodec {
    const val FRAGMENT_ID_BYTES = 8
    // Minimum header: fragmentID(8) + index(2) + total(2) = 12 bytes
    private const val HEADER_BYTES = FRAGMENT_ID_BYTES + 2 + 2
    private val random = java.security.SecureRandom()

    /**
     * Splits [frame] into BLE-MTU-sized fragments.  Each fragment carries the
     * same random [fragmentID], its [index] and the [total] count so the receiver
     * can reassemble them out-of-order — matching bitchat's planner contract.
     */
    fun fragment(frame: ByteArray, mtuPayload: Int): List<ByteArray> {
        require(mtuPayload > HEADER_BYTES) { "MTU too small for fragment header" }
        val chunkSize = mtuPayload - HEADER_BYTES
        val fragmentId = ByteArray(FRAGMENT_ID_BYTES).also(random::nextBytes)
        val chunks = frame.toList().chunked(chunkSize) { it.toByteArray() }
        val total = chunks.size
        return chunks.mapIndexed { index, chunk ->
            ByteBuffer.allocate(HEADER_BYTES + chunk.size).order(ByteOrder.BIG_ENDIAN).apply {
                put(fragmentId)
                putShort(index.toShort())
                putShort(total.toShort())
                put(chunk)
            }.array()
        }
    }
}

/**
 * Per-link inbound fragment reassembler — a Kotlin port of bitchat's
 * [BLEFragmentAssemblyBuffer].
 *
 * Differences from the old single-stream assembler:
 *  - Tracks up to [maxInFlight] concurrent fragment streams by fragmentID.
 *  - Reassembles fragments **out of order**: stores by (fragmentID → Map<index, bytes>).
 *  - Evicts the **oldest** stream when [maxInFlight] is exceeded.
 *  - Expires incomplete streams after [streamTtlMs] since first fragment.
 *  - Enforces a [maxFrameBytes] size cap; oversized streams are evicted immediately.
 */
internal class BleFrameAssembler(
    private val maxFrameBytes: Int,
    private val maxInFlight: Int = 32,
    private val streamTtlMs: Long = 30_000L,
) {
    private data class StreamKey(val id: String)   // hex of 8-byte fragmentID

    private class StreamState(val total: Int, val startedAt: Long) {
        val fragments = HashMap<Int, ByteArray>()
    }

    private val streams = LinkedHashMap<StreamKey, StreamState>()  // insertion-ordered for eviction

    /**
     * Offer one raw BLE write to the assembler.
     *
     * Returns the fully reassembled frame bytes when the last fragment of a
     * stream arrives, or null if the stream is still incomplete.
     *
     * Wire layout expected: [fragmentID:8][index:u16][total:u16][data…]
     */
    fun offer(fragment: ByteArray): ByteArray? {
        val hdrSize = BleFrameCodec.FRAGMENT_ID_BYTES + 4   // id + index(2) + total(2)
        if (fragment.size < hdrSize) return null

        val buf = ByteBuffer.wrap(fragment).order(ByteOrder.BIG_ENDIAN)
        val idBytes = ByteArray(BleFrameCodec.FRAGMENT_ID_BYTES).also(buf::get)
        val index = buf.short.toInt() and 0xffff
        val total = buf.short.toInt() and 0xffff
        val data  = ByteArray(buf.remaining()).also(buf::get)

        if (total <= 0 || total > 10_000 || index < 0 || index >= total) return null
        if (data.size > maxFrameBytes) return null

        val key = StreamKey(idBytes.joinToString("") { "%02x".format(it) })
        val now = System.currentTimeMillis()

        expireStaleStreams(now)

        // Start a new stream or retrieve existing one
        val stream = streams.getOrPut(key) {
            evictOldestIfNeeded()
            StreamState(total, now)
        }

        // Validate total is consistent across fragments of the same stream
        if (stream.total != total) {
            streams.remove(key)
            return null
        }

        // Size guard: reject if adding this fragment would overflow the limit
        val currentSize = stream.fragments.values.sumOf { it.size }
        if (currentSize + data.size > maxFrameBytes) {
            streams.remove(key)
            return null
        }

        stream.fragments[index] = data   // idempotent: duplicate fragments are harmless

        if (stream.fragments.size < total) return null   // still waiting for more

        // All fragments received — reassemble in order
        val assembled = ByteArray(stream.fragments.values.sumOf { it.size })
        var pos = 0
        for (i in 0 until total) {
            val part = stream.fragments[i] ?: run { streams.remove(key); return null }
            part.copyInto(assembled, pos)
            pos += part.size
        }
        streams.remove(key)
        return assembled
    }

    private fun expireStaleStreams(now: Long) {
        val it = streams.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.startedAt > streamTtlMs) it.remove()
        }
    }

    private fun evictOldestIfNeeded() {
        if (streams.size >= maxInFlight) {
            streams.iterator().apply { if (hasNext()) { next(); remove() } }
        }
    }
}
