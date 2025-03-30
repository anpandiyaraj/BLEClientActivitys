package com.anpan.bleclientactivitys

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.nio.charset.Charset
import java.util.*

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
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothGatt == null || bluetoothGatt?.device?.bondState != BluetoothDevice.BOND_BONDED) {
                    startBleScan()
                }
            }
            handler.postDelayed(this, 5000) // Schedule again after 5 seconds
        }
    }
    private val rssiUpdateInterval: Long = 2000 // Interval in milliseconds
    private var lockStatus: String = "none" // Default lock status
    private var isLockButtonPressedManually: Boolean = false // Track manual lock button press
    private var lastCharacteristicValue: String? = null // Store last received characteristic value

    private val SERVICE_UUID = UUID.fromString("a1c658ed-1df2-4c5c-8477-708f714f01f7")
    private val CHAR_WRITE_UUID = UUID.fromString("f16c9c3c-fbcc-4a8c-b130-0e79948b8f82")
    private val CHAR_NOTIFY_UUID = UUID.fromString("7dc6ca3d-f066-4bda-a742-4deb534b58d5")
    private val ESP32_MAC_ADDRESS = "5C:01:3B:96:DD:56"
    private val PASSKEY = "123456"

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logMethodCall("onReceive")
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == intent.action) {
                val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

                Log.d("BLEBond", "Bond state changed for ${device?.address}: $prevBondState -> $bondState")

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d("BLEBond", "Device bonded successfully")
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGatt?.discoverServices()
                    }
                }
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        // Bluetooth is turned on, start BLE scan
                        startBleScan()
                        // Enable buttons based on last received characteristic value
                        Log.d("Debug", "Last characteristic value: $lastCharacteristicValue")
                        if (lastCharacteristicValue == "Unlocked") {
                            lockButton.isEnabled = true
                            unlockButton.isEnabled = false
                            Log.d("Debug", "Buttons set to: lockButton enabled, unlockButton disabled")
                        } else if (lastCharacteristicValue == "Locked") {
                            lockButton.isEnabled = false
                            unlockButton.isEnabled = true
                            Log.d("Debug", "Buttons set to: lockButton disabled, unlockButton enabled")
                        } else {
                            lockButton.isEnabled = true
                            unlockButton.isEnabled = false
                            Log.d("Debug", "Buttons set to: lockButton disabled, unlockButton disabled")
                        }
                        findViewById<Button>(R.id.trunkButton).isEnabled = true
                        locateMeButton.isEnabled = true // Enable Locate Me button when Bluetooth is on
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        // Bluetooth is turned off, stop BLE scan
                        stopBleScan()
                        // Disable buttons
                        lockButton.isEnabled = false
                        unlockButton.isEnabled = false
                        findViewById<Button>(R.id.trunkButton).isEnabled = false
                        connectionStatusLabel.text = getString(R.string.disconnected)
                        connectionStatusLabel.setTextColor(getColor(R.color.red))
                        locateMeButton.isEnabled = false // Disable Locate Me button when Bluetooth is off
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            logMethodCall("onScanResult")
            val deviceName = if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                result.device.name ?: "Unknown Device"
            } else {
                "Unknown Device"
            }
            val deviceAddress = result.device.address
            if (deviceAddress == ESP32_MAC_ADDRESS) {
                logTextView.append("Found device: $deviceName ($deviceAddress)\n")
                Log.d("BLEScan", "Found device: $deviceName ($deviceAddress)")
            }

            if (deviceAddress == ESP32_MAC_ADDRESS) {
                stopBleScan()
                connectToDevice(result.device)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logMethodCall("onConnectionStateChange")
            val statusMessage = when (status) {
                BluetoothGatt.GATT_SUCCESS -> "Success"
                else -> "Error: $status"
            }

            Log.d("BLEConnection", "Connection state change: $statusMessage, newState: $newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread {
                        connectionStatusLabel.text = getString(R.string.connected)
                        connectionStatusLabel.setTextColor(getColor(R.color.green))
                        locateMeButton.isEnabled = true // Enable Locate Me button on connection
                    }
                    Log.d("BLEConnection", "Connected to device: ${gatt.device.address}")

                    // Remove reconnect runnable on successful connection
                    handler.removeCallbacks(reconnectRunnable)

                    bluetoothGatt = gatt // Re-initialize BluetoothGatt object

                    when (gatt.device.bondState) {
                        BluetoothDevice.BOND_NONE -> {
                            Log.d("BLEConnection", "Initiating bonding")
                            gatt.device.createBond()
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            Log.d("BLEConnection", "Device already bonded, discovering services")
                            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                bluetoothGatt?.discoverServices()
                            }
                        }
                        BluetoothDevice.BOND_BONDING -> {
                            Log.d("BLEConnection", "Bonding in progress")
                        }
                    }

                    // Start periodic RSSI reading
                    startRssiUpdates(gatt)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLEConnection", "Disconnected from device")
                    runOnUiThread {
                        connectionStatusLabel.text = getString(R.string.disconnected)
                        connectionStatusLabel.setTextColor(getColor(R.color.red))
                        startBleScan()
                        locateMeButton.isEnabled = false // Disable Locate Me button on disconnection
                    }
                    // Re-add reconnect runnable on disconnection
                    handler.post(reconnectRunnable)

                    // Stop periodic RSSI reading
                    stopRssiUpdates()

                    // Reset lock status and manual lock button press
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
                performBleOperation { gatt.setCharacteristicNotification(notifyCharacteristic, true) }
            } else {
                Log.e("BLEService", "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            logMethodCall("onCharacteristicChanged")
            val value = characteristic.value?.toString(Charset.defaultCharset())
            runOnUiThread {
                if (value != null) {
                    responseLabel.text = value
                    handleCharacteristicChanged(value)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        connectionStatusLabel = findViewById(R.id.connectionStatusLabel)
        responseLabel = findViewById(R.id.responseLabel)
        logTextView = findViewById(R.id.logTextView)
        logTextView.visibility = View.GONE // Hide logTextView by default
        lockButton = findViewById(R.id.lockButton)
        unlockButton = findViewById(R.id.unlockButton)
        locateMeButton = findViewById(R.id.locateMeButton)
        lockButton.isEnabled = false // Disable lock button by default
        unlockButton.isEnabled = false // Disable unlock button by default
        locateMeButton.isEnabled = false // Disable Locate Me button by default
        setupButtons()

        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        startBleScan()
        handler.post(reconnectRunnable) // Start the reconnect task
    }

    private fun setupButtons() {
        logMethodCall("setupButtons")
        lockButton.setOnClickListener {
            logMethodCall("lockButton onClick")
            writeToCharacteristic(CHAR_WRITE_UUID, "Lock")
            lockStatus = "locked"
            isLockButtonPressedManually = true // Set manual lock button press
            lockButton.isEnabled = false
            unlockButton.isEnabled = true
        }
        unlockButton.setOnClickListener {
            logMethodCall("unlockButton onClick")
            writeToCharacteristic(CHAR_WRITE_UUID, "UnLock")
            lockStatus = "unlocked"
            isLockButtonPressedManually = false // Reset manual lock button press
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

    @SuppressLint("MissingPermission")
    private fun writeToCharacteristic(uuid: UUID, command: String) {
        logMethodCall("writeToCharacteristic")
        val characteristic = bluetoothGatt?.getService(SERVICE_UUID)?.getCharacteristic(uuid)
        if (characteristic == null) {
            Log.e("BLEWrite", "Characteristic not found")
            return
        }
        characteristic.value = command.toByteArray()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            handler.post {
                val success = bluetoothGatt?.writeCharacteristic(characteristic) ?: false
                if (!success) {
                    Log.e("BLEWrite", "Failed to write characteristic")
                }
            }
        }
    }

    private fun startBleScan() {
        logMethodCall("startBleScan")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ), 1)
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // Prompt user to enable location services
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BLEScan", "BluetoothLeScanner is null")
            return
        }
        stopBleScan() // Stop any ongoing scan before starting a new one
        val scanFilter = ScanFilter.Builder().build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    private fun stopBleScan() {
        logMethodCall("stopBleScan")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        logMethodCall("connectToDevice")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothGatt = device.connectGatt(this, true, gattCallback)
                initiatePairing(device)
            } catch (e: SecurityException) {
                runOnUiThread {
                    responseLabel.text = getString(R.string.ble_operation_failed, e.message)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initiatePairing(device: BluetoothDevice) {
        logMethodCall("initiatePairing")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            Log.d("BLEPairing", "Initiating pairing with device: ${device.address}")
            device.createBond()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    private fun sendCommand(command: String) {
        logMethodCall("sendCommand")
        writeCharacteristic?.let { characteristic ->
            characteristic.value = command.toByteArray()
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        logMethodCall("onDestroy")
        super.onDestroy()
        performBleOperation { bluetoothGatt?.close() }
        unregisterReceiver(bondStateReceiver)
        unregisterReceiver(bluetoothStateReceiver)
        handler.removeCallbacks(reconnectRunnable) // Stop the reconnect task
    }

    private fun performBleOperation(operation: () -> Unit) {
        logMethodCall("performBleOperation")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                operation()
            } catch (e: SecurityException) {
                runOnUiThread {
                    responseLabel.text = getString(R.string.ble_operation_failed, e.message)
                }
            }
        }
    }

    private fun startRssiUpdates(gatt: BluetoothGatt) {
        handler.post(object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
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
        logTextView.setText("$methodName called\n")
        Log.d("MethodCall", "$methodName called")
    }

companion object {
    private const val REQUEST_ENABLE_BT = 1
}
}