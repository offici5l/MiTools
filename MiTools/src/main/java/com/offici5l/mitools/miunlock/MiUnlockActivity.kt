package com.offici5l.mitools.miunlock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.offici5l.mitools.MainActivity

class MiUnlockActivity : AppCompatActivity() {
    private var deviceId: String = ""
    private var userId: String = ""

    private val loginResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            handleLoginResult(result.data)
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            finish()
        }
    }

    private val hstResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            handleHSTResult(result.data)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startLoginActivity()
    }

    private fun startLoginActivity() {
        val intent = Intent(this, MiUnlockLoginActivity::class.java)
        loginResultLauncher.launch(intent)
    }

    private fun startHSTActivity(passToken: String, deviceId: String, userId: String) {
        val intent = Intent(this, MiUnlockHSTActivity::class.java).apply {
            putExtra("passToken", passToken)
            putExtra("deviceId", deviceId)
            putExtra("userId", userId)
        }
        hstResultLauncher.launch(intent)
    }

    private fun handleLoginResult(data: Intent?) {
        val passToken = data?.getStringExtra("passToken") ?: ""
        deviceId = data?.getStringExtra("deviceId") ?: ""
        userId = data?.getStringExtra("userId") ?: ""

        val hasAllValues = passToken.isNotEmpty() && deviceId.isNotEmpty() && userId.isNotEmpty()

        if (hasAllValues) {
            startHSTActivity(passToken, deviceId, userId)
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun handleHSTResult(data: Intent?) {
        val host = data?.getStringExtra("host") ?: ""
        val ssecurity = data?.getStringExtra("ssecurity") ?: ""
        val serviceToken = data?.getStringExtra("serviceToken") ?: ""
        val hasAllValues = host.isNotEmpty() && ssecurity.isNotEmpty() && serviceToken.isNotEmpty()

        if (hasAllValues) {
            val intent = Intent(this, MiUnlockDActivity::class.java).apply {
                putExtra("serviceToken", serviceToken)
                putExtra("ssecurity", ssecurity)
                putExtra("host", host)
                putExtra("deviceId", deviceId)
                putExtra("userId", userId)
            }
            startActivity(intent)
        }
        finish()
    }
}