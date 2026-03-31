package com.phonepad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

@SuppressLint("MissingPermission")
class HidMouseService(
    private val context: Context, 
    private val onReady: () -> Unit,
    private val onConnectionChange: (String?) -> Unit
) {
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    var connectedDevice: BluetoothDevice? = null

    // Standard USB HID Report Descriptor for a 3-button mouse with wheel
    private val MOUSE_REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01, // Usage Page (Generic Desktop Ctrls)
        0x09, 0x02, // Usage (Mouse)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x85.toByte(), 0x01, //   Report ID (1)
        0x09, 0x01, //   Usage (Pointer)
        0xA1.toByte(), 0x00, //   Collection (Physical)
        0x05, 0x09, //     Usage Page (Button)
        0x19, 0x01, //     Usage Minimum (0x01)
        0x29, 0x03, //     Usage Maximum (0x03)
        0x15, 0x00, //     Logical Minimum (0)
        0x25, 0x01, //     Logical Maximum (1)
        0x95.toByte(), 0x03, //     Report Count (3)
        0x75, 0x01, //     Report Size (1)
        0x81.toByte(), 0x02, //     Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95.toByte(), 0x01, //     Report Count (1)
        0x75, 0x05, //     Report Size (5)
        0x81.toByte(), 0x03, //     Input (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x05, 0x01, //     Usage Page (Generic Desktop Ctrls)
        0x09, 0x30, //     Usage (X)
        0x09, 0x31, //     Usage (Y)
        0x09, 0x38, //     Usage (Wheel)
        0x15, 0x81.toByte(), //     Logical Minimum (-127)
        0x25, 0x7F, //     Logical Maximum (127)
        0x75, 0x08, //     Report Size (8)
        0x95.toByte(), 0x03, //     Report Count (3)
        0x81.toByte(), 0x06, //     Input (Data,Var,Rel,No Wrap,Linear,Preferred State,No Null Position)
        0xC0.toByte(), //   End Collection
        0xC0.toByte()  // End Collection
    )
    
    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "PhonePad Mouse",
        "PhonePad Platform",
        "Android Bluetooth Mouse Provider",
        BluetoothHidDevice.SUBCLASS1_MOUSE,
        MOUSE_REPORT_DESCRIPTOR
    )
    
    private val hidDeviceCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            if (registered) {
                Log.d("HidMouse", "App registered successfully")
                onReady()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            if (state == BluetoothProfile.STATE_CONNECTED) {
                Log.d("HidMouse", "Connected to ${device?.name}")
                connectedDevice = device
                onConnectionChange(device?.name ?: "Unknown PC")
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("HidMouse", "Disconnected from ${device?.name}")
                connectedDevice = null
                onConnectionChange(null)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                
                // Sync any already-connected devices
                val alreadyConnected = hidDevice?.connectedDevices
                if (!alreadyConnected.isNullOrEmpty()) {
                    val dev = alreadyConnected.first()
                    connectedDevice = dev
                    onConnectionChange(dev.name ?: "Unknown PC")
                }
                
                hidDevice?.registerApp(
                    sdpSettings,
                    null,
                    null,
                    context.mainExecutor,
                    hidDeviceCallback
                )
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice?.unregisterApp()
                hidDevice = null
            }
        }
    }

    fun start() {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bm?.adapter

        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        }
    }
    
    fun connectToPairedDevices() {
        val paired = bluetoothAdapter?.bondedDevices
        paired?.forEach { dev ->
            hidDevice?.connect(dev)
        }
    }
    
    private var buttonsState = 0

    fun sendMouseMove(dx: Int, dy: Int) {
        val dxConstrained = dx.coerceIn(-127, 127).toByte()
        val dyConstrained = dy.coerceIn(-127, 127).toByte()
        sendReport(buttonsState.toByte(), dxConstrained, dyConstrained, 0)
    }
    
    fun sendScroll(dy: Int) {
        val wheelConstrained = dy.coerceIn(-127, 127).toByte()
        sendReport(buttonsState.toByte(), 0, 0, wheelConstrained)
    }

    fun sendClick(left: Boolean = false, right: Boolean = false, middle: Boolean = false) {
        var btn = 0
        if (left) btn = btn or 1
        if (right) btn = btn or 2
        if (middle) btn = btn or 4
        buttonsState = btn
        sendReport(buttonsState.toByte(), 0, 0, 0)
        
        if (buttonsState != 0) {
            buttonsState = 0
            sendReport(0, 0, 0, 0)
        }
    }
    
    fun sendLeftDown() {
        buttonsState = buttonsState or 1
        sendReport(buttonsState.toByte(), 0, 0, 0)
    }
    
    fun sendLeftUp() {
        buttonsState = buttonsState and 1.inv()
        sendReport(buttonsState.toByte(), 0, 0, 0)
    }

    private fun sendReport(buttons: Byte, dx: Byte, dy: Byte, wheel: Byte) {
        val dev = connectedDevice
        val hid = hidDevice
        if (dev != null && hid != null) {
            val report = byteArrayOf(buttons, dx, dy, wheel)
            hid.sendReport(dev, 1, report)
        }
    }
}
