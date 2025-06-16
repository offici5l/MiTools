package com.offici5l.mitools.miunlock

import android.app.Activity
import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.offici5l.mitools.MainActivity
import com.offici5l.mitools.R
import android.widget.Button
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast

class MiUnlockLoginActivity : AppCompatActivity() {

    private val initialUrl = "https://account.xiaomi.com/pass/serviceLogin?sid=unlockApi&checkSafeAddress=true"
    private val endPattern = "{\"R\":\"\",\"S\":\"OK\"}"
    private var monitoringEnded = false
    private var passToken: String? = null
    private var deviceId: String? = null
    private var userId: String? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.miunlock_login)

        val webView = findViewById<WebView>(R.id.webView)
        val continueButton = findViewById<Button>(R.id.continueButton)
        val logoutButton = findViewById<Button>(R.id.logoutButton)
        val loginMessage = findViewById<TextView>(R.id.loginMessage)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        onBackPressedDispatcher.addCallback(this) {
            finish()
        }

        if (checkExistingCookies()) {
            webView.visibility = View.GONE
            progressBar.visibility = View.GONE
            continueButton.visibility = View.VISIBLE
            logoutButton.visibility = View.VISIBLE
            loginMessage.visibility = View.VISIBLE
            loginMessage.text = "Already logged in as Account ID: $userId"
        } else {
            webView.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            continueButton.visibility = View.GONE
            logoutButton.visibility = View.GONE
            loginMessage.visibility = View.GONE
            setupWebView(webView, progressBar)
        }

        continueButton.setOnClickListener {
            returnResults()
        }

        logoutButton.setOnClickListener {
            CookieManager.getInstance().removeAllCookies {
                Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
                passToken = null
                deviceId = null
                userId = null
                monitoringEnded = false
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            CookieManager.getInstance().flush()
        }
    }

    private fun checkExistingCookies(): Boolean {
        val cookieString = CookieManager.getInstance().getCookie("https://account.xiaomi.com")
        if (cookieString != null) {
            fun extractValue(key: String) = cookieString.split(";").map { it.trim() }
                .find { it.startsWith("$key=") }?.split("=")?.getOrNull(1)
            
            passToken = extractValue("passToken")
            deviceId = extractValue("deviceId")
            userId = extractValue("userId")
            
            return userId?.isNotEmpty() == true && 
                   passToken?.isNotEmpty() == true && 
                   deviceId?.isNotEmpty() == true
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView, progressBar: ProgressBar) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "(Android) Mobile"
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!monitoringEnded) {
                    extractCookies()
                }
                return false
            }
            
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                handler.removeCallbacksAndMessages(null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!monitoringEnded) {
                    progressBar.visibility = View.GONE
                    view.evaluateJavascript(
                        "var style = document.createElement('style'); style.innerHTML = '* { transition: none !important; animation: none !important; }'; document.head.appendChild(style);",
                        null
                    )
                    extractCookies()
                    checkForEndSignal(view)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                progressBar.visibility = View.GONE
                monitoringEnded = true
                Toast.makeText(this@MiUnlockLoginActivity, "Error loading page", Toast.LENGTH_SHORT).show()
                val intent = Intent(this@MiUnlockLoginActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                setResult(Activity.RESULT_CANCELED)
                startActivity(intent)
                finish()
            }
        }
        
        CookieManager.getInstance().run {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        
        webView.loadUrl(initialUrl)
        handler.postDelayed({
            if (progressBar.visibility == View.VISIBLE) {
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Page load timed out", Toast.LENGTH_SHORT).show()
            }
        }, 5000)
    }

    private fun extractCookies() {
        CookieManager.getInstance().getCookie("https://account.xiaomi.com")?.let { cookieString ->
            fun extractValue(key: String) = cookieString.split(";").map { it.trim() }
                .find { it.startsWith("$key=") }?.split("=")?.getOrNull(1)
            
            passToken = passToken ?: extractValue("passToken")
            deviceId = deviceId ?: extractValue("deviceId")
            userId = userId ?: extractValue("userId")
        }
    }

    private fun checkForEndSignal(view: WebView) {
        view.evaluateJavascript("document.documentElement.outerHTML") { html ->
            val cleanedHtml = html.replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            
            if (cleanedHtml.contains(endPattern)) {
                if (passToken?.isNotEmpty() != true || deviceId?.isNotEmpty() != true || userId?.isNotEmpty() != true) {
                    view.loadUrl(initialUrl)
                } else {
                    monitoringEnded = true
                    val loginMessage = findViewById<TextView>(R.id.loginMessage)
                    loginMessage.text = "Logged in successfully to account ID: $userId"
                    loginMessage.visibility = View.VISIBLE
                    handler.postDelayed({
                        returnResults()
                    }, 2000)
                }
            }
        }
    }

    private fun returnResults() {
        val hasAllValues = passToken?.isNotEmpty() == true && deviceId?.isNotEmpty() == true && userId?.isNotEmpty() == true
        
        if (hasAllValues) {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("passToken", passToken)
                putExtra("deviceId", deviceId)
                putExtra("userId", userId)
            })
            finish()
        } else {
            findViewById<WebView>(R.id.webView).loadUrl(initialUrl)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}