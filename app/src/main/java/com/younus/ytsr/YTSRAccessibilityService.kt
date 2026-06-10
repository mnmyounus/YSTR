package com.younus.ytsr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * YTSRAccessibilityService
 * ────────────────────────
 * Intercepts Android TV remote KeyEvents globally via FLAG_REQUEST_FILTER_KEY_EVENTS.
 * Works even when Peo TV (or any other app) is in the foreground.
 *
 * DOUBLE-PRESS LOGIC (500 ms window):
 *   [0][0] → START recording  (launches MediaProjectionRequestActivity)
 *   [1][1] → STOP  recording  (sends Intent to RecordingService)
 *
 * Single presses are always passed through to the foreground app (return false).
 * Only confirmed double-presses are consumed (return true).
 *
 * Developer: YOUNUS
 */
class YTSRAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "YTSRAccessibility"
        private const val DOUBLE_PRESS_MS = 500L   // max interval to qualify as double-press

        /** Read by MainActivity to display service status without binding. */
        @Volatile var isServiceRunning = false
            private set
    }

    /** Timestamps of the most recent ACTION_DOWN for each watched key. */
    private var lastKey0Ms = 0L
    private var lastKey1Ms = 0L

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Ensure the key-filter flag is set at runtime in addition to the XML config.
        // Both layers are required for reliable interception across TV firmware variants.
        val info = serviceInfo ?: AccessibilityServiceInfo().also {
            it.eventTypes  = AccessibilityEvent.TYPES_ALL_MASK
            it.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        isServiceRunning = true
        Log.i(TAG, "Service connected — key filtering ACTIVE")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        isServiceRunning = false
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    /** Not needed for key-event-only operation; required by abstract class. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    // ── Core key interception ─────────────────────────────────────────────

    /**
     * Called for every routed KeyEvent.
     * Returns TRUE  → event CONSUMED  (not delivered to the foreground app).
     * Returns FALSE → event PASSED THROUGH to the foreground app normally.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only react to the initial press; ignore key-repeat and release events.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false

        val now = System.currentTimeMillis()

        return when (event.keyCode) {

            KeyEvent.KEYCODE_0 -> {
                val delta = now - lastKey0Ms
                if (delta in 1..DOUBLE_PRESS_MS) {
                    // ✅ Double-press confirmed
                    lastKey0Ms = 0L                           // reset to prevent triple-fire
                    Log.i(TAG, "⚡ [0][0] double-press (Δ${delta}ms) → START recording")
                    triggerStart()
                    true                                      // consume — do NOT send to Peo TV
                } else {
                    lastKey0Ms = now                          // first press — record timestamp
                    false                                     // pass through
                }
            }

            KeyEvent.KEYCODE_1 -> {
                val delta = now - lastKey1Ms
                if (delta in 1..DOUBLE_PRESS_MS) {
                    lastKey1Ms = 0L
                    Log.i(TAG, "⚡ [1][1] double-press (Δ${delta}ms) → STOP recording")
                    triggerStop()
                    true
                } else {
                    lastKey1Ms = now
                    false
                }
            }

            else -> false   // never intercept any other key
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────

    /**
     * Launches the transparent MediaProjectionRequestActivity which shows
     * the system screen-capture consent dialog, then hands the token to
     * RecordingService. FLAG_ACTIVITY_NEW_TASK is mandatory from a Service context.
     */
    private fun triggerStart() {
        if (RecordingService.isRecording) {
            Log.w(TAG, "Already recording — ignoring start request")
            return
        }
        try {
            startActivity(
                Intent(this, MediaProjectionRequestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                             Intent.FLAG_ACTIVITY_SINGLE_TOP or
                             Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MediaProjectionRequestActivity", e)
        }
    }

    /** Sends a stop command directly to the running RecordingService. */
    private fun triggerStop() {
        if (!RecordingService.isRecording) {
            Log.w(TAG, "Not recording — ignoring stop request")
            return
        }
        try {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP_RECORDING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop RecordingService", e)
        }
    }
}
