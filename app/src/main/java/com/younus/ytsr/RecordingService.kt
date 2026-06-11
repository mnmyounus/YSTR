cat > /home/claude/YTSR/app/src/main/java/com/younus/ytsr/RecordingService.kt << 'KOTLIN_EOF'
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
 * API 29+ (all modern Android TV / Google TV):
 *   → MediaStore API → saves to public Movies/YTSR/ folder
 *   → Visible in every file manager immediately, no WRITE_EXTERNAL_STORAGE needed.
 *   → MediaRecorder writes directly into the MediaStore FileDescriptor.
 *
 * API < 29:
 *   → Public Movies/YTSR/ via Environment.DIRECTORY_MOVIES
 *   → WRITE_EXTERNAL_STORAGE declared in manifest with maxSdkVersion=28.
 *
 * VIDEO QUALITY STRATEGY
 * ───────────────────────
 * Resolution is capped at 1920×1080 regardless of display size.
 * Android TV is often 4K — encoding 4K at typical bitrates causes encoder
 * overload, resulting in frozen/stuttering video. 1080p at 8 Mbps is clean.
 * H.264 requires even-numbered width and height — enforced in capResolution().
 *
 * AUDIO STRATEGY
 * ───────────────
 * Android TV boxes usually have no physical microphone.
 * We try AudioSource.MIC first. If it fails (no hardware), we reset and
 * retry video-only. This prevents a corrupt/empty audio track which causes
 * players to lose A/V sync and "rewind" during playback.
 *
 * Developer: YOUNUS
 */
class RecordingService : Service() {

    // ── Constants ──────────────────────────────────────────────────────────

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

    // ── State ──────────────────────────────────────────────────────────────

    private var projection:     MediaProjection?      = null
    private var recorder:       MediaRecorder?        = null
    private var virtualDisplay: VirtualDisplay?       = null

    // API 29+: MediaStore entry + open file descriptor
    private var mediaStoreUri: android.net.Uri?       = null
    private var mediaStorePfd: ParcelFileDescriptor?  = null
    private var displayName:   String                 = ""

    // API < 29: direct public file path
    private var legacyFile:    File?                  = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "Projection stopped externally — halting recording")
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
                    Log.e(TAG, "Invalid MediaProjection token (code=$code) — aborting")
                    stopSelf()
                }
            }

            ACTION_STOP_RECORDING -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        // NOT_STICKY: never auto-restart — no auto-recording by design
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()   // safety net in case service is killed
        super.onDestroy()
    }

    // ── Start recording ────────────────────────────────────────────────────

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

        // 2. Prepare output destination (MediaStore or legacy file)
        try {
            prepareOutput()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare output: ${e.message}")
            releaseAll(); stopSelf(); return
        }

        // 3. Configure MediaRecorder (with audio fallback to video-only)
        val m = displayMetrics()
        recorder = createFreshRecorder()
        try {
            setupRecorder(m.widthPixels, m.heightPixels)
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder setup failed: ${e.message}")
            abandonOutput(); releaseAll(); stopSelf(); return
        }

        // 4. Create VirtualDisplay — mirrors screen into recorder surface
        try {
            val (capW, capH) = capResolution(m.widthPixels, m.heightPixels)
            virtualDisplay = projection!!.createVirtualDisplay(
                "YTSR_VD",
                capW, capH, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder!!.surface, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay creation failed: ${e.message}")
            abandonOutput(); releaseAll(); stopSelf(); return
        }

        // 5. Start
        recorder!!.start()
        isRecording = true
        Log.i(TAG, "▶ Recording started — $displayName")
        updateNotification(recording = true)
    }

    // ── Stop recording ─────────────────────────────────────────────────────

    private fun stopRecording() {
        if (!isRecording) return

        // stop() can throw on Android TV if recording was < 1 second.
        // Do NOT delete the file here — it is usually still valid.
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "recorder.stop() threw (often harmless on short recordings): ${e.message}")
        }

        // Finalise BEFORE release() — publishes file to Movies/YTSR/
        finaliseOutput()

        releaseAll()

        Log.i(TAG, "■ Recording stopped — $displayName")
    }

    // ── MediaRecorder setup ────────────────────────────────────────────────

    /**
     * Caps resolution to 1080p, then tries to configure MediaRecorder with
     * audio (MIC). If audio source is unavailable (no mic on this TV box),
     * resets and retries as video-only to avoid a corrupt empty audio track
     * which causes players to lose sync and "rewind" during playback.
     */
    @Throws(Exception::class)
    private fun setupRecorder(w: Int, h: Int) {
        val (capW, capH) = capResolution(w, h)
        Log.i(TAG, "Display ${w}×${h} → Recording ${capW}×${capH} @ ${bitrateFor(capW, capH) / 1_000_000}Mbps")

        // Try with audio first
        val audioOk = tryBuildRecorder(capW, capH, audio = true)

        if (!audioOk) {
            Log.w(TAG, "MIC unavailable — retrying as video-only (no audio track)")
            recorder?.reset()
            recorder?.release()
            recorder = createFreshRecorder()

            val videoOk = tryBuildRecorder(capW, capH, audio = false)
            if (!videoOk) throw Exception("MediaRecorder setup failed even without audio")
        }
    }

    /**
     * Attempts to fully configure and prepare [recorder].
     * Returns true on success, false if an exception is thrown.
     * Audio source MUST be set before video source per MediaRecorder state machine.
     */
    private fun tryBuildRecorder(w: Int, h: Int, audio: Boolean): Boolean {
        return try {
            recorder!!.apply {
                if (audio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                // Point output to MediaStore FD (API 29+) or file path (API < 29)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    setOutputFile(mediaStorePfd!!.fileDescriptor)
                else
                    setOutputFile(legacyFile!!.absolutePath)

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
     * Caps recording at 1920×1080 regardless of display size.
     * 4K TVs at low bitrates cause encoder overload → frozen/stuttering video.
     * Also enforces even dimensions — H.264 requires width and height to be even.
     */
    private fun capResolution(w: Int, h: Int): Pair<Int, Int> {
        val maxW  = 1920
        val maxH  = 1080
        val scale = if (w > maxW || h > maxH)
            minOf(maxW.toFloat() / w, maxH.toFloat() / h)
        else 1f
        val outW = (w * scale).toInt().let { if (it % 2 == 0) it else it - 1 }
        val outH = (h * scale).toInt().let { if (it % 2 == 0) it else it - 1 }
        return Pair(outW, outH)
    }

    /**
     * Returns a suitable H.264 bitrate for the capped resolution.
     * Too low  → compression artefacts and macro-blocking.
     * Too high → encoder overload → dropped frames → frozen video.
     */
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
     * API 29+: Inserts a pending MediaStore entry in Movies/YTSR/ and opens
     *          its FileDescriptor for MediaRecorder to write into directly.
     *          IS_PENDING=1 hides the file until recording finishes.
     *
     * API <29: Creates a File in the public Movies/YTSR/ directory.
     */
    @Throws(Exception::class)
    private fun prepareOutput() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        displayName = "YTSR_$ts.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE,    "video/mp4")
                // Saves to /sdcard/Movies/YTSR/ — accessible to all TV file managers
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${android.os.Environment.DIRECTORY_MOVIES}/YTSR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            mediaStoreUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore.insert returned null")

            mediaStorePfd = contentResolver.openFileDescriptor(mediaStoreUri!!, "w")
                ?: throw Exception("Cannot open FileDescriptor for MediaStore URI")

            Log.i(TAG, "Output → MediaStore pending entry: $mediaStoreUri")

        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MOVIES), "YTSR"
            )
            dir.mkdirs()
            legacyFile = File(dir, displayName)
            Log.i(TAG, "Output → ${legacyFile!!.absolutePath}")
        }
    }

    /**
     * API 29+: Closes the FileDescriptor and sets IS_PENDING=0 so the file
     *          becomes visible in file managers immediately.
     * API <29: Triggers MediaScanner to index the file without reboot.
     */
    private fun finaliseOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { mediaStorePfd?.close() }
            mediaStoreUri?.let { uri ->
                try {
                    contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null, null
                    )
                    Log.i(TAG, "✅ Published → Movies/YTSR/$displayName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to finalise MediaStore entry: ${e.message}")
                }
            }
        } else {
            legacyFile?.takeIf { it.exists() && it.length() > 0 }?.let { f ->
                MediaScannerConnection.scanFile(
                    applicationContext, arrayOf(f.absolutePath), arrayOf("video/mp4")
                ) { path, uri -> Log.i(TAG, "✅ Scanned → $path  uri=$uri") }
            }
        }
    }

    /**
     * Called when recording FAILED before it started.
     * Removes the pending MediaStore ghost entry / deletes empty file.
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
            val b = wm.currentWindowMetrics.bounds
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
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
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
KOTLIN_EOF
echo "✅ $(wc -l < /home/claude/YTSR/app/src/main/java/com/younus/ytsr/RecordingService.kt) lines written"
