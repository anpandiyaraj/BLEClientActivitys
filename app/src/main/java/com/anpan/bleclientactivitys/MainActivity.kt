package com.anpan.bleclientactivitys

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.os.*
import android.text.SpannableString
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

class MainActivity : AppCompatActivity() {

    // BLE Configuration
    private val SERVICE_UUID = UUID.fromString("726f72c1-055d-4f94-b090-c1afeec24780")
    private val CHAR_NOTIFY_UUID = UUID.fromString("c1cf0c5d-d07f-4f7c-ad2e-9cb3e49286b2")
    private val CHAR_WRITE_UUID = UUID.fromString("b12523bb-5e18-41fa-a498-cceb16bb7626")
    private val ESP32_MAC_ADDRESS = "5C:01:3B:95:90:AA"
    private val PASSKEY = "151784"

    // UI Components
    private lateinit var connectionStatusLabel: TextView
    private lateinit var responseLabel: TextView
    private lateinit var lockButton: Button
    private lateinit var unlockButton: Button
    private lateinit var locateMeButton: Button
    private lateinit var trunkButton: Button

    // BLE Objects
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    // Audio Feedback
    private var mediaLock: MediaPlayer? = null
    private var mediaConnect: MediaPlayer? = null

    // State Management
    private var currentLockStatus = "none" // "none", "locked", "unlocked"
    //private var isManualOperation = false
    private var isManualLock = false // New flag for manual lock state
    private var isLockButtonEnabled = true
    private var proximityConfirmedCount = 0

    // RSSI Processing
    private val rssiHistory = LinkedBlockingQueue<Int>()
    private var lastRssiTriggerTime = 0L
    private var lastRssiValue = 0

    // Thresholds
    private val UNLOCK_THRESHOLD = -88
    private val LOCK_THRESHOLD = -93
    private val PROXIMITY_CONFIRMATIONS_NEEDED = 3

    // Threading
    private val handler = Handler(Looper.getMainLooper())
    private val bleOperationQueue = LinkedBlockingQueue<() -> Unit>()
    private val connectionTimeoutHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val RSSI_HISTORY_SIZE = 5
        private const val RSSI_UPDATE_INTERVAL = 1000L
        private const val RSSI_CONFIRMATION_DELAY = 2000L
        private const val CONNECTION_TIMEOUT = 10000L
        private const val RECONNECT_DELAY = 5000L
    }

    // BLE Callbacks
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("BLE", "Connection state changed: $newState, status: $status")

            runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectionStatusLabel.text = SpannableString("Connected \uD83D\uDE97")
                        connectionStatusLabel.setTextColor(Color.GREEN)
                        mediaConnect?.start()
                        Handler(Looper.getMainLooper()).postDelayed({
                            mediaConnect?.pause()
                            mediaConnect?.seekTo(0)
                        }, 3000)

                        lockButton.isEnabled = true
                        unlockButton.isEnabled = true
                        trunkButton.isEnabled = true
                        locateMeButton.isEnabled = true
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectionStatusLabel.text = "Disconnected"
                        connectionStatusLabel.setTextColor(Color.RED)

                        lockButton.isEnabled = false
                        unlockButton.isEnabled = false
                        trunkButton.isEnabled = false
                        locateMeButton.isEnabled = false
                    }
                }
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionTimeoutHandler.removeCallbacksAndMessages(null)
                    Log.d("BLE", "Connected to ${gatt.device?.address}")
                    bleOperationQueue.add {
                        gatt.discoverServices()
                        gatt.readRemoteRssi()
                    }
                    startRssiUpdates(gatt)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLE", "Disconnected")
                    isManualLock = false // Reset manual lock flag on disconnection
                    stopRssiUpdates()
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Services discovered")
                val service = gatt.getService(SERVICE_UUID)
                writeCharacteristic = service?.getCharacteristic(CHAR_WRITE_UUID)
                notifyCharacteristic = service?.getCharacteristic(CHAR_NOTIFY_UUID)

                bleOperationQueue.add {
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        gatt.setCharacteristicNotification(notifyCharacteristic, true)
                    }
                }
            } else {
                Log.e("BLE", "Service discovery failed: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.getStringValue(0)
            Log.d("BLE", "Characteristic changed: $value")
            runOnUiThread {
                value?.let {
                    responseLabel.text = SpannableString("$it \n\uD83D\uDE97")
                    updateLockStatus(it)
                    updateButtonStates(it)
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "RSSI: $rssi")
                processRssiValue(rssi)
            } else {
                Log.e("BLE", "Failed to read RSSI: $status")
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.address == ESP32_MAC_ADDRESS) {
                Log.d("BLE", "Found target device")
                stopBleScan()
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed: $errorCode")
            handler.postDelayed({ startBleScan() }, RECONNECT_DELAY)
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    runOnUiThread {
                        connectionStatusLabel.text = "Bluetooth Off"
                        connectionStatusLabel.setTextColor(Color.RED)
                    }
                    isManualLock = false // Reset manual lock flag when Bluetooth turns off
                    stopBleOperations()
                }
                BluetoothAdapter.STATE_ON -> startBleScan()
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                if (device?.address == ESP32_MAC_ADDRESS) {
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            Log.d("BLE", "Device bonded")
                            bleOperationQueue.add {
                                if (ActivityCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    bluetoothGatt?.discoverServices()
                                }
                            }
                        }
                        BluetoothDevice.BOND_NONE -> initiatePairing(device)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        connectionStatusLabel = findViewById(R.id.connectionStatusLabel)
        responseLabel = findViewById(R.id.responseLabel)
        lockButton = findViewById(R.id.lockButton)
        unlockButton = findViewById(R.id.unlockButton)
        locateMeButton = findViewById(R.id.locateMeButton)
        trunkButton = findViewById(R.id.trunkButton)

        // Set initial states
        connectionStatusLabel.text = "Initializing..."
        connectionStatusLabel.setTextColor(Color.YELLOW)
        lockButton.isEnabled = false
        unlockButton.isEnabled = false
        trunkButton.isEnabled = false
        locateMeButton.isEnabled = false

        // Setup audio feedback
        mediaLock = MediaPlayer.create(this, R.raw.carlocksound)
        mediaConnect = MediaPlayer.create(this, R.raw.carstartsound)

        // Register receivers
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        // Setup Bluetooth
        setupBluetooth()

        // Setup button listeners
        setupButtons()

        // Start BLE worker thread
        Thread(BleWorker()).start()
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, 1)
        } else {
            startBleScan()
        }
    }

    private fun setupButtons() {
        lockButton.setOnClickListener {
           // isManualOperation = true
            isManualLock = true // Set manual lock flag
            sendCommand("LOCK")
            currentLockStatus = "locked"
            playLockSound()
            lockButton.isEnabled = false
            isLockButtonEnabled = lockButton.isEnabled
            locateMeButton.isEnabled = false
            trunkButton.isEnabled = false
        }

        unlockButton.setOnClickListener {
           // isManualOperation = true
            isManualLock = false // Reset manual lock flag on manual unlock
            sendCommand("UNLOCK")
            currentLockStatus = "unlocked"
            playLockSound()
            unlockButton.isEnabled = false
            locateMeButton.isEnabled = false
            trunkButton.isEnabled = false
        }

        trunkButton.setOnClickListener {
            isLockButtonEnabled = lockButton.isEnabled
            responseLabel.text = SpannableString("Opening Trunk \n\uD83D\uDE97")
            sendCommand("TRUNK")
            trunkButton.isEnabled = false
            lockButton.isEnabled = false
            unlockButton.isEnabled = false
            locateMeButton.isEnabled = false
        }

        locateMeButton.setOnClickListener {
            isLockButtonEnabled = lockButton.isEnabled
            responseLabel.text = SpannableString("Locating...\n\uD83D\uDE97")
            sendCommand("LOCATE")
            locateMeButton.isEnabled = false
            lockButton.isEnabled = false
            unlockButton.isEnabled = false
            trunkButton.isEnabled = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        runOnUiThread {
            connectionStatusLabel.text = "Scanning..."
            connectionStatusLabel.setTextColor(Color.YELLOW)
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_SCAN),
                1
            )
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setDeviceAddress(ESP32_MAC_ADDRESS).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        runOnUiThread {
            connectionStatusLabel.text = "Connecting..."
            connectionStatusLabel.setTextColor(Color.YELLOW)
        }

        connectionTimeoutHandler.postDelayed({
            if (bluetoothGatt?.device?.bondState != BluetoothDevice.BOND_BONDED) {
                runOnUiThread {
                    connectionStatusLabel.text = "Connection Timeout"
                    connectionStatusLabel.setTextColor(Color.RED)
                }
                bluetoothGatt?.disconnect()
            }
        }, CONNECTION_TIMEOUT)

        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect() {
        handler.postDelayed({
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (bluetoothGatt?.device?.bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d("BLE", "Attempting to reconnect...")
                    bluetoothGatt?.connect()
                } else {
                    Log.d("BLE", "Starting scan for reconnection...")
                    startBleScan()
                }
            }
        }, RECONNECT_DELAY)
    }

    @SuppressLint("MissingPermission")
    private fun startRssiUpdates(gatt: BluetoothGatt) {
        handler.post(object : Runnable {
            override fun run() {
                bleOperationQueue.add {
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        gatt.readRemoteRssi()
                    }
                }
                handler.postDelayed(this, RSSI_UPDATE_INTERVAL)
            }
        })
    }

    private fun stopRssiUpdates() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun processRssiValue(rssi: Int) {
        if (rssiHistory.size >= RSSI_HISTORY_SIZE) {
            rssiHistory.poll()
        }
        rssiHistory.add(rssi)
        lastRssiValue = rssi

        if (rssiHistory.size >= RSSI_HISTORY_SIZE) {
            val smoothedRssi = calculateSmoothedRssi()
            if (shouldAutoUnlock(smoothedRssi) || shouldAutoLock(smoothedRssi)) {
                handleDistanceChange(smoothedRssi)
            }
        }
    }

    private fun calculateSmoothedRssi(): Int {
        val sorted = rssiHistory.sorted()
        return sorted[rssiHistory.size / 2]
    }

    private fun shouldAutoUnlock(rssi: Int): Boolean {
        if (isManualLock) return false // Disable auto-unlock if manually locked

        if (rssi > UNLOCK_THRESHOLD) {
            proximityConfirmedCount++
            if (proximityConfirmedCount >= PROXIMITY_CONFIRMATIONS_NEEDED) {
                proximityConfirmedCount = 0
                return (!isManualLock &&
                        (currentLockStatus == "none" || currentLockStatus == "locked"))
            }
        } else {
            proximityConfirmedCount = 0
        }
        return false
    }

    private fun shouldAutoLock(rssi: Int): Boolean {
        if (rssi < LOCK_THRESHOLD) {
            return (currentLockStatus == "unlocked")
        }
        return false
    }

    private fun handleDistanceChange(rssi: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRssiTriggerTime < RSSI_CONFIRMATION_DELAY) {
            return
        }

        when {
            rssi > UNLOCK_THRESHOLD && (currentLockStatus == "none" || currentLockStatus == "locked") -> {
                sendCommand("UNLOCK")
                runOnUiThread {
                    responseLabel.text = SpannableString("Auto Unlocked \n\uD83D\uDE97")
                    lockButton.isEnabled = true
                    unlockButton.isEnabled = false
                    trunkButton.isEnabled = true
                    locateMeButton.isEnabled = true
                }
                currentLockStatus = "unlocked"
                lastRssiTriggerTime = currentTime
                playLockSound()
            }
            rssi < LOCK_THRESHOLD && currentLockStatus == "unlocked" -> {
                sendCommand("LOCK")
                runOnUiThread {
                    responseLabel.text = SpannableString("Auto Locked \n\uD83D\uDE97")
                    lockButton.isEnabled = false
                    unlockButton.isEnabled = true
                    trunkButton.isEnabled = true
                    locateMeButton.isEnabled = true
                }
                currentLockStatus = "locked"
                lastRssiTriggerTime = currentTime
                playLockSound()
            }
        }
    }

    private fun updateLockStatus(value: String) {
        when {
            value.contains("Unlocked", ignoreCase = true) -> {
                currentLockStatus = "unlocked"
                isManualLock = false // Reset manual lock flag on unlock
            }
            value.contains("Locked", ignoreCase = true) -> {
                currentLockStatus = "locked"

            }
        }
    }

    private fun updateButtonStates(value: String) {
        when {
            value.contains("Unlocked", ignoreCase = true) -> {
                lockButton.isEnabled = true
                unlockButton.isEnabled = false
                trunkButton.isEnabled = true
                locateMeButton.isEnabled = true
            }
            value.contains("Locked", ignoreCase = true) -> {
                lockButton.isEnabled = false
                unlockButton.isEnabled = true
                trunkButton.isEnabled = true
                locateMeButton.isEnabled = true
            }
            value.contains("Located", ignoreCase = true) -> {
                locateMeButton.isEnabled = true
                trunkButton.isEnabled = true
                if (isLockButtonEnabled) {
                    lockButton.isEnabled = true
                } else {
                    unlockButton.isEnabled = true
                }
            }
            value.contains("Trunk", ignoreCase = true) -> {
                trunkButton.isEnabled = true
                locateMeButton.isEnabled = true
                if (isLockButtonEnabled) {
                    lockButton.isEnabled = true
                } else {
                    unlockButton.isEnabled = true
                }
            }
            else -> {
                trunkButton.isEnabled = true
                locateMeButton.isEnabled = true
                if (isLockButtonEnabled) {
                    lockButton.isEnabled = true
                } else {
                    unlockButton.isEnabled = true
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(command: String) {
        bleOperationQueue.add {
            writeCharacteristic?.value = command.toByteArray()
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothGatt?.writeCharacteristic(writeCharacteristic)
            }
        }
    }

    private fun playLockSound() {
        mediaLock?.start()
        handler.postDelayed({
            mediaLock?.pause()
            mediaLock?.seekTo(0)
        }, 335)
    }

    @SuppressLint("MissingPermission")
    private fun initiatePairing(device: BluetoothDevice) {
        try {
            device.setPin(PASSKEY.toByteArray())
            device.createBond()
        } catch (e: Exception) {
            Log.e("BLE", "Pairing failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleOperations() {
        handler.removeCallbacksAndMessages(null)
        connectionTimeoutHandler.removeCallbacksAndMessages(null)
        bleOperationQueue.clear()
        bluetoothGatt?.disconnect()
    }

    inner class BleWorker : Runnable {
        override fun run() {
            while (true) {
                try {
                    val operation = bleOperationQueue.take()
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        operation()
                    }
                } catch (e: Exception) {
                    Log.e("BLE", "Operation failed", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bondStateReceiver)
        unregisterReceiver(bluetoothStateReceiver)
        stopBleOperations()
        bluetoothGatt?.close()
        mediaLock?.release()
        mediaConnect?.release()
    }
}
