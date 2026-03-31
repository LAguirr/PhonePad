package com.phonepad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private lateinit var bleClient: BleMouseClient
    private var isReady by mutableStateOf(false)
    private var permissionGranted by mutableStateOf(false)
    private var connectedDeviceName by mutableStateOf<String?>(null)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        permissionGranted = allGranted
        if (allGranted) {
            setupBluetooth()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        bleClient = BleMouseClient(this, onReady = {
            runOnUiThread { isReady = true }
        }, onConnectionChange = { defaultName ->
            runOnUiThread { connectedDeviceName = defaultName }
        })

        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!permissionGranted) {
                        EmptyState("Bluetooth Permissions Required")
                    } else if (!isReady) {
                        DeviceSelectionScreen(bleClient, connectedDeviceName)
                    } else {
                        TouchpadArea(bleClient, connectedDeviceName, this)
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val needed = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        } else {
            permissionGranted = true
            setupBluetooth()
        }
    }

    private fun setupBluetooth() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bm.adapter?.isEnabled == false) {
            // Wait or prompt user to enable Bluetooth
            return
        } 
    }
}

@Composable
fun EmptyState(message: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Text(text = message, textAlign = TextAlign.Center)
    }
}

@Composable
fun DeviceSelectionScreen(bleClient: BleMouseClient, status: String?) {
    val devices = bleClient.getPairedDevices()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Paired Devices", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        devices.forEach { device ->
            @SuppressLint("MissingPermission")
            val name = device.name ?: "Unknown Device"
            Button(
                onClick = { bleClient.connectToSpecificDevice(device) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("$name (${device.address})")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        if (status != null) {
            Text(text = "Status: $status", color = Color.Yellow)
        }
    }
}

@Composable
fun TouchpadArea(hidService: BleMouseClient, connectedDeviceName: String?, context: Context) {
    
    val connectionText = if (connectedDeviceName != null) {
        "Connected to $connectedDeviceName\nReady to use"
    } else {
        "Ready. Ensure PhonePadService is running."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = connectionText,
                color = if (connectedDeviceName != null) Color.Green else Color.LightGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Row {
                Button(onClick = { hidService.disconnect() }) {
                    Text("Disconnect")
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { hidService.sendLeftUp() },
                        onDragCancel = { hidService.sendLeftUp() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val sensitivity = 1.3f
                            hidService.sendMouseMove(
                                (dragAmount.x * sensitivity).toInt(),
                                (dragAmount.y * sensitivity).toInt()
                            )
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { hidService.sendClick(left = true) },
                        onDoubleTap = { },
                        onLongPress = { hidService.sendClick(right = true) },
                        onPress = { }
                    )
                }
        ) {
            Text(
                text = "Drag to Move\nTap to Left-Click\nLong Press to Right-Click",
                color = Color.DarkGray,
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
