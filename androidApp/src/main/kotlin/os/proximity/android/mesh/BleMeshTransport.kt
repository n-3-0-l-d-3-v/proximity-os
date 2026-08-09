package os.proximity.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import os.proximity.shared.mesh.DiscoveredPeer
import os.proximity.shared.mesh.IncomingMessage
import os.proximity.shared.mesh.MeshTransport
import os.proximity.shared.util.currentTimeMillis
import kotlin.coroutines.resume

/**
 * Bluetooth LE implementation of [MeshTransport].
 *
 * Discovery is symmetric: this device both advertises [SERVICE_UUID] (so
 * peers can find it) and scans for the same UUID (so it can find peers).
 * Once a peer is [connect]ed, this device acts as a GATT *client* against
 * the peer's GATT *server* to write outbound messages, and runs its own
 * GATT server so the peer can write messages back — every device is both
 * a client and a server, matching the peer-to-peer nature of the mesh.
 *
 * Callers must have already obtained BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE /
 * BLUETOOTH_CONNECT (API 31+) or ACCESS_FINE_LOCATION (API < 31) before
 * calling [startDiscovery] — this class does not request permissions
 * itself, that belongs in the UI layer per docs/ARCHITECTURE.md.
 *
 * Not yet implemented (tracked as follow-up work): payload encryption,
 * message chunking beyond the negotiated MTU, and multi-hop relay. This is
 * the Phase 1 "can two phones see and message each other" slice.
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(context: Context) : MeshTransport {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private val discoveredPeersState = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    override val discoveredPeers: Flow<List<DiscoveredPeer>> =
        discoveredPeersState.map { it.values.sortedByDescending { peer -> peer.lastSeenEpochMillis } }

    private val connectedGatt = ConcurrentHashMap<String, BluetoothGatt>()
    private val incomingChannel = Channel<IncomingMessage>(Channel.BUFFERED)
    override val incomingMessages: Flow<IncomingMessage> = incomingChannel.receiveAsFlow()

    private var gattServer: BluetoothGattServer? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null

    override fun startDiscovery() {
        val bleScanner = adapter?.bluetoothLeScanner
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (bleScanner == null || advertiser == null) {
            Log.w(TAG, "Bluetooth LE not available; cannot start discovery")
            return
        }

        // Deferred until here (rather than in an init block) because opening a
        // GATT server requires BLUETOOTH_CONNECT, which the caller may not have
        // been granted yet at construction time.
        if (gattServer == null) {
            startGattServer()
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val peer = DiscoveredPeer(
                    transportAddress = device.address,
                    displayName = device.name,
                    rssi = result.rssi,
                    lastSeenEpochMillis = currentTimeMillis()
                )
                discoveredPeersState.value = discoveredPeersState.value + (peer.transportAddress to peer)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
            }
        }
        scanCallback = callback
        bleScanner.startScan(listOf(filter), settings, callback)

        val advSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .build()
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val advCallback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE advertise failed: $errorCode")
            }
        }
        advertiseCallback = advCallback
        advertiser.startAdvertising(advSettings, advData, advCallback)
    }

    override fun stopDiscovery() {
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        advertiseCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        scanCallback = null
        advertiseCallback = null
    }

    override suspend fun connect(peerAddress: String): Boolean {
        val device = adapter?.getRemoteDevice(peerAddress) ?: return false
        connectedGatt[peerAddress]?.let { return true }

        return suspendCancellableCoroutine { continuation ->
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connectedGatt.remove(peerAddress)
                        if (continuation.isActive) continuation.resume(false)
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connectedGatt[peerAddress] = gatt
                        if (continuation.isActive) continuation.resume(true)
                    } else if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
            device.connectGatt(appContext, false, callback)
        }
    }

    override suspend fun send(peerAddress: String, payload: ByteArray): Boolean {
        val gatt = connectedGatt[peerAddress] ?: return false
        val service = gatt.getService(SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(INBOX_CHARACTERISTIC_UUID) ?: return false
        characteristic.value = payload
        return gatt.writeCharacteristic(characteristic)
    }

    private fun startGattServer() {
        val server = bluetoothManager.openGattServer(appContext, object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (characteristic.uuid == INBOX_CHARACTERISTIC_UUID) {
                    incomingChannel.trySend(IncomingMessage(device.address, value))
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        })

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val inboxCharacteristic = BluetoothGattCharacteristic(
            INBOX_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(inboxCharacteristic)
        server?.addService(service)
        gattServer = server
    }

    companion object {
        private const val TAG = "BleMeshTransport"

        /** Identifies Proximity OS peers during scanning/advertising. */
        val SERVICE_UUID: UUID = UUID.fromString("7a4f9b1e-7e0e-4f2a-9b0e-1e2d3c4b5a69")

        /** Peers write outbound message payloads to this characteristic. */
        val INBOX_CHARACTERISTIC_UUID: UUID = UUID.fromString("7a4f9b1e-7e0e-4f2a-9b0e-1e2d3c4b5a6a")
    }
}
