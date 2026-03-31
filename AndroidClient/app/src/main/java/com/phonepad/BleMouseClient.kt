package com.phonepad

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BleMouseClient(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onConnectionChange: (String?) -> Unit
) {
    // SPP UUID - works over Classic Bluetooth, same as what our C# server registers
    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    @Volatile private var isConnected = false

    init {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bm?.adapter
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun connectToSpecificDevice(device: BluetoothDevice) {
        onConnectionChange("Connecting to ${device.name}...")
        
        Thread {
            try {
                bluetoothSocket?.close()
                bluetoothSocket = null
                outputStream = null
                isConnected = false

                // Cancel discovery to speed up connection
                bluetoothAdapter?.cancelDiscovery()

                // Create an RFCOMM socket using our custom UUID
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                bluetoothSocket = socket

                socket.connect() // blocking call

                outputStream = socket.outputStream
                isConnected = true

                Log.d("BleMouse", "Connected via RFCOMM to ${device.name}")
                onConnectionChange(device.name ?: "Unknown PC")
                onReady()

            } catch (e: IOException) {
                Log.e("BleMouse", "RFCOMM connect failed: ${e.message}")
                onConnectionChange("Failed: ${e.message?.take(40)}")
                bluetoothSocket?.close()
                bluetoothSocket = null
                isConnected = false
            }
        }.start()
    }

    fun disconnect() {
        isConnected = false
        try {
            bluetoothSocket?.close()
        } catch (_: IOException) {}
        bluetoothSocket = null
        outputStream = null
        onConnectionChange(null)
    }

    private fun sendPayload(payload: ByteArray) {
        if (!isConnected) return
        try {
            outputStream?.write(payload)
        } catch (e: IOException) {
            Log.e("BleMouse", "Send failed: ${e.message}")
            isConnected = false
            onConnectionChange(null)
        }
    }

    fun sendMouseMove(dx: Int, dy: Int) {
        val dxB = dx.coerceIn(-127, 127).toByte()
        val dyB = dy.coerceIn(-127, 127).toByte()
        sendPayload(byteArrayOf(1, dxB, dyB))
    }

    fun sendClick(left: Boolean = false, right: Boolean = false, middle: Boolean = false) {
        if (left) sendPayload(byteArrayOf(2))
        if (right) sendPayload(byteArrayOf(3))
    }

    fun sendLeftDown() = sendPayload(byteArrayOf(4))
    fun sendLeftUp()   = sendPayload(byteArrayOf(5))

    fun sendScroll(dy: Int) {
        val w = dy.coerceIn(-127, 127).toByte()
        sendPayload(byteArrayOf(6, w))
    }
}
