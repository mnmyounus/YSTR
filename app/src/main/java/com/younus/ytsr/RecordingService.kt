package com.younus.ytsr

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecordingService
 * ────────────────
 * Foreground Service (foregroundServiceType="mediaProjection").
 *
 * STORAGE
 *   API 29+  → MediaStore → public Movies/YTSR/ (visible to all TV file managers)
 *   API <29  → Environment.DIRECTORY_MOVIES/YTSR/
 *
 * VIDEO
 *   Capped at 1920×1080 — 4K TVs overload the encoder at normal bitrates,
 *   causing frozen/stuttering frames.
 *   H.264 requires even-numbered width and height — enforced in capResolution().
 *
 * AUDIO
 *   Tries AudioSource.MIC first. Most Android TV boxes have no mic — if setup
 *   fails we reset and retry video-only. A corrupt empty audio track causes
 *   players to lose A/V sync and "rewind" during playback.
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

    // ── State ──────────────────────────────────────────────────────────────

    private var projection:     MediaProjection?     = null
    private var recorder:       MediaRecorder?       = null
    private var virtualDisplay: VirtualDisplay?      = null

    // API 29+: MediaStore entry + open file descriptor
    private var mediaStoreUri:  Uri?                 = null
    private var mediaStorePfd:  ParcelFileDescriptor? = null
    private var displayName:    String               = ""

    // API <29: direct public file path
    private var legacyFile:     File?                = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "Projection stopped externally — halting")
            stopRecording()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    // ── Start ──────────────────────────────────────────────────────────────

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return

        // 1. Obtain MediaProjection token
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(resultCode, data)?.also {
            it.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        } ?: run {
            Log.e(TAG, "getMediaProjection returned null"); stopSelf(); return
        }

        // 2. Prepare output destination
        try {
            prepareOutput()
        } catch (e: Exception) {
            Log.e(TAG, "prepareOutput failed: ${e.message}")
            releaseAll(); stopSelf(); return
        }

        // 3. Get display size and cap to 1080p
        val raw     = displayMetrics()
        val (capW, capH) = capResolution(raw.widthPixels, raw.heightPixels)

        // 4. Configure MediaRecorder (with audio, or video-only fallback)
        recorder = createFreshRecorder()
        try {
            setupRecorder(capW, capH)
        } catch (e: Exception) {
            Log.e(TAG, "setupRecorder failed: ${e.message}")
            abandonOutput(); releaseAll(); stopSelf(); return
        }

        // 5. Mirror screen into recorder surface via VirtualDisplay
        try {
            virtualDisplay = projection!!.createVirtualDisplay(
                "YTSR_VD",
                capW, capH, raw.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder!!.surface, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay failed: ${e.message}")
            abandonOutput(); releaseAll(); stopSelf(); return
        }

        // 6. Start
        recorder!!.start()
        isRecording = true
        Log.i(TAG, "▶ Recording started — $displayName")
        updateNotification(recording = true)
    }

    // ── Stop ───────────────────────────────────────────────────────────────

    private fun stopRecording() {
        if (!isRecording) return

        // stop() can throw on TV when recording is very short — do NOT delete the file
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "recorder.stop() threw (usually harmless): ${e.message}")
        }

        finaliseOutput()  // publish file BEFORE releasing resources
        releaseAll()
        Log.i(TAG, "■ Recording stopped — $displayName")
    }

    // ── MediaRecorder setup ────────────────────────────────────────────────

    /**
     * First attempts to configure with MIC audio.
     * If that fails (no mic hardware on this TV), resets and retries video-only.
     * An empty/corrupt audio track causes A/V sync failure and "rewind" in players.
     */
    @Throws(Exception::class)
    private fun setupRecorder(w: Int, h: Int) {
        Log.i(TAG, "Configuring recorder — ${w}×${h} @ ${bitrateFor(w, h) / 1_000_000}Mbps")

        val audioOk = tryBuildRecorder(w, h, audio = true)
        if (!audioOk) {
            Log.w(TAG, "MIC unavailable — retrying as video-only")
            recorder?.reset()
            recorder?.release()
            recorder = createFreshRecorder()
            val videoOk = tryBuildRecorder(w, h, audio = false)
            if (!videoOk) throw Exception("MediaRecorder setup failed for both audio+video and video-only")
        }
    }

    /**
     * Attempts to configure and prepare [recorder].
     * Audio source MUST be set before video source (MediaRecorder state machine).
     * Returns true on success, false on any exception.
     */
    private fun tryBuildRecorder(w: Int, h: Int, audio: Boolean): Boolean {
        return try {
            recorder!!.apply {
                if (audio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setOutputFile(mediaStorePfd!!.fileDescriptor)
                } else {
                    setOutputFile(legacyFile!!.absolutePath)
                }

                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (audio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    setAudioChannels(2)
                }

                setVideoEncodingBitRate(bitrateFor(w, h))
                setVideoFrameRate(30)
                setVideoSize(w, h)
                prepare()
            }
            Log.i(TAG, "Recorder ready — audio=$audio")
            true
        } catch (e: Exception) {
            Log.w(TAG, "tryBuildRecorder(audio=$audio) failed: ${e.message}")
            false
        }
    }

    // ── Resolution helpers ─────────────────────────────────────────────────

    /**
     * Caps at 1920×1080. 4K + low bitrate = encoder overload = frozen video.
     * Also rounds down to even numbers — H.264 codec requirement.
     */
    private fun capResolution(w: Int, h: Int): Pair<Int, Int> {
        val scale = if (w > 1920 || h > 1080)
            minOf(1920f / w, 1080f / h) else 1f
        val outW = (w * scale).toInt().let { if (it % 2 == 0) it else it - 1 }
        val outH = (h * scale).toInt().let { if (it % 2 == 0) it else it - 1 }
        return Pair(outW, outH)
    }

    private fun bitrateFor(w: Int, h: Int): Int = when {
        w >= 1920 -> 8_000_000   // 1080p → 8 Mbps
        w >= 1280 -> 4_000_000   //  720p → 4 Mbps
        else      -> 2_000_000   // below → 2 Mbps
    }

    private fun createFreshRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()

    // ── Output helpers ─────────────────────────────────────────────────────

    /**
     * API 29+: Inserts a pending MediaStore entry under Movies/YTSR/ and
     *          opens its FileDescriptor. IS_PENDING=1 hides the file from
     *          file managers until recording finishes.
     * API <29: Creates a File in the public Movies/YTSR/ directory.
     */
    @Throws(Exception::class)
    private fun prepareOutput() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        displayName = "YTSR_$ts.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME,   displayName)
                put(MediaStore.Video.Media.MIME_TYPE,      "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/YTSR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            mediaStoreUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore.insert returned null")

            mediaStorePfd = contentResolver.openFileDescriptor(mediaStoreUri!!, "w")
                ?: throw Exception("Cannot open FileDescriptor for MediaStore URI")

            Log.i(TAG, "Output → MediaStore pending: $mediaStoreUri")

        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "YTSR"
            )
            dir.mkdirs()
            legacyFile = File(dir, displayName)
            Log.i(TAG, "Output → ${legacyFile!!.absolutePath}")
        }
    }

    /**
     * API 29+: Closes FD and sets IS_PENDING=0 — file becomes visible instantly.
     * API <29: Triggers MediaScanner so file appears without reboot.
     */
    private fun finaliseOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { mediaStorePfd?.close() }
            mediaStoreUri?.let { uri ->
                try {
                    contentResolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        },
                        null, null
                    )
                    Log.i(TAG, "✅ Published → Movies/YTSR/$displayName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to publish MediaStore entry: ${e.message}")
                }
            }
        } else {
            legacyFile?.takeIf { it.exists() && it.length() > 0 }?.let { f ->
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(f.absolutePath),
                    arrayOf("video/mp4")
                ) { path, uri -> Log.i(TAG, "✅ Scanned → $path (uri=$uri)") }
            }
        }
    }

    /** Removes the pending ghost entry if recording failed before it started. */
    private fun abandonOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { mediaStorePfd?.close() }
            mediaStoreUri?.let { contentResolver.delete(it, null, null) }
        } else {
            legacyFile?.delete()
        }
        mediaStoreUri = null; mediaStorePfd = null; legacyFile = null
    }

    // ── Resource cleanup ───────────────────────────────────────────────────

    private fun releaseAll() {
        runCatching { virtualDisplay?.release() }
        runCatching { recorder?.reset(); recorder?.release() }
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        virtualDisplay = null
        recorder       = null
        projection     = null
        mediaStoreUri  = null
        mediaStorePfd  = null
        legacyFile     = null
        isRecording    = false
        updateNotification(recording = false)
    }

    // ── Display metrics ────────────────────────────────────────────────────

    private fun displayMetrics(): DisplayMetrics = DisplayMetrics().also { m ->
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b    = wm.currentWindowMetrics.bounds
            m.widthPixels  = b.width()
            m.heightPixels = b.height()
            m.densityDpi   = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(recording: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java)
                .apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (recording) "🔴 YTSR — Recording" else "YTSR — Ready")
            .setContentText(
                if (recording) "Double-press [1] on remote to stop & save"
                else "Double-press [0] on remote to start recording"
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
