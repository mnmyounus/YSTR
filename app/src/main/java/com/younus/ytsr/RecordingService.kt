package com.younus.ytsr

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecordingService
 * ────────────────
 * Foreground Service (type: mediaProjection) that owns the full recording lifecycle:
 *
 *   ACTION_START_RECORDING  → receives MediaProjection token from
 *                             MediaProjectionRequestActivity, sets up VirtualDisplay
 *                             + MediaRecorder, begins MP4 capture (H.264/AAC).
 *   ACTION_STOP_RECORDING   → flushes and saves the file, releases all resources.
 *
 * Output:  API 29+  → app-scoped external storage  (no WRITE_EXTERNAL_STORAGE needed)
 *          API ≤ 28 → Movies/YTSR/ on shared storage (needs WRITE_EXTERNAL_STORAGE)
 *
 * Developer: YOUNUS
 */
class RecordingService : Service() {

    companion object {
        private const val TAG          = "RecordingService"
        private const val NOTIF_ID     = 7001
        private const val CHANNEL_ID   = "ytsr_rec"
        private const val CHANNEL_NAME = "YTSR Screen Recording"

        const val ACTION_START_RECORDING = "com.younus.ytsr.START"
        const val ACTION_STOP_RECORDING  = "com.younus.ytsr.STOP"
        const val EXTRA_RESULT_CODE      = "result_code"
        const val EXTRA_RESULT_DATA      = "result_data"

        /** Read by AccessibilityService and MainActivity to guard against duplicate ops. */
        @Volatile var isRecording = false
            private set
    }

    private var projection:     MediaProjection? = null
    private var recorder:       MediaRecorder?   = null
    private var virtualDisplay: VirtualDisplay?  = null
    private var outputFile:     File?            = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "Projection stopped externally — halting")
            stopRecording()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (code == Activity.RESULT_OK && data != null) {
                    startForeground(NOTIF_ID, buildNotification(recording = false))
                    startRecording(code, data)
                } else {
                    Log.e(TAG, "Invalid projection token (code=$code) — stopping")
                    stopSelf()
                }
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // NOT_STICKY: never auto-restart — spec requires NO auto-recording
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()   // safety net
        super.onDestroy()
    }

    // ── Start recording ───────────────────────────────────────────────────

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(resultCode, data)?.also {
            it.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        } ?: run { Log.e(TAG, "getMediaProjection returned null"); stopSelf(); return }

        val m   = displayMetrics()
        val w   = m.widthPixels
        val h   = m.heightPixels
        val dpi = m.densityDpi

        outputFile = timestampedFile()

        try {
            setupRecorder(w, h)
            virtualDisplay = projection!!.createVirtualDisplay(
                "YTSR", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder!!.surface, null, null
            )
            recorder!!.start()
            isRecording = true
            Log.i(TAG, "▶ Recording → ${outputFile!!.absolutePath}")
            notifyMgr().notify(NOTIF_ID, buildNotification(recording = true))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            release()
            stopSelf()
        }
    }

    // ── Stop recording ─────────────────────────────────────────────────────

    private fun stopRecording() {
        if (!isRecording) return
        try {
            recorder?.stop()
            recorder?.release()  // CRITICAL: must release() to finalize the MP4 file
            Log.i(TAG, "■ Saved → ${outputFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed — discarding file", e)
            outputFile?.delete()
        } finally {
            release()
        }
    }

    // ── MediaRecorder setup ────────────────────────────────────────────────

    /**
     * Configures recorder with:
     *   Video: SURFACE source, H.264, 8 Mbps, 30 fps
     *   Audio: MIC source, AAC, 128 kbps, 44.1 kHz stereo
     * MediaRecorder state-machine order must be followed exactly.
     */
    @Throws(Exception::class)
    private fun setupRecorder(w: Int, h: Int) {
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this)
        else
            @Suppress("DEPRECATION") MediaRecorder()

        recorder!!.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile!!.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(8_000_000)
            setVideoFrameRate(30)
            setVideoSize(w, h)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setAudioChannels(2)
            prepare()   // surfaces become valid only after prepare()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun release() {
        runCatching { virtualDisplay?.release() }
        runCatching { recorder?.reset(); recorder?.release() }
        runCatching { projection?.unregisterCallback(projectionCallback); projection?.stop() }
        virtualDisplay = null; recorder = null; projection = null
        isRecording = false
        notifyMgr().notify(NOTIF_ID, buildNotification(recording = false))
    }

    private fun displayMetrics(): DisplayMetrics = DisplayMetrics().also { m ->
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            m.widthPixels = b.width(); m.heightPixels = b.height()
            m.densityDpi  = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
        }
    }

    private fun timestampedFile(): File {
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            File(getExternalFilesDir(null), "Recordings")
        else
            @Suppress("DEPRECATION")
            File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MOVIES), "YTSR")
        dir.mkdirs()
        return File(dir, "YTSR_$ts.mp4")
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifyMgr().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false); enableVibration(false); setSound(null, null)
                }
            )
        }
    }

    private fun buildNotification(recording: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java)
                .apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (recording) "🔴 YTSR — Recording" else "YTSR — Ready")
            .setContentText(
                if (recording) "Double-press [1] to stop & save"
                else "Double-press [0] to start recording"
            )
            .setSmallIcon(if (recording) R.drawable.ic_record else R.drawable.ic_stop)
            .setContentIntent(openApp)
            .setOngoing(recording)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (recording) addAction(R.drawable.ic_stop, "Stop", stopPi) }
            .build()
    }

    private fun notifyMgr() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
