package com.offici5l.mitools.miunlock

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.min

object MiUnlockFastboot {

    private const val TIMEOUT_MS = 10000
    private const val BUFFER_SIZE = 256

    suspend fun getProduct(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val device = MiUnlockUsbManager.getFastbootDevice(context) ?: return@withContext null
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val output = executeFastbootCommand(usbManager, device, "getvar:product")
            parseFastbootOutput(output)?.takeIf { it.isNotBlank() && !it.contains("FAIL", ignoreCase = true) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getDeviceToken(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val device = MiUnlockUsbManager.getFastbootDevice(context) ?: return@withContext null
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            var token = executeFastbootCommand(usbManager, device, "getvar:token")
            var parsedToken = parseFastbootOutput(token)?.takeIf { it.isNotBlank() && !it.contains("FAIL", ignoreCase = true) }
            if (parsedToken == null) {
                token = executeFastbootCommand(usbManager, device, "oem get_token")
                parsedToken = parseFastbootOutput(token)?.takeIf { it.isNotBlank() && !it.contains("FAIL", ignoreCase = true) }
            }
            parsedToken
        } catch (e: Exception) {
            null
        }
    }

    suspend fun stageEncryptData(context: Context, encryptDataFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!encryptDataFile.exists()) {
                return@withContext false
            }

            val device = MiUnlockUsbManager.getFastbootDevice(context) ?: return@withContext false

            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val fileSize = encryptDataFile.length()

            val connection = usbManager.openDevice(device) ?: return@withContext false

            val (usbInterface, endpointIn, endpointOut) = findUsbEndpoints(device) ?: run {
                connection.close()
                return@withContext false
            }

            connection.claimInterface(usbInterface, true)
            try {
                val command = "download:${String.format("%08x", fileSize.toInt())}"
                val commandBytes = command.toByteArray(StandardCharsets.UTF_8)
                if (connection.bulkTransfer(endpointOut, commandBytes, commandBytes.size, TIMEOUT_MS) < 0) {
                    return@withContext false
                }

                val initialBuffer = ByteBuffer.allocate(BUFFER_SIZE)
                val initialBytesRead = connection.bulkTransfer(endpointIn, initialBuffer.array(), initialBuffer.capacity(), TIMEOUT_MS)
                if (initialBytesRead <= 0) {
                    return@withContext false
                }
                val initialResponse = String(initialBuffer.array(), 0, initialBytesRead, StandardCharsets.UTF_8).trim()

                if (!initialResponse.startsWith("DATA")) {
                    return@withContext false
                }

                val fileBytes = encryptDataFile.readBytes()
                val chunkSize = BUFFER_SIZE
                var offset = 0
                while (offset < fileBytes.size) {
                    val chunk = fileBytes.copyOfRange(offset, min(offset + chunkSize, fileBytes.size))
                    val bytesWritten = connection.bulkTransfer(endpointOut, chunk, chunk.size, TIMEOUT_MS)
                    if (bytesWritten < 0) {
                        return@withContext false
                    }
                    offset += bytesWritten
                }

                val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                val bytesRead = connection.bulkTransfer(endpointIn, buffer.array(), buffer.capacity(), TIMEOUT_MS)
                if (bytesRead > 0) {
                    val response = String(buffer.array(), 0, bytesRead, StandardCharsets.UTF_8).trim()
                    response.isNotEmpty() && response.contains("OKAY", ignoreCase = true) && !response.contains("FAIL", ignoreCase = true)
                } else {
                    false
                }
            } finally {
                connection.releaseInterface(usbInterface)
                connection.close()
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun oemUnlock(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val device = MiUnlockUsbManager.getFastbootDevice(context) ?: return@withContext false
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val output = executeFastbootCommand(usbManager, device, "oem unlock")
            !output.isNullOrEmpty() && !output.contains("FAIL", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun executeFastbootCommand(usbManager: UsbManager, device: UsbDevice, command: String): String? = withContext(Dispatchers.IO) {
        val connection = usbManager.openDevice(device) ?: return@withContext null

        val (usbInterface, endpointIn, endpointOut) = findUsbEndpoints(device) ?: run {
            connection.close()
            return@withContext null
        }

        try {
            connection.claimInterface(usbInterface, true)
            val commandBytes = command.toByteArray(StandardCharsets.UTF_8)
            val bytesWritten = connection.bulkTransfer(endpointOut, commandBytes, commandBytes.size, TIMEOUT_MS)
            if (bytesWritten < 0) {
                return@withContext null
            }

            val responseBuilder = StringBuilder()
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            var lastReadTime = System.currentTimeMillis()

            while (true) {
                val bytesRead = connection.bulkTransfer(endpointIn, buffer.array(), buffer.capacity(), TIMEOUT_MS)
                if (bytesRead > 0) {
                    val receivedData = String(buffer.array(), 0, bytesRead, StandardCharsets.UTF_8)
                    responseBuilder.append(receivedData)
                    lastReadTime = System.currentTimeMillis()

                    val currentResponse = responseBuilder.toString()
                    if (currentResponse.contains("OKAY", ignoreCase = true) || currentResponse.contains("FAIL", ignoreCase = true)) {
                        break
                    }
                } else if (bytesRead == 0) {
                    if (System.currentTimeMillis() - lastReadTime > TIMEOUT_MS) {
                        break
                    }
                } else {
                    return@withContext null
                }
            }
            responseBuilder.toString().trim()
        } catch (e: Exception) {
            null
        } finally {
            connection.releaseInterface(usbInterface)
            connection.close()
        }
    }

    private fun findUsbEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                        inEndpoint = endpoint
                    } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = endpoint
                    }
                }
            }
            if (inEndpoint != null && outEndpoint != null) {
                return Triple(iface, inEndpoint, outEndpoint)
            }
        }
        return null
    }

    private fun parseFastbootOutput(output: String?): String? {
        if (output.isNullOrEmpty()) return null

        val cleanedOutput = output
            .replace(Regex("(?i)\\(bootloader\\)\\s*|token:\\s*|product:\\s*|INFO\\s*|OKAY\\s*|FAIL\\s*"), "")
            .trim()

        return cleanedOutput.takeIf { it.isNotBlank() }
    }
}