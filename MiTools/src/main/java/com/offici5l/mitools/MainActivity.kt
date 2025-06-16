package com.offici5l.mitools

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.offici5l.mitools.miunlock.MiUnlockActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val miUnlockButton: Button = findViewById(R.id.miUnlockButton)
        val miAssistantButton: Button = findViewById(R.id.miAssistantButton)
        val miFlashButton: Button = findViewById(R.id.miFlashButton)
        val websiteLink: TextView = findViewById(R.id.websiteLink)
        val versionText: TextView = findViewById(R.id.versionText)

        val versionName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (e: PackageManager.NameNotFoundException) {
            "Unknown"
        }
        versionText.text = "Version $versionName"

        
        miUnlockButton.setOnClickListener {
            startActivity(Intent(this, MiUnlockActivity::class.java))
        }

        miAssistantButton.setOnClickListener {
            Toast.makeText(this, "Mi Assistant: Coming Soon", Toast.LENGTH_SHORT).show()
        }

        miFlashButton.setOnClickListener {
            Toast.makeText(this, "Mi Flash: Coming Soon", Toast.LENGTH_SHORT).show()
        }

        val websiteText = getString(R.string.website)
        val spannableString = SpannableString(websiteText)
        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://offici5l.github.io"))
                startActivity(intent)
            }
        }
        spannableString.setSpan(clickableSpan, 0, websiteText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        websiteLink.text = spannableString
        websiteLink.movementMethod = LinkMovementMethod.getInstance()
    }
}