package com.offici5l.mitools.miunlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.app.PendingIntent
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object MiUnlockUsbManager {

    private const val ACTION_USB_PERMISSION = "com.offici5l.mitools.USB_PERMISSION"
    private const val CHECK_DEVICE_DELAY_MS = 2000L
    private const val PERMISSION_RETRY_DELAY_MS = 2000L
    private const val PERMISSION_TIMEOUT_MS = 5000L
    private var usbPermissionDeferred: CompletableDeferred<Boolean>? = null
    private var usbReceiver: BroadcastReceiver? = null

    suspend fun getFastbootDevice(context: Context): UsbDevice? = withContext(Dispatchers.IO) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        ensureDeviceAndPermission(context, usbManager)
    }

    private suspend fun ensureDeviceAndPermission(context: Context, usbManager: UsbManager): UsbDevice? {
        while (true) {
            val device = findFastbootDevice(usbManager)
            if (device == null) {
                delay(CHECK_DEVICE_DELAY_MS)
                continue
            }

            if (usbManager.hasPermission(device)) {
                return device
            }

            while (!usbManager.hasPermission(device)) {
                try {
                    val intent = Intent(ACTION_USB_PERMISSION).apply {
                        putExtra(UsbManager.EXTRA_DEVICE, device)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    val deferred = registerUsbReceiver(context)
                    usbManager.requestPermission(device, pendingIntent)
                    
                    val granted = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() }
                    
                    if (granted == true) {
                        return device
                    } else {
                        delay(PERMISSION_RETRY_DELAY_MS)
                    }
                } catch (e: Exception) {
                    delay(PERMISSION_RETRY_DELAY_MS)
                } finally {
                    unregisterUsbReceiver(context)
                }
            }
        }
    }

    private fun registerUsbReceiver(context: Context): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        usbPermissionDeferred = deferred
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (permissionGranted) {
                        deferred.complete(true)
                    } else {
                        deferred.complete(false)
                    }
                    context.unregisterReceiver(this)
                    usbReceiver = null
                    usbPermissionDeferred = null
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        
        return deferred
    }

    private fun unregisterUsbReceiver(context: Context) {
        usbReceiver?.let {
            try {
                context.unregisterReceiver(it)
                usbReceiver = null
                usbPermissionDeferred?.complete(false)
                usbPermissionDeferred = null
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    private fun findFastbootDevice(usbManager: UsbManager): UsbDevice? {
        val deviceList = usbManager.deviceList
        return deviceList.values.find { device ->
            device.vendorId == 0x18D1 ||
            device.vendorId == 0x2717 ||
            device.vendorId == 0x0BB4 ||
            device.vendorId == 0x0E8D
        }
    }
}
