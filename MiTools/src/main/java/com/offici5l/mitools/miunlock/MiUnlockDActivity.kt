package com.offici5l.mitools.miunlock

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.offici5l.mitools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Suppress("DEPRECATION")
private fun Intent.getUsbDeviceExtra(): UsbDevice? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}

class MiUnlockDActivity : AppCompatActivity() {

    private lateinit var serviceToken: String
    private lateinit var ssecurity: String
    private lateinit var host: String
    private lateinit var deviceId: String
    private lateinit var userId: String
    private lateinit var noticeTextView: TextView
    private lateinit var continueButton: Button
    private var nonce: String? = null
    private var product: String? = null
    private var deviceToken: String? = null
    private lateinit var pcId: String

    companion object {
        private const val ACTION_USB_PERMISSION = "com.offici5l.mitools.USB_PERMISSION"
    }

    private val usbDeviceAttachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                intent.getUsbDeviceExtra()?.let { device ->
                    val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                    if (usbManager.hasPermission(device)) {
                        startUnlockProcess(device)
                    } else {
                        requestUsbPermission(device)
                    }
                }
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    intent.getUsbDeviceExtra()?.let {
                        startUnlockProcess(it)
                    }
                } else {
                    noticeTextView.text = "USB permission was denied. Please reconnect the device and grant permission."
                }
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION)
        intent.setPackage(packageName)
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            intent,
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.miunlock_d)

        noticeTextView = findViewById(R.id.noticeTextView)
        continueButton = findViewById(R.id.continueButton)
        continueButton.visibility = View.GONE

        serviceToken = intent.getStringExtra("serviceToken") ?: ""
        ssecurity = intent.getStringExtra("ssecurity") ?: ""
        host = intent.getStringExtra("host") ?: ""
        deviceId = intent.getStringExtra("deviceId") ?: ""

        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(deviceId.toByteArray())
        pcId = digest.joinToString("") { "%02x".format(it) }

        userId = intent.getStringExtra("userId") ?: ""

        if (serviceToken.isNotEmpty() && ssecurity.isNotEmpty() && host.isNotEmpty()) {
            registerReceivers()
            noticeTextView.text = "Power off your phone and press the Volume Down + Power button to enter Bootloader and connect the phone using USB cable."
            checkForConnectedDevices()
        } else {
            finish()
        }
    }

    private fun registerReceivers() {
        val attachedFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDeviceAttachedReceiver, attachedFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(usbPermissionReceiver, permissionFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbDeviceAttachedReceiver, attachedFilter)
            registerReceiver(usbPermissionReceiver, permissionFilter)
        }
    }

    private fun checkForConnectedDevices() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        
        for (device in deviceList.values) {
            if (usbManager.hasPermission(device)) {
                startUnlockProcess(device)
                break
            } else {
                requestUsbPermission(device)
                break 
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbDeviceAttachedReceiver)
            unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }
    }

    private fun startUnlockProcess(device: UsbDevice) {
        CoroutineScope(Dispatchers.Main).launch {
            noticeTextView.text = "USB permission granted. Retrieving device info..."

            product = MiUnlockFastboot.getProduct(this@MiUnlockDActivity)?.replace("\\s".toRegex(), "")
            if (product.isNullOrEmpty()) {
                noticeTextView.text = "Failed to retrieve product. Please ensure the device is in Fastboot mode and try again."
                return@launch
            }
            noticeTextView.text = "\nPhone connected\nproduct: $product"
            delay(2000)

            deviceToken = MiUnlockFastboot.getDeviceToken(this@MiUnlockDActivity)?.replace("\\s".toRegex(), "")
            if (deviceToken.isNullOrEmpty()) {
                noticeTextView.text = "Failed to retrieve deviceToken."
                return@launch
            }
            noticeTextView.text = "\ndeviceToken: $deviceToken"
            delay(2000)

            processUnlockSteps()
        }
    }

    private fun processUnlockSteps() {
        val randomString = (1..16).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
        val nrParams = mapOf("r" to randomString, "sid" to "miui_unlocktool_client")

        CoroutineScope(Dispatchers.Main).launch {
            val nr = withContext(Dispatchers.IO) {
                send(host, "/api/v2/nonce", listOf("r", "sid"), nrParams, ssecurity, serviceToken)
            }

            nonce = nr.optString("nonce")
            if (nonce.isNullOrEmpty()) {
                noticeTextView.text = "Failed to retrieve nonce: $nr"
                return@launch
            }

            val crParams = mapOf(
                "data" to JSONObject().put("product", product).toString(),
                "nonce" to nonce!!,
                "sid" to "miui_unlocktool_client"
            )
            val cr = withContext(Dispatchers.IO) {
                send(host, "/api/v2/unlock/device/clear", listOf("data", "nonce", "sid"), crParams, ssecurity, serviceToken)
            }

            val notice = cr.optString("notice", "No notice available")
            val cleanOrNot = cr.optInt("cleanOrNot", -1)
            noticeTextView.text = buildString {
                append(notice)
                append("\n")
                append(if (cleanOrNot == 1) {
                    "This device clears user data when it is unlocked"
                } else {
                    "Unlocking the device does not clear user data"
                })
            }

            continueButton.visibility = View.VISIBLE

            continueButton.setOnClickListener {
                continueButton.visibility = View.GONE
                performUnlock()
            }
        }
    }

    private fun performUnlock() {
        val arParams = mapOf(
            "appId" to "1",
            "data" to JSONObject().apply {
                put("clientId", "2")
                put("clientVersion", "7.6.727.43")
                put("deviceInfo", JSONObject().apply {
                    put("boardVersion", "")
                    put("deviceName", "")
                    put("product", product)
                    put("socId", "")
                })
                put("deviceToken", deviceToken)
                put("language", "en")
                put("operate", "unlock")
                put("pcId", pcId)
                put("region", "")
                put("uid", userId)
            }.toString(),
            "nonce" to nonce!!,
            "sid" to "miui_unlocktool_client"
        )

        CoroutineScope(Dispatchers.Main).launch {
            val ar = withContext(Dispatchers.IO) {
                send(host, "/api/v3/ahaUnlock", listOf("appId", "data", "nonce", "sid"), arParams, ssecurity, serviceToken)
            }

            if (ar.optInt("code") != 0) {
                val message = if (ar.has("descEN")) ar.optString("descEN") else ar.optString("description", "Unknown error")
                noticeTextView.text = message
            } else {
                val encryptData = ar.optString("encryptData", "")
                if (encryptData.isNotEmpty()) {
                    try {
                        val bytes = Base64.getDecoder().decode(encryptData)
                        val file = File(filesDir, "encryptData")
                        FileOutputStream(file).use { it.write(bytes) }

                        val stageSuccess = MiUnlockFastboot.stageEncryptData(this@MiUnlockDActivity, file)
                        if (stageSuccess) {
                            val unlockSuccess = MiUnlockFastboot.oemUnlock(this@MiUnlockDActivity)
                            noticeTextView.text = if (unlockSuccess) {
                                "unlocked successfully"
                            } else {
                                "Failed to unlock"
                            }
                        } else {
                            noticeTextView.text = "Failed to stage encryptData"
                        }
                    } catch (e: Exception) {
                        noticeTextView.text = "Error saving unlock data: ${e.message}"
                    }
                } else {
                    noticeTextView.text = "Unknown error"
                }
            }
        }
    }

    private suspend fun send(
        host: String,
        path: String,
        paramOrder: List<String>,
        paramsRaw: Map<String, String>,
        ssecurity: String,
        serviceToken: String
    ): JSONObject {
        return try {
            val key = Base64.getDecoder().decode(ssecurity)
            val iv = "0102030405060708".toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)

            val params = paramsRaw.toMutableMap()
            if (params.containsKey("data")) {
                val dataJson = params["data"]!!
                params["data"] = Base64.getEncoder().encodeToString(dataJson.toByteArray(Charsets.UTF_8))
            }

            if (!params.containsKey("sid")) {
                params["sid"] = "miui_unlocktool_client"
            }

            val ep: (String) -> String = { input ->
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
                Base64.getEncoder().encodeToString(cipher.doFinal(input.toByteArray(Charsets.UTF_8)))
            }

            val signParams = paramOrder.joinToString("&") { k -> "$k=${params[k]}" }
            val signStr = "POST\n$path\n$signParams"

            val hmacKey = "2tBeoEyJTunmWUGq7bQH2Abn0k2NhhurOaqBfyxCuLVgn4AVj7swcawe53uDUno".toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(hmacKey, "HmacSHA1") )
            val hmacDigest = mac.doFinal(signStr.toByteArray(Charsets.UTF_8))
            val hexHmac = ByteString.of(*hmacDigest).hex()
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            val currentSign = Base64.getEncoder().encodeToString(cipher.doFinal(hexHmac.toByteArray(Charsets.UTF_8)))

            val encodedParams = paramOrder.map { k -> "$k=${ep(params[k]!!)}" }
            val sha1Input = "POST&${path}&${encodedParams.joinToString("&")}&sign=$currentSign&$ssecurity"
            val sha1 = MessageDigest.getInstance("SHA1")
            val signature = Base64.getEncoder().encodeToString(sha1.digest(sha1Input.toByteArray(Charsets.UTF_8)))

            val formBody = FormBody.Builder()
            paramOrder.forEach { k ->
                formBody.add(k, ep(params[k]!!))
            }
            formBody.add("sign", currentSign)
            formBody.add("signature", signature)

            val cookieString = serviceToken.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains("=") }
                .joinToString("; ") { it }

            val request = Request.Builder()
                .url("$host$path")
                .post(formBody.build())
                .header("User-Agent", "com.offici5l.mitools")
                .header("Cookie", cookieString)
                .build()

            val client = OkHttpClient()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response from server")

            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(Base64.getDecoder().decode(responseBody))
            val decryptedString = String(decrypted, Charsets.UTF_8)
            val jsonString = String(Base64.getDecoder().decode(decryptedString), Charsets.UTF_8)
            JSONObject(jsonString)
        } catch (e: Exception) {
            JSONObject().put("error", "Request failed: ${e.javaClass.simpleName} - ${e.message}")
        }
    }
}