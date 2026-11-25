package com.fmcoach

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.fmcoach.START"
        const val ACTION_CAPTURE = "com.fmcoach.CAPTURE"
        const val ACTION_STOP = "com.fmcoach.STOP"
        const val EXTRA_RC = "rc"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "fmcoach"
        private const val NOTIF_ID = 42

        fun start(ctx: Context, resultCode: Int, data: Intent): Intent =
            Intent(ctx, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RC, resultCode)
                .putExtra(EXTRA_DATA, data)

        fun capture(ctx: Context): Intent =
            Intent(ctx, CaptureService::class.java).setAction(ACTION_CAPTURE)

        fun stop(ctx: Context): Intent =
            Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capturePending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat(buildNotification("Siap. Tekan Capture saat layar game tampil."))
                setupProjection(intent)
                return START_STICKY
            }
            ACTION_CAPTURE -> {
                capturePending = true
                notify("Menangkap layar...")
                return START_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun setupProjection(intent: Intent) {
        val rc = intent.getIntExtra(EXTRA_RC, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra(EXTRA_DATA) as? Intent ?: return
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = mgr.getMediaProjection(rc, data) ?: return

        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val wm = getSystemService(WindowManager::class.java)
        val dims = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val m = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            m.widthPixels to m.heightPixels
        }
        val (w, h) = dims
        val dpi = resources.displayMetrics.densityDpi

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay(
            "FM27Capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, Handler(Looper.getMainLooper())
        )
        reader!!.setOnImageAvailableListener({ r ->
            if (!capturePending) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            capturePending = false
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bmp = try { img.toBitmap() } catch (t: Throwable) { null } finally { img.close() }
            if (bmp != null) processBitmap(bmp)
            else notify("Gagal ambil frame. Coba lagi.")
        }, Handler(Looper.getMainLooper()))
    }

    private fun Image.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun processBitmap(bitmap: Bitmap) {
        scope.launch {
            try {
                val tokens = ocr(bitmap)
                val result = StatsParser.parse(tokens)
                val kind = when (result) {
                    is ParseResult.Team -> "TEAM"
                    is ParseResult.Player -> "PLAYER"
                    else -> "UNKNOWN"
                }
                val db = AppDatabase.get(this@CaptureService)
                db.dao().insertCapture(
                    Capture(
                        timestamp = System.currentTimeMillis(),
                        kind = kind,
                        tokensJson = json.encodeToString(tokens),
                    )
                )
                notify("Tersimpan sebagai $kind")
            } catch (t: Throwable) {
                notify("OCR/parse gagal: ${t.message}")
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun ocr(bitmap: Bitmap): List<TextToken> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val out = mutableListOf<TextToken>()
        for (block in result.textBlocks) for (line in block.lines) for (el in line.elements) {
            val b = el.boundingBox ?: continue
            out += TextToken(el.text, b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat())
        }
        return out
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "FM27 Coach", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val capPi = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("FM27 Coach")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "Capture", capPi)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
