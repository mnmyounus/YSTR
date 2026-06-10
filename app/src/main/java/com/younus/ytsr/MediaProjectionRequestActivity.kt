package com.younus.ytsr

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * MediaProjectionRequestActivity
 * ────────────────────────────────
 * Zero-UI transparent Activity whose only job is to show the Android system
 * screen-capture consent dialog and forward the approved token to RecordingService.
 *
 * WHY A SEPARATE ACTIVITY?
 * The AccessibilityService is not an Activity context, so it cannot call
 * startActivityForResult() directly. This transparent trampoline bridges the gap.
 *
 * It is configured in the Manifest with:
 *   theme=Theme.Translucent.NoTitleBar  → invisible
 *   excludeFromRecents=true             → hidden from recents
 *   noHistory=true                      → removed from back-stack immediately
 *
 * Developer: YOUNUS
 */
class MediaProjectionRequestActivity : Activity() {

    companion object {
        private const val TAG = "MPRequestActivity"
        private const val REQ = 1_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (RecordingService.isRecording) { finish(); return }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Kept for pre-API-31 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            Log.i("MPRequestActivity", "Consent GRANTED — starting RecordingService")
            val svc = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START_RECORDING
                putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        } else {
            Log.w("MPRequestActivity", "Consent DENIED (resultCode=$resultCode)")
        }
        finish()
    }
}
