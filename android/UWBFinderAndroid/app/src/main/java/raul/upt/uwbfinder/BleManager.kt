package raul.upt.uwbfinder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Scans for, connects to, and receives distance notifications from Stella.
 * Port of BLEManager.swift.
 *
 * All public methods must be called on the main thread. Scan results, GATT
 * callbacks and the Bluetooth state broadcast are delivered on the main thread
 * as well, so no locking is needed.
 */
class BleManager(context: Context) {

    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED,
        BLUETOOTH_OFF,
    }

    companion object {
        // GATT layout (must match stella_ble_tracker.ino and the iOS app)
        val SERVICE_UUID: UUID = UUID.fromString("A1E8F400-EE45-4832-A144-6F3E7F0D6B01")
        val DISTANCE_CHAR_UUID: UUID = UUID.fromString("A1E8F401-EE45-4832-A144-6F3E7F0D6B01")

        /** Client Characteristic Configuration Descriptor (Bluetooth SIG, 0x2902). */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Same auto-retry delay as the iOS app. */
        private const val RETRY_DELAY_MS = 1000L

        /** Runtime permissions needed to scan and connect on this Android version. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean =
            requiredPermissions().all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _distanceM = MutableStateFlow<Double?>(null)
    val distanceM: StateFlow<Double?> = _distanceM.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var started = false
    private var scanning = false
    private var receiverRegistered = false
    private var gatt: BluetoothGatt? = null

    private val retryRunnable = Runnable { startScanning() }

    /** Starts scanning (or reports Bluetooth off). Call once permissions are granted. */
    fun start() {
        if (started) return
        started = true
        registerStateReceiver()
        startScanning()
    }

    /** Stops scanning, drops the connection and clears the last distance. */
    fun stop() {
        if (!started) return
        started = false
        mainHandler.removeCallbacks(retryRunnable)
        unregisterStateReceiver()
        stopScanning()
        closeGatt()
        _distanceM.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ----- Scanning -----

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!started || scanning || gatt != null) return
        if (!hasPermissions(appContext)) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = ConnectionState.BLUETOOTH_OFF
            return
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            _connectionState.value = ConnectionState.SCANNING
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // Permission revoked while scanning; nothing left to stop.
        } catch (e: IllegalStateException) {
            // Bluetooth was switched off while scanning.
        }
    }

    private fun scheduleRetry() {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleRetry()
        }
    }

    // ----- Connection -----

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!started || gatt != null) return
        stopScanning()
        _connectionState.value = ConnectionState.CONNECTING
        val newGatt = try {
            device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                mainHandler,
            )
        } catch (e: SecurityException) {
            null
        }
        if (newGatt == null) {
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleRetry()
        } else {
            gatt = newGatt
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
            g.close()
        } catch (e: SecurityException) {
            // Permission revoked; the system tears the connection down.
        }
    }

    /** Stella may have gone briefly out of range: drop the link and rescan. */
    private fun handleDisconnect() {
        closeGatt()
        _connectionState.value = ConnectionState.DISCONNECTED
        _distanceM.value = null
        if (started) scheduleRetry()
    }

    private fun handleDistanceValue(characteristicUuid: UUID, value: ByteArray?) {
        if (characteristicUuid != DISTANCE_CHAR_UUID) return
        if (value == null || value.size != 4) return
        // Stella sends a little-endian IEEE 754 float, in metres.
        val metres = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).float
        _distanceM.value = metres.toDouble()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.CONNECTED
                if (!g.discoverServices()) handleDisconnect()
            } else if (status != BluetoothGatt.GATT_SUCCESS ||
                newState == BluetoothProfile.STATE_DISCONNECTED
            ) {
                handleDisconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt || status != BluetoothGatt.GATT_SUCCESS) return
            val characteristic = g.getService(SERVICE_UUID)
                ?.getCharacteristic(DISTANCE_CHAR_UUID) ?: return
            if (!g.setCharacteristicNotification(characteristic, true)) return
            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        // Android 13 (API 33) and later.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (g !== gatt) return
            handleDistanceValue(characteristic.uuid, value)
        }

        // Android 12L (API 32) and earlier.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (g !== gatt) return
            handleDistanceValue(characteristic.uuid, characteristic.value)
        }
    }

    // ----- Bluetooth on/off -----

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startScanning()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    mainHandler.removeCallbacks(retryRunnable)
                    scanning = false
                    closeGatt()
                    _distanceM.value = null
                    _connectionState.value = ConnectionState.BLUETOOTH_OFF
                }
            }
        }
    }

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        // ACTION_STATE_CHANGED is a protected system broadcast, so the receiver
        // needs no exported / not-exported flag, including on Android 14+.
        appContext.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        receiverRegistered = true
    }

    private fun unregisterStateReceiver() {
        if (!receiverRegistered) return
        appContext.unregisterReceiver(stateReceiver)
        receiverRegistered = false
    }
}
