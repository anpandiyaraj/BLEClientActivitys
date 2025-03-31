package com.anpan.bleclientactivitys

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.nio.charset.Charset
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var connectionStatusLabel: TextView
    private lateinit var responseLabel: TextView
    private lateinit var logTextView: TextView
    private lateinit var lockButton: Button
    private lateinit var unlockButton: Button
    private lateinit var locateMeButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            logMethodCall("reconnectRunnable")
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothGatt == null || bluetoothGatt?.device?.bondState != BluetoothDevice.BOND_BONDED) {
                    startBleScan()
                }
            }
            handler.postDelayed(this, 5000)
        }
    }
    private val rssiUpdateInterval = 2000L
    private var lockStatus = "none"
    private var isLockButtonPressedManually = false
    private var lastCharacteristicValue: String? = null

    private val SERVICE_UUID = UUID.fromString("a1c658ed-1df2-4c5c-8477-708f714f01f7")
    private val CHAR_WRITE_UUID = UUID.fromString("f16c9c3c-fbcc-4a8c-b130-0e79948b8f82")
    private val CHAR_NOTIFY_UUID = UUID.fromString("7dc6ca3d-f066-4bda-a742-4deb534b58d5")
    private val ESP32_MAC_ADDRESS = "5E:01:3C:96:FD:56"
    private val PASSKEY = "123456"

    private lateinit var enableBluetoothLauncher: ActivityResultLauncher<Intent>

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logMethodCall("onReceive")
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState =
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val prevBondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.ERROR
                )

                Log.d(
                    "BLEBond",
                    "Bond state changed for ${device?.address}: $prevBondState -> $bondState"
                )

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d("BLEBond", "Device bonded successfully")
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGatt?.discoverServices()
                    }
                }
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        startBleScan()
                        Log.d("Debug", "Last characteristic value: $lastCharacteristicValue")
                        if (lastCharacteristicValue == "Unlocked") {
                            lockButton.isEnabled = true
                            unlockButton.isEnabled = false
                        } else if (lastCharacteristicValue == "Locked") {
                            lockButton.isEnabled = false
                            unlockButton.isEnabled = true
                        } else {
                            lockButton.isEnabled = true
                            unlockButton.isEnabled = false
                        }
                        findViewById<Button>(R.id.trunkButton).isEnabled = true
                        locateMeButton.isEnabled = true
                    }

                    BluetoothAdapter.STATE_OFF -> {
                        stopBleScan()
                        lockButton.isEnabled = false
                        unlockButton.isEnabled = false
                        findViewById<Button>(R.id.trunkButton).isEnabled = false
                        connectionStatusLabel.text = getString(R.string.disconnected)
                        connectionStatusLabel.setTextColor(getColor(R.color.red))
                        locateMeButton.isEnabled = false
                    }
                }
            }
        }
    }

    private val pairingRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                device?.let {
                    val variant = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.ERROR
                    )
                    Log.d("BLEPairing", "Pairing variant: $variant")

                    when (variant) {
                        BluetoothDevice.PAIRING_VARIANT_PIN -> {
                            try {
                                it.setPin(PASSKEY.toByteArray())
                                abortBroadcast()
                            } catch (e: SecurityException) {
                                Log.e("BLEPairing", "Security exception: ${e.message}")
                            }
                        }

                        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                            try {
                                it.setPairingConfirmation(true)
                                abortBroadcast()
                            } catch (e: SecurityException) {
                                Log.e("BLEPairing", "Security exception: ${e.message}")
                            }
                        }

                        else -> {
                            Log.d("BLEPairing", "Unhandled pairing variant: $variant")
                        }
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            logMethodCall("onScanResult")
            val deviceName = result.device.name ?: "Unknown Device"
            val deviceAddress = result.device.address
            val rssi = result.rssi

            Log.d("BLEScan", "Found device: $deviceName ($deviceAddress) RSSI: $rssi")

            if (deviceAddress == ESP32_MAC_ADDRESS) {
                logMethodCall("Found device: $deviceName ($deviceAddress) RSSI: $rssi\n")
                Log.d("BLEScan", "Found target device: $deviceName ($deviceAddress)")
                stopBleScan()
                connectToDevice(result.device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onScanFailed(errorCode: Int) {
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error code $errorCode"
            }
            Log.e("BLEScan", "Scan failed: $errorMessage")
            isScanning = false

            if (bluetoothGatt?.device?.bondState != BluetoothDevice.BOND_BONDED) {
                handler.postDelayed({ startBleScan() }, SCAN_INTERVAL)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val statusMessage = when (status) {
                BluetoothGatt.GATT_SUCCESS -> "Success"
                else -> "Error: $status"
            }

            Log.d("BLEConnection", "Connection state change: $statusMessage, newState: $newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread {
                        logMethodCall(getString(R.string.connected))
                        connectionStatusLabel.text = getString(R.string.connected)
                        connectionStatusLabel.setTextColor(getColor(R.color.green))
                        locateMeButton.isEnabled = true
                    }
                    Log.d("BLEConnection", "Connected to device: ${gatt.device.address}")

                    handler.removeCallbacks(reconnectRunnable)
                    bluetoothGatt = gatt

                    when (gatt.device.bondState) {
                        BluetoothDevice.BOND_NONE -> {
                            Log.d("BLEConnection", "Initiating bonding")
                            initiatePairing(gatt.device)
                        }

                        BluetoothDevice.BOND_BONDED -> {
                            Log.d("BLEConnection", "Device already bonded, discovering services")
                            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                bluetoothGatt?.discoverServices()
                            }
                        }

                        BluetoothDevice.BOND_BONDING -> {
                            Log.d("BLEConnection", "Bonding in progress")
                        }
                    }

                    startRssiUpdates(gatt)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLEConnection", "Disconnected from device")
                    runOnUiThread {
                        connectionStatusLabel.text = getString(R.string.disconnected)
                        connectionStatusLabel.setTextColor(getColor(R.color.red))
                        startBleScan()
                        locateMeButton.isEnabled = false
                    }
                    handler.post(reconnectRunnable)
                    stopRssiUpdates()
                    lockStatus = "none"
                    isLockButtonPressedManually = false
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            logMethodCall("onReadRemoteRssi")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLEConnection", "RSSI: $rssi")
                runOnUiThread {
                    logMethodCall("RSSI: $rssi")
                    handleRssiValue(rssi)
                }
            } else {
                Log.e("BLEConnection", "Failed to read RSSI: $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logMethodCall("onServicesDiscovered")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                writeCharacteristic = service.getCharacteristic(CHAR_WRITE_UUID)
                notifyCharacteristic = service.getCharacteristic(CHAR_NOTIFY_UUID)
                performBleOperation {
                    gatt.setCharacteristicNotification(
                        notifyCharacteristic,
                        true
                    )
                }

                runOnUiThread {
                    lockButton.isEnabled = true
                    unlockButton.isEnabled = true
                    locateMeButton.isEnabled = true
                }
            } else {
                Log.e("BLEService", "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            logMethodCall("onCharacteristicChanged")
            val value = characteristic.getValue()?.toString(Charset.defaultCharset())
            runOnUiThread {
                if (value != null) {
                    responseLabel.text = value
                    logMethodCall(value)
                    handleCharacteristicChanged(value)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkPermissions()
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        connectionStatusLabel = findViewById(R.id.connectionStatusLabel)
        responseLabel = findViewById(R.id.responseLabel)
        logTextView = findViewById(R.id.logTextView)
        logTextView.visibility = View.GONE
        lockButton = findViewById(R.id.lockButton)
        unlockButton = findViewById(R.id.unlockButton)
        locateMeButton = findViewById(R.id.locateMeButton)
        lockButton.isEnabled = false
        unlockButton.isEnabled = false
        locateMeButton.isEnabled = false
        setupButtons()

        enableBluetoothLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    startBleScan()
                } else {
                    Log.e("MainActivity", "Bluetooth not enabled")
                }
            }

        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        registerReceiver(
            pairingRequestReceiver,
            IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST)
        )
        startBleScan()
        handler.post(reconnectRunnable)
    }

    private fun setupButtons() {
        logMethodCall("setupButtons")
        lockButton.setOnClickListener {
            logMethodCall("lockButton onClick")
            writeToCharacteristic(CHAR_WRITE_UUID, "Lock")
            lockStatus = "locked"
            isLockButtonPressedManually = true
            lockButton.isEnabled = false
            unlockButton.isEnabled = true
        }
        unlockButton.setOnClickListener {
            logMethodCall("unlockButton onClick")
            writeToCharacteristic(CHAR_WRITE_UUID, "UnLock")
            lockStatus = "unlocked"
            isLockButtonPressedManually = false
            lockButton.isEnabled = true
            unlockButton.isEnabled = false
        }
        findViewById<Button>(R.id.trunkButton).setOnClickListener {
            logMethodCall("trunkButton onClick")
            writeToCharacteristic(CHAR_WRITE_UUID, "Trunk Release")
        }
        locateMeButton.setOnClickListener {
            logMethodCall("locateMeButton onClick")
            writeToCharacteristic(CHAR_WRITE_UUID, "Locate Me")
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun writeToCharacteristic(uuid: UUID, command: String) {
        logMethodCall("writeToCharacteristic")
        val characteristic = bluetoothGatt?.getService(SERVICE_UUID)?.getCharacteristic(uuid)
        if (characteristic == null) {
            Log.e("BLEWrite", "Characteristic not found")
            return
        }

        bluetoothGatt?.let { gatt ->
            characteristic.value = command.toByteArray()
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                handler.post {
                    val result = gatt.writeCharacteristic(
                        characteristic,
                        characteristic.value,
                        characteristic.writeType
                    )
                    if (result == BluetoothGatt.GATT_SUCCESS) {
                        Log.d("BLEWrite", "Characteristic written successfully")
                    } else {
                        Log.e("BLEWrite", "Failed to write characteristic, result: $result")
                    }
                }
            }
        }
    }

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                Log.d("MainActivity", "${it.key} = ${it.value}")
            }
        }

    private fun checkPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        requestMultiplePermissions.launch(requiredPermissions)
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        logMethodCall("startBleScan")

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.d("BLEScan", "Bluetooth scan permission not granted")
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN), 1)
            return
        }

        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled) {
            Log.d("BLEScan", "Bluetooth is not enabled")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.d("BLEScan", "Location services are not enabled")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BLEScan", "BluetoothLeScanner is null")
            return
        }

        stopBleScan()

        val scanFilter = ScanFilter.Builder()
            .setDeviceAddress(ESP32_MAC_ADDRESS)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d("BLEScan", "Starting scan")
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    private fun stopBleScan() {
        logMethodCall("stopBleScan")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        logMethodCall("connectToDevice")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothGatt = device.connectGatt(this, false, gattCallback)
            } catch (e: SecurityException) {
                runOnUiThread {
                    logMethodCall(getString(R.string.ble_operation_failed, e.message))
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initiatePairing(device: BluetoothDevice) {
        logMethodCall("initiatePairing")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val pinBytes = PASSKEY.toByteArray()
            if (!device.setPin(pinBytes)) {
                Log.e("BLEPairing", "Failed to set PIN")
                return
            }

            if (!device.createBond()) {
                Log.e("BLEPairing", "Failed to initiate bonding")
            }
        } catch (e: Exception) {
            Log.e("BLEPairing", "Error during pairing: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        logMethodCall("onDestroy")
        super.onDestroy()
        performBleOperation { bluetoothGatt?.disconnect() }
        unregisterReceiver(bondStateReceiver)
        unregisterReceiver(bluetoothStateReceiver)
        unregisterReceiver(pairingRequestReceiver)
        handler.removeCallbacks(reconnectRunnable)
    }

    private fun performBleOperation(operation: () -> Unit) {
        logMethodCall("performBleOperation")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                operation()
            } catch (e: SecurityException) {
                runOnUiThread {
                    logMethodCall(getString(R.string.ble_operation_failed, e.message))
                }
            }
        }
    }

    private fun startRssiUpdates(gatt: BluetoothGatt) {
        handler.post(object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.readRemoteRssi()
                    handler.postDelayed(this, rssiUpdateInterval)
                }
            }
        })
    }

    private fun stopRssiUpdates() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun handleRssiValue(rssi: Int) {
        if (rssi in -85..-30 && (lockStatus == "none" || lockStatus == "locked") && !isLockButtonPressedManually) {
            findViewById<Button>(R.id.unlockButton).performClick()
        } else if (rssi in -90..-86 && lockStatus == "unlocked") {
            findViewById<Button>(R.id.lockButton).performClick()
        }
    }

    private fun handleCharacteristicChanged(value: String) {
        lastCharacteristicValue = value
        when (value) {
            "Unlocked" -> {
                lockButton.isEnabled = true
                unlockButton.isEnabled = false
            }

            "Locked" -> {
                lockButton.isEnabled = false
                unlockButton.isEnabled = true
            }
        }
    }

    private fun logMethodCall(methodName: String) {
        Log.d("MethodCall", "$methodName called")
    }

    companion object {
        private const val SCAN_INTERVAL = 30000L
        private var isScanning = false
    }
}
