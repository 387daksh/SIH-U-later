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
import kotlin.math.max

data class BluetoothPeer(val name: String, val address: String)

/**
 * Unpaired BLE mesh link following Bitchat's peripheral + central model. Every
 * phone advertises this service and scans for it; a deterministic central then
 * connects to the other phone's GATT server. RFCOMM and Android pairing are not used.
 */
class BluetoothPeerTransport(
    private val context: Context,
    private val listener: PeerTransportListener,
    sourceId: Long,
) : AutoCloseable {
    companion object {
        // Bitchat's production discovery UUIDs. SUTRA still carries its own
        // authenticated payload after this BLE link is established.
        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        private val CLIENT_CONFIGURATION_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)
        private const val MANUFACTURER_ID = 0x5349 // "SI"; identifies this GATT payload, not Bitchat's.
        private const val MAX_FRAME_BYTES = SutraCodec.MAX_PAYLOAD_BYTES + 1024
        private const val REQUESTED_MTU = 247
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager.adapter
    private val localNodeId = sourceId.toInt()
    private val discovered = LinkedHashMap<String, BluetoothDevice>()
    private val lock = Any()
    private val centralWrites = ArrayDeque<ByteArray>()
    private val serverNotifications = ArrayDeque<ByteArray>()
    private val centralAssembler = BleFrameAssembler(MAX_FRAME_BYTES)
    private val serverAssembler = BleFrameAssembler(MAX_FRAME_BYTES)
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private var serverPeer: BluetoothDevice? = null
    private var centralGatt: BluetoothGatt? = null
    private var centralCharacteristic: BluetoothGattCharacteristic? = null
    private var centralPeerAddress: String? = null
    private var centralReady = false
    private var scanning = false
    private var advertising = false
    private var serviceReady = false
    private var mtu = 23

    val isConnected: Boolean get() = synchronized(lock) { centralReady || serverPeer != null }

    /** Nearby unpaired BLE candidates; bonded devices are never consulted. */
    @SuppressLint("MissingPermission")
    fun nearbyPeers(): List<BluetoothPeer> = synchronized(lock) {
        discovered.values.map { device ->
            BluetoothPeer(device.name?.takeIf(String::isNotBlank) ?: "Nearby SUTRA phone", device.address)
        }.sortedBy(BluetoothPeer::name)
    }

    /** Start Bitchat-style advertising and service-filtered discovery. Safe to repeat. */
    @SuppressLint("MissingPermission")
    fun listen() {
        val activeAdapter = adapter
        if (activeAdapter == null || !activeAdapter.isEnabled) {
            listener.onTransportStatus("Bluetooth is off; turn it on to find nearby phones")
            return
        }
        ensureGattServer()
        startAdvertising()
        startScanning()
        listener.onTransportStatus("Searching for nearby phones (no Bluetooth pairing needed)")
    }

    /** Explicit UI selection still uses the unpaired BLE GATT path. */
    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = synchronized(lock) { discovered[address] }
            ?: throw IllegalArgumentException("That nearby phone is no longer visible")
        connectCentral(device)
    }

    fun send(bytes: ByteArray) {
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Invalid Bluetooth frame size" }
        synchronized(lock) {
            val fragments = BleFrameCodec.fragment(bytes, max(20, mtu - 3))
            when {
                centralReady && centralGatt != null && centralCharacteristic != null -> {
                    centralWrites.addAll(fragments)
                    writeNextCentralLocked()
                }
                serverPeer != null && serverCharacteristic != null -> {
                    serverNotifications.addAll(fragments)
                    notifyNextServerLocked()
                }
                else -> error("No nearby Bluetooth peer is connected")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureGattServer() {
        if (gattServer != null) return
        val server = manager.openGattServer(context, serverCallback)
        if (server == null) {
            listener.onTransportStatus("Bluetooth LE server could not start")
            return
        }
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                CLIENT_CONFIGURATION_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ))
        }
        gattServer = server
        serverCharacteristic = characteristic
        server.addService(BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(characteristic)
        })
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (advertising || !serviceReady) return
        val activeAdvertiser = adapter?.bluetoothLeAdvertiser
        if (activeAdvertiser == null) {
            listener.onTransportStatus("This phone cannot advertise Bluetooth LE")
            return
        }
        advertiser = activeAdvertiser
        val nodeBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(localNodeId).array()
        activeAdvertiser.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
            AdvertiseData.Builder().addServiceUuid(SERVICE_PARCEL_UUID)
                // Service UUID + manufacturer data fits the 31-byte BLE
                // advertising budget; service data would not.
                .addManufacturerData(MANUFACTURER_ID, nodeBytes).build(),
            advertiseCallback,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (scanning) return
        val activeScanner = adapter?.bluetoothLeScanner
        if (activeScanner == null) {
            listener.onTransportStatus("This phone cannot scan for Bluetooth LE devices")
            return
        }
        scanner = activeScanner
        activeScanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL_UUID).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        scanning = true
    }

    @SuppressLint("MissingPermission")
    private fun connectCentral(device: BluetoothDevice) {
        synchronized(lock) {
            if (centralPeerAddress == device.address || centralReady) return
            centralGatt?.close()
            centralGatt = null
            centralCharacteristic = null
            centralReady = false
            centralPeerAddress = device.address
            listener.onTransportStatus("Connecting to nearby Bluetooth phone")
            centralGatt = device.connectGatt(context, false, centralCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextCentralLocked() {
        val gatt = centralGatt ?: return
        val characteristic = centralCharacteristic ?: return
        val fragment = centralWrites.firstOrNull() ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = fragment
        if (!gatt.writeCharacteristic(characteristic)) {
            centralWrites.clear()
            listener.onTransportStatus("Bluetooth write could not start")
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyNextServerLocked() {
        val server = gattServer ?: return
        val peer = serverPeer ?: return
        val characteristic = serverCharacteristic ?: return
        val fragment = serverNotifications.firstOrNull() ?: return
        characteristic.value = fragment
        if (!server.notifyCharacteristicChanged(peer, characteristic, false)) {
            listener.onTransportStatus("Bluetooth notification queue is full")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { advertising = true }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            listener.onTransportStatus("Bluetooth advertising failed ($errorCode)")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val remoteIdBytes = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            if (remoteIdBytes.size != 4) return
            val remoteNodeId = ByteBuffer.wrap(remoteIdBytes).order(ByteOrder.BIG_ENDIAN).int
            if (remoteNodeId == localNodeId) return
            synchronized(lock) { discovered[device.address] = device }

            // The larger advertised id is the sole central, preventing two links.
            if (Integer.compareUnsigned(localNodeId, remoteNodeId) > 0) connectCentral(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onTransportStatus("Bluetooth scan failed ($errorCode)")
        }
    }

    private val centralCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                mtu = 23
                gatt.requestMtu(REQUESTED_MTU)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                synchronized(lock) {
                    if (centralGatt === gatt) {
                        centralReady = false
                        centralCharacteristic = null
                        centralWrites.clear()
                        centralGatt = null
                        centralPeerAddress = null
                    }
                }
                gatt.close()
                listener.onTransportStatus("Bluetooth disconnected; searching for nearby phones")
                startScanning()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtuValue: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = mtuValue
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID) ?: run {
                listener.onTransportStatus("Nearby device does not expose the SUTRA BLE service")
                return
            }
            synchronized(lock) { centralCharacteristic = characteristic }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIGURATION_UUID) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) listener.onTransportStatus("Bluetooth notifications could not be enabled")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CLIENT_CONFIGURATION_UUID || status != BluetoothGatt.GATT_SUCCESS) return
            synchronized(lock) { centralReady = true }
            listener.onTransportStatus("Bluetooth connected — ready to speak")
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            synchronized(lock) {
                if (status == BluetoothGatt.GATT_SUCCESS && centralWrites.isNotEmpty()) centralWrites.removeFirst()
                else if (status != BluetoothGatt.GATT_SUCCESS) centralWrites.clear()
                writeNextCentralLocked()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != CHARACTERISTIC_UUID) return
            centralAssembler.offer(characteristic.value ?: return)?.let(listener::onTransportMessage)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == SERVICE_UUID) {
                serviceReady = true
                startAdvertising()
            } else if (service.uuid == SERVICE_UUID) {
                listener.onTransportStatus("Bluetooth LE service could not be published")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(lock) {
                    if (serverPeer?.address == device.address) {
                        serverPeer = null
                        serverNotifications.clear()
                    }
                }
                listener.onTransportStatus("Bluetooth disconnected; searching for nearby phones")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                               preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val enabled = descriptor.uuid == CLIENT_CONFIGURATION_UUID &&
                value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            if (enabled) synchronized(lock) { serverPeer = device }
            if (responseNeeded) gattServer?.sendResponse(device, requestId,
                if (enabled) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (enabled) listener.onTransportStatus("Bluetooth connected — ready to speak")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                                                  characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
                                                  responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val accepted = characteristic.uuid == CHARACTERISTIC_UUID && !preparedWrite && offset == 0 && value != null
            if (responseNeeded) gattServer?.sendResponse(device, requestId,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (accepted) serverAssembler.offer(requireNotNull(value))?.let(listener::onTransportMessage)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) {
                if (status == BluetoothGatt.GATT_SUCCESS && serverNotifications.isNotEmpty()) serverNotifications.removeFirst()
                else if (status != BluetoothGatt.GATT_SUCCESS) serverNotifications.clear()
                notifyNextServerLocked()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        synchronized(lock) {
            centralWrites.clear(); serverNotifications.clear(); centralReady = false
            centralCharacteristic = null; centralPeerAddress = null
            centralGatt?.disconnect(); centralGatt?.close(); centralGatt = null
            serverPeer = null
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        disconnect()
        scanner?.stopScan(scanCallback)
        scanner = null; scanning = false
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null; advertising = false
        gattServer?.close(); gattServer = null; serverCharacteristic = null
        serviceReady = false
    }
}

/** GATT carries fragments; this restores regular SUTRA transport frames. */
internal object BleFrameCodec {
    private const val FIRST: Byte = 1
    private const val NEXT: Byte = 2
    private var nextId = 0

    @Synchronized
    fun fragment(frame: ByteArray, mtuPayload: Int): List<ByteArray> {
        require(mtuPayload >= 8)
        val id = nextId++ and 0xffff
        val firstPayload = mtuPayload - 7
        val nextPayload = mtuPayload - 3
        val fragments = ArrayList<ByteArray>()
        var offset = minOf(firstPayload, frame.size)
        fragments += ByteBuffer.allocate(7 + offset).order(ByteOrder.BIG_ENDIAN).apply {
            put(FIRST); putShort(id.toShort()); putInt(frame.size); put(frame, 0, offset)
        }.array()
        while (offset < frame.size) {
            val count = minOf(nextPayload, frame.size - offset)
            fragments += ByteBuffer.allocate(3 + count).order(ByteOrder.BIG_ENDIAN).apply {
                put(NEXT); putShort(id.toShort()); put(frame, offset, count)
            }.array()
            offset += count
        }
        return fragments
    }
}

internal class BleFrameAssembler(private val maxFrameBytes: Int) {
    private var id = -1
    private var expected = 0
    private var received = 0
    private var frame = ByteArray(0)

    fun offer(fragment: ByteArray): ByteArray? {
        if (fragment.size < 3) return null
        val input = ByteBuffer.wrap(fragment).order(ByteOrder.BIG_ENDIAN)
        val kind = input.get()
        val incomingId = input.short.toInt() and 0xffff
        if (kind == 1.toByte()) {
            if (input.remaining() < 4) return null
            val size = input.int
            if (size !in 1..maxFrameBytes || input.remaining() > size) return null
            id = incomingId; expected = size; received = input.remaining(); frame = ByteArray(size)
            input.get(frame, 0, received)
        } else if (kind == 2.toByte() && incomingId == id && received < expected) {
            val count = minOf(input.remaining(), expected - received)
            input.get(frame, received, count)
            received += count
        } else return null
        return if (received == expected) frame.also { id = -1; expected = 0; received = 0; frame = ByteArray(0) } else null
    }
}
