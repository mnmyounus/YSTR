package com.younus.ytsr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * MainActivity
 * ─────────────
 * Leanback-compatible TV setup screen for YTSR.
 * Responsibilities:
 *   • Request RECORD_AUDIO (+ legacy storage) permissions
 *   • Deep-link to Accessibility Settings to enable YTSRAccessibilityService
 *   • Poll and display live recording / service status every 1.5 s
 *   • Show usage instructions (visible at 10-foot distance)
 *
 * NOTE: This Activity does NOT control recording.
 *       All recording control flows via double-press keys → AccessibilityService.
 *
 * Developer: YOUNUS
 */
class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG       = "MainActivity"
        private const val REQ_PERMS = 200
        private const val POLL_MS   = 1_500L
    }

    private lateinit var tvVersion:       TextView
    private lateinit var tvPermStatus:    TextView
    private lateinit var tvA11yStatus:    TextView
    private lateinit var tvRecStatus:     TextView
    private lateinit var btnPerms:        Button
    private lateinit var btnA11y:         Button

    private val pollHandler  = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() { refreshStatus(); pollHandler.postDelayed(this, POLL_MS) }
    }

    private val permissions: Array<String> by lazy {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvVersion    = findViewById(R.id.tvAppVersion)
        tvPermStatus = findViewById(R.id.tvPermissionStatus)
        tvA11yStatus = findViewById(R.id.tvAccessibilityStatus)
        tvRecStatus  = findViewById(R.id.tvRecordingStatus)
        btnPerms     = findViewById(R.id.btnRequestPermissions)
        btnA11y      = findViewById(R.id.btnOpenAccessibility)

        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrDefault("1.0.0")
        tvVersion.text = "v$ver  |  YOUNUS"

        btnPerms.setOnClickListener { requestMissingPermissions() }
        btnA11y.setOnClickListener  { openA11ySettings() }
    }

    override fun onResume() {
        super.onResume()
        requestMissingPermissions()
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    // ── Permissions ────────────────────────────────────────────────────────

    private fun requestMissingPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            tvPermStatus.text    = "✅  All permissions granted"
            btnPerms.isEnabled   = false
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val ok = grantResults.isNotEmpty() && grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }
            tvPermStatus.text  = if (ok) "✅  All permissions granted"
                                  else "⚠️  Permissions missing — tap to retry"
            btnPerms.isEnabled = !ok
        }
    }

    // ── Status polling ─────────────────────────────────────────────────────

    private fun refreshStatus() {
        // Accessibility service
        if (YTSRAccessibilityService.isServiceRunning) {
            tvA11yStatus.text  = "✅  Accessibility Service: ACTIVE"
            btnA11y.isEnabled  = false
        } else {
            tvA11yStatus.text  = "❌  Disabled\n    Tap below → Settings → YTSR Key Interceptor → ON"
            btnA11y.isEnabled  = true
        }
        // Recording
        tvRecStatus.text = if (RecordingService.isRecording)
            "🔴  RECORDING IN PROGRESS\n    Double-press  [1]  to STOP & SAVE"
        else
            "⏸  Idle — Double-press  [0]  to START recording"
    }

    // ── Accessibility deep-link ────────────────────────────────────────────

    private fun openA11ySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open accessibility settings", e)
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {
                tvA11yStatus.text =
                    "⚠️  Open Settings manually:\n    Settings → Accessibility → YTSR Key Interceptor"
            }
        }
    }
}
