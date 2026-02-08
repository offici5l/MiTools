package com.offici5l.mitools.miunlock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.offici5l.mitools.R

class MiUnlockHSTActivity : AppCompatActivity() {

    private lateinit var passToken: String
    private lateinit var deviceId: String
    private lateinit var userId: String
    private lateinit var noticeTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.miunlock_hst)

        noticeTextView = findViewById(R.id.noticeTextView)
        noticeTextView.text = "Please wait ..."

        passToken = intent.getStringExtra("passToken") ?: return finishWithError()
        deviceId = intent.getStringExtra("deviceId") ?: return finishWithError()
        userId = intent.getStringExtra("userId") ?: return finishWithError()


        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cookies = mapOf("passToken" to passToken, "deviceId" to deviceId, "userId" to userId)
                val client = OkHttpClient.Builder()
                    .cookieJar(MemoryCookieJar(cookies))
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val region = getRegion(client)
                
                runOnUiThread {
                    noticeTextView.text = "Account region: $region"
                }

                val regionConfig = getRegionConfig(client, region)
                val host = getHost(regionConfig)
                val (ssecurity, serviceToken) = getSsecurityAndServiceToken(client)

                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("host", host)
                    putExtra("ssecurity", ssecurity)
                    putExtra("serviceToken", serviceToken)
                })
            } catch (e: Exception) {
                setResult(Activity.RESULT_CANCELED)
            }
            finish()
        }
    }

    private fun finishWithError() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun getRegion(client: OkHttpClient): String {
        val request = Request.Builder()
            .url("https://account.xiaomi.com/pass/user/login/region")
            .header("User-Agent", "com.offici5l.mitools")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
            val body = response.body?.string()?.drop(11) ?: throw IOException("Empty response")
            return JSONObject(body).getJSONObject("data").getString("region")
        }
    }

    private fun getRegionConfig(client: OkHttpClient, region: String): String {
        val request = Request.Builder()
            .url("https://account.xiaomi.com/pass2/config?key=regionConfig")
            .header("User-Agent", "com.offici5l.mitools")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
            val body = response.body?.string()?.drop(11) ?: throw IOException("Empty response")
            val regionConfigs = JSONObject(body).getJSONObject("regionConfig")
            for (key in regionConfigs.keys()) {
                val config = regionConfigs.getJSONObject(key)
                if (config.has("region.codes")) {
                    val regionCodes = config.getJSONArray("region.codes")
                    for (i in 0 until regionCodes.length()) {
                        if (region == regionCodes.getString(i)) return key
                    }
                }
            }
            throw IOException("Region config not found for region: $region")
        }
    }

    private fun getHost(regionConfig: String): String {
        val subdomains = mapOf(
            "Singapore" to "unlock.update.intl",
            "China" to "unlock.update",
            "India" to "in-unlock.update.intl",
            "Russia" to "ru-unlock.update.intl",
            "Europe" to "eu-unlock.update.intl"
        )
        return "https://${subdomains[regionConfig] ?: throw IOException("Unknown region config: $regionConfig")}.miui.com"
    }

    private fun getSsecurityAndServiceToken(client: OkHttpClient): Pair<String, String> {
        val request = Request.Builder()
            .url("https://account.xiaomi.com/pass/serviceLogin?sid=unlockApi")
            .header("User-Agent", "com.offici5l.mitools")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
            val ssecurity = (response.priorResponse ?: response).header("extension-pragma")?.let {
                JSONObject(it).getString("ssecurity")
            } ?: throw IOException("ssecurity not found")
            val serviceToken = response.headers("Set-Cookie")
                .filter { !it.substringBefore(";").endsWith("=null") }
                .joinToString(";") { it.substringBefore(";") }
            return ssecurity to serviceToken
        }
    }

    class MemoryCookieJar(private val initialCookies: Map<String, String>) : CookieJar {
        private val cookieStore = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val initial = initialCookies.map { (name, value) ->
                Cookie.Builder().name(name).value(value).domain(url.host).build()
            }
            return initial + (cookieStore[url.host] ?: emptyList())
        }
    }
}