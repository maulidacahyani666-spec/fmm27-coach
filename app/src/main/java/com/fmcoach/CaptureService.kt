package com.fmcoach

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.abs

/**
 * Satu VirtualDisplay dibuat sekali (Android 14+ melarang membuat ulang dari token yang sama),
 * ukurannya di-resize mengikuti rotasi layar (game landscape), dan frame terbaru selalu ditahan.
 * Tombol "FM" melayang di atas game: tekan untuk capture.
 */
class CaptureService : Service() {
    companion object {
        val running = MutableStateFlow(false)
        const val ACTION_CAPTURE = "com.fmcoach.CAPTURE"
        const val ACTION_STOP = "com.fmcoach.STOP"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var size = Point(0, 0)
    private var latest: Image? = null
    private var overlay: TextView? = null
    private var busy = false
    private val mpCb = object : MediaProjection.Callback() { override fun onStop() { main.post { stopSelf() } } }
    private val dispCb = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) ensureSize() }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        when (i.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_CAPTURE -> { if (mp == null) stopSelf() else doCapture(); return START_NOT_STICKY }
        }
        if (mp != null) return START_NOT_STICKY
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java) else i.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try { setup(i.getIntExtra("code", 0), data) } catch (e: Exception) { toast("Gagal memulai: ${e.message}"); stopSelf(); return START_NOT_STICKY }
        addOverlay()
        running.value = true
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("cap", "Capture", NotificationManager.IMPORTANCE_LOW))
        fun pi(a: String, rc: Int) = PendingIntent.getService(this, rc, Intent(this, CaptureService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "cap").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("FM27 Coach aktif").setContentText("Tekan tombol FM di layar game untuk capture")
            .addAction(Notification.Action.Builder(0, "Capture", pi(ACTION_CAPTURE, 1)).build())
            .addAction(Notification.Action.Builder(0, "Stop", pi(ACTION_STOP, 2)).build()).build()
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Point = Point().also { (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it) }

    private fun newReader(s: Point): ImageReader {
        val r = ImageReader.newInstance(s.x, s.y, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) synchronized(lock) { latest?.close(); latest = img }
        }, main)
        return r
    }

    private fun setup(code: Int, data: Intent) {
        val p = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
        p.registerCallback(mpCb, main)
        mp = p
        size = realSize()
        val r = newReader(size); reader = r
        vd = p.createVirtualDisplay("fm27coach", size.x, size.y, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(dispCb, main)
    }

    /** Rotasi layar mengubah ukuran: resize display & ganti reader (tanpa membuat VirtualDisplay baru). */
    private fun ensureSize() {
        val d = vd ?: return
        val s = realSize()
        if (s.x == size.x && s.y == size.y) return
        size = s
        synchronized(lock) { latest?.close(); latest = null }
        val old = reader
        val r = newReader(s); reader = r
        d.resize(s.x, s.y, resources.displayMetrics.densityDpi)
        d.surface = r.surface
        old?.close()
    }

    private fun addOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            text = "FM"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setBackgroundColor(0xCC6750A4.toInt()); setPadding(36, 28, 36, 28)
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 20; y = 200
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (abs(dx) + abs(dy) > 12) moved = true
                    lp.x = ox + dx; lp.y = oy + dy; wm.updateViewLayout(v, lp); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) doCapture(); true }
                else -> false
            }
        }
        try { wm.addView(tv, lp); overlay = tv } catch (e: Exception) { toast("Tombol melayang gagal (izin 'tampil di atas app lain'?). Pakai tombol Capture di notifikasi.") }
    }

    private fun doCapture() {
        if (busy) return
        busy = true
        overlay?.visibility = View.INVISIBLE      // sembunyikan tombol agar tidak ikut tertangkap
        ensureSize()
        main.postDelayed({
            val bmp = synchronized(lock) { latest?.let { toBitmap(it) } }
            overlay?.visibility = View.VISIBLE
            if (bmp == null) { toast("Belum ada frame layar, coba lagi"); busy = false } else ocr(bmp)
        }, 450)
    }

    private fun toBitmap(img: Image): Bitmap {
        val pl = img.planes[0]; val w = img.width; val h = img.height
        val b = Bitmap.createBitmap(w + (pl.rowStride - pl.pixelStride * w) / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
        pl.buffer.rewind(); b.copyPixelsFromBuffer(pl.buffer)
        return if (b.width == w) b else Bitmap.createBitmap(b, 0, 0, w, h)
    }

    private fun ocr(bmp: Bitmap) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { res ->
                val toks = res.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                    .mapNotNull { e -> e.boundingBox?.let { Tok(e.text, it.centerX(), it.centerY(), it.height()) } }
                io.launch { save(bmp, toks) }
            }
            .addOnFailureListener { toast("OCR gagal: ${it.message}"); busy = false }
    }

    private suspend fun save(bmp: Bitmap, toks: List<Tok>) {
        val ts = System.currentTimeMillis()
        val f = File(filesDir, "cap_$ts.jpg")
        runCatching { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 60, it) } }
        val kind = StatsParser.classify(toks)
        val hd = StatsParser.header(toks)?.let { "${it[0]} ${it[1]}-${it[2]} ${it[3]}" } ?: ""
        val dao = Db.get(this).dao()
        dao.insertCapture(CaptureRow(ts = ts, kind = kind.name, header = hd, tokensJson = toks.toJson(), imgPath = f.absolutePath))
        val msg = when {
            toks.size < 8 -> "OCR hampir kosong (${toks.size} teks). Layar mungkin hitam/terblokir: buka capture di app untuk melihat gambarnya."
            kind == Kind.UNKNOWN -> "Halaman tidak dikenali. Buka statistik tim atau statistik pemain, lalu capture lagi."
            else -> {
                val other = if (kind == Kind.TEAM) "PLAYER" else "TEAM"
                if (dao.latest(other) != null) Assembler.run(dao) else "${kind.name} tersimpan. Sekarang capture halaman ${if (kind == Kind.TEAM) "statistik pemain" else "statistik tim"}."
            }
        }
        toast(msg); main.post { busy = false }
    }

    private fun toast(s: String) { main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show() } }

    override fun onDestroy() {
        running.value = false
        overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        runCatching { (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(dispCb) }
        synchronized(lock) { latest?.close(); latest = null }
        vd?.release(); reader?.close()
        mp?.unregisterCallback(mpCb); mp?.stop()
        io.cancel()
        super.onDestroy()
    }
}
