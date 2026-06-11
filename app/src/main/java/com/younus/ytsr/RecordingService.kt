package com.younus.ytsr

import android.app.*
import android.content.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
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
 * Foreground Service (foregroundServiceType="mediaProjection").
 *
 * STORAGE STRATEGY
 * ─────────────────
 * API 29+ (Android 10+, all modern Android TV):
 *   → MediaStore API → saves directly to public Movies/YTSR/
 *   → Visible in every file manager immediately, no WRITE_EXTERNAL_STORAGE needed.
 *   → Uses ParcelFileDescriptor so MediaRecorder writes directly into MediaStore.
 *
 * API < 29 (older devices):
 *   → Environment.DIRECTORY_MOVIES / YTSR  (public Movies folder)
 *   → Requires WRITE_EXTERNAL_STORAGE (declared in manifest with maxSdkVersion=28).
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

        @Volatile var isRecording = false
            private set
    }

    // ── State ─────────────────────────────────────────────────────────────

    private var projection:     MediaProjection?       = null
    private var recorder:       MediaRecorder?         = null
    private var virtualDisplay: VirtualDisplay?        = null

    // API 29+ — MediaStore entry + open file descriptor
    private var mediaStoreUri:  android.net.Uri?       = null
    private var mediaStorePfd:  ParcelFileDescriptor?  = null
    private var displayName:    String                 = ""

    // API < 29 — direct File path
    private var legacyFile:     File?                  = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "Projection stopped externally — halting")
            stopRecording()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (code == Activity.RESULT_OK && data != null) {
                    startForeground(NOTIF_ID, buildNotification(recording = false))
                    startRecording(code, data)
                } else {
                    Log.e(TAG, "Invalid MediaProjection token (code=$code)")
                    stopSelf()
                }
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY   // never auto-restart — no auto-recording by design
    }

    override fun onDestroy() {
        stopRecording()           // safety net
        super.onDestroy()
    }

    // ── Start ─────────────────────────────────────────────────────────────

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return

        // 1. Obtain MediaProjection token
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(resultCode, data)?.also {
            it.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        } ?: run {
            Log.e(TAG, "getMediaProjection returned null — aborting")
            stopSelf(); return
        }

        // 2. Prepare output (MediaStore or legacy file)
        try {
            prepareOutput()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare output file", e)
            releaseAll(); stopSelf(); return
        }

        // 3. Configure MediaRecorder
        val m = displayMetrics()
        try {
            setupRecorder(m.widthPixels, m.heightPixels)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure MediaRecorder", e)
            abandonOutput()
            releaseAll(); stopSelf(); return
        }

        // 4. Create VirtualDisplay — feeds screen frames into recorder surface
        try {
            virtualDisplay = projection!!.createVirtualDisplay(
                "YTSR_VD",
                m.widthPixels, m.heightPixels, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder!!.surface, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay creation failed", e)
            abandonOutput()
            releaseAll(); stopSelf(); return
        }

        // 5. Start
        recorder!!.start()
        isRecording = true
        Log.i(TAG, "▶ Recording started — output: $displayName")
        updateNotification(recording = true)
    }

    // ── Stop ──────────────────────────────────────────────────────────────

    private fun stopRecording() {
        if (!isRecording) return

        // Stop encoder — may throw if recording was very short; that's OK on Android TV
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "recorder.stop() threw (harmless on short recordings): ${e.message}")
        }

        // Finalise output — MUST happen before release()
        finaliseOutput()

        releaseAll()

        Log.i(TAG, "■ Recording stopped — file: $displayName")
    }

    // ── Output helpers (API 29+ MediaStore  vs  API < 29 File) ───────────

    /**
     * API 29+: inserts a pending MediaStore entry in Movies/YTSR/ and opens
     *          its FileDescriptor so MediaRecorder writes directly into it.
     * API < 29: creates a File in the public Movies/YTSR/ directory.
     */
    @Throws(Exception::class)
    private fun prepareOutput() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        displayName = "YTSR_$ts.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE,    "video/mp4")
                // RELATIVE_PATH puts the file in /sdcard/Movies/YTSR/
                // This folder is visible to every file manager on Android TV
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${android.os.Environment.DIRECTORY_MOVIES}/YTSR")
                put(MediaStore.Video.Media.IS_PENDING, 1) // hide until recording finishes
            }
            mediaStoreUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore.insert returned null")

            mediaStorePfd = contentResolver.openFileDescriptor(mediaStoreUri!!, "w")
                ?: throw Exception("Cannot open FileDescriptor for MediaStore URI")

            Log.i(TAG, "MediaStore entry created → $mediaStoreUri")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MOVIES), "YTSR"
            )
            dir.mkdirs()
            legacyFile = File(dir, displayName)
            Log.i(TAG, "Legacy file path → ${legacyFile!!.absolutePath}")
        }
    }

    /**
     * API 29+: marks IS_PENDING=0 → file becomes visible in file managers.
     * API < 29: triggers MediaScanner to index the new file.
     */
    private fun finaliseOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                mediaStorePfd?.close()
                mediaStoreUri?.let { uri ->
                    contentResolver.update(uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null, null)
                    Log.i(TAG, "✅ File published to Movies/YTSR/$displayName  uri=$uri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalise MediaStore entry", e)
            }
        } else {
            legacyFile?.let { f ->
                if (f.exists() && f.length() > 0) {
                    MediaScannerConnection.scanFile(
                        applicationContext, arrayOf(f.absolutePath), arrayOf("video/mp4")
                    ) { path, uri -> Log.i(TAG, "✅ Scanned: $path  uri=$uri") }
                }
            }
        }
    }

    /**
     * Called when recording FAILED before it started — removes the pending
     * MediaStore entry so it doesn't leave a corrupt ghost file.
     */
    private fun abandonOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { mediaStorePfd?.close() }
            mediaStoreUri?.let { contentResolver.delete(it, null, null) }
        } else {
            legacyFile?.delete()
        }
        mediaStoreUri = null; mediaStorePfd = null; legacyFile = null
    }

    // ── MediaRecorder configuration ────────────────────────────────────────

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

            // Point to MediaStore FD (API 29+) or file path (API < 29)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setOutputFile(mediaStorePfd!!.fileDescriptor)
            } else {
                setOutputFile(legacyFile!!.absolutePath)
            }

            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(8_000_000)   // 8 Mbps — suitable for 1080p TV
            setVideoFrameRate(30)
            setVideoSize(w, h)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setAudioChannels(2)
            prepare()
        }
    }

    // ── Resource cleanup ───────────────────────────────────────────────────

    private fun releaseAll() {
        runCatching { virtualDisplay?.release() }
        runCatching { recorder?.reset(); recorder?.release() }
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        virtualDisplay = null; recorder = null; projection = null
        mediaStoreUri  = null; mediaStorePfd = null; legacyFile = null
        isRecording    = false
        updateNotification(recording = false)
    }

    // ── Display metrics ────────────────────────────────────────────────────

    private fun displayMetrics(): DisplayMetrics = DisplayMetrics().also { m ->
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            m.widthPixels = b.width()
            m.heightPixels = b.height()
            m.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false); enableVibration(false); setSound(null, null)
                    }
                )
        }
    }

    private fun buildNotification(recording: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (recording) "🔴 YTSR — Recording" else "YTSR — Ready")
            .setContentText(
                if (recording) "Double-press [1] on remote to stop & save"
                else "Double-press [0] on remote to start"
            )
            .setSmallIcon(if (recording) R.drawable.ic_record else R.drawable.ic_stop)
            .setContentIntent(openApp)
            .setOngoing(recording)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (recording) addAction(R.drawable.ic_stop, "Stop", stopPi) }
            .build()
    }

    private fun updateNotification(recording: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(recording))
    }
}
