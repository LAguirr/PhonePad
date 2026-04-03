package com.phonepad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

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
        }, onConnectionChange = { name ->
            runOnUiThread { connectedDeviceName = name }
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
                        TouchpadArea(
                            hidService = bleClient,
                            connectedDeviceName = connectedDeviceName,
                            onDisconnect = {
                                bleClient.disconnect()
                                isReady = false
                            }
                        )
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
fun TouchpadArea(
    hidService: BleMouseClient,
    connectedDeviceName: String?,
    onDisconnect: () -> Unit
) {
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
        // Top bar
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
            Button(onClick = onDisconnect) {
                Text("Disconnect")
            }
        }

        // Touchpad surface — handles all gestures
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                // ── Gesture 1: single-finger drag → mouse move
                //    Gesture 2: double-tap-and-hold → pick/drag
                .pointerInput(Unit) {
                    val doubleTapTimeoutMs = 300L

                    awaitEachGesture {
                        // ── Phase 1: first finger down
                        val firstDown = awaitFirstDown(requireUnconsumed = false)

                        var isDragging = false

                        // ── Phase 2: track first finger until it lifts (or drag starts)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == firstDown.id }
                                ?: break

                            if (pointer.changedToUp()) {
                                pointer.consume()
                                break
                            }

                            val drag = pointer.position - pointer.previousPosition
                            if (drag.x != 0f || drag.y != 0f) {
                                isDragging = true
                                val sensitivity = 1.3f
                                hidService.sendMouseMove(
                                    (drag.x * sensitivity).toInt(),
                                    (drag.y * sensitivity).toInt()
                                )
                                pointer.consume()
                            }
                        }

                        if (!isDragging) {
                            // ── Phase 3: first tap lifted — wait for second tap with timeout
                            val secondDown: PointerInputChange? = withTimeoutOrNull(doubleTapTimeoutMs) {
                                var found: PointerInputChange? = null
                                while (found == null) {
                                    val event = awaitPointerEvent()
                                    found = event.changes.firstOrNull { !it.previousPressed && it.pressed }
                                    found?.consume()
                                }
                                found
                            }

                            if (secondDown != null) {
                                // ── Double-tap-and-hold: pick/drag gesture
                                hidService.sendLeftDown()
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pointer = event.changes.firstOrNull { it.id == secondDown.id }
                                            ?: break
                                        if (pointer.changedToUp()) {
                                            pointer.consume()
                                            break
                                        }
                                        val drag = pointer.position - pointer.previousPosition
                                        if (drag.x != 0f || drag.y != 0f) {
                                            val sensitivity = 1.3f
                                            hidService.sendMouseMove(
                                                (drag.x * sensitivity).toInt(),
                                                (drag.y * sensitivity).toInt()
                                            )
                                            pointer.consume()
                                        }
                                    }
                                } finally {
                                    hidService.sendLeftUp()
                                }
                            } else {
                                // ── Single tap → left click
                                hidService.sendClick(left = true)
                            }
                        }
                        // If isDragging, move events were already sent above
                    }
                }
                // ── Gesture 3: two-finger scroll
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // Wait until we see at least 2 fingers
                        awaitFirstDown(requireUnconsumed = false)

                        var scrollAccum = 0f
                        val scrollThreshold = 8f  // pixels per scroll tick

                        while (true) {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.filter { it.pressed }

                            if (pointers.size >= 2) {
                                // Average Y movement of all active fingers
                                val avgDy = pointers.map {
                                    it.position.y - it.previousPosition.y
                                }.average().toFloat()

                                scrollAccum += avgDy
                                // Consume to prevent single-finger handler interfering
                                pointers.forEach { it.consume() }

                                while (scrollAccum > scrollThreshold) {
                                    hidService.sendScroll(3)    // scroll up
                                    scrollAccum -= scrollThreshold
                                }
                                while (scrollAccum < -scrollThreshold) {
                                    hidService.sendScroll(-3)   // scroll down
                                    scrollAccum += scrollThreshold
                                }
                            }

                            if (pointers.isEmpty()) break
                        }
                    }
                }
        ) {
            Text(
                text = "Drag to Move\nTap to Click\nDouble-tap & Hold to Drag\nTwo Fingers to Scroll",
                color = Color.DarkGray,
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
