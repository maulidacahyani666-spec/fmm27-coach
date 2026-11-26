package com.fmcoach

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val proj = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null)
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data))
    }
    private val notif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan 'Tampil di atas app lain' untuk FM27 Coach, lalu tekan Mulai Capture lagi", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        proj.launch((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        val dao = Db.get(this).dao()
        setContent {
            MaterialTheme {
                val running by CaptureService.running.collectAsState()
                Screen(dao, running, { start() }) { stopService(Intent(this, CaptureService::class.java)) }
            }
        }
    }
}

@Composable
fun Screen(dao: AppDao, running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("p", 0) }
    var club by remember { mutableStateOf(prefs.getString("club", "NEW") ?: "NEW") }
    val caps by remember { dao.captures() }.collectAsState(emptyList())
    val rows by remember { dao.matches() }.collectAsState(emptyList())
    val canAssemble = caps.any { it.kind == "TEAM" && !it.used } && caps.any { it.kind == "PLAYER" && !it.used }
    val ms = rows.map { it.toMatch() }

    LazyColumn(Modifier.systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("FM27 Coach", style = MaterialTheme.typography.headlineMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onStart, enabled = !running) { Text("Mulai Capture") }
                OutlinedButton(onStop, enabled = running) { Text("Stop") }
            }
        }
        item {
            Text(
                if (running) "Aktif. Buka game, buka halaman statistik tim lalu statistik pemain, dan tekan tombol FM di layar tiap halaman."
                else "Belum aktif."
            )
        }
        item {
            OutlinedTextField(
                club,
                {
                    club = it.uppercase().trim()
                    prefs.edit().putString("club", club).apply()
                },
                label = { Text("Kode klub Anda (mis. NEW)") },
                singleLine = true
            )
        }
        item {
            Button(
                { scope.launch { Toast.makeText(ctx, Assembler.run(dao), Toast.LENGTH_LONG).show() } },
                enabled = canAssemble
            ) {
                Text("Susun Match dari Capture terakhir")
            }
        }
        Coach.patterns(ms, club).takeIf { it.isNotEmpty() }?.let { p ->
            item {
                Column {
                    Text("Pola lintas match", fontWeight = FontWeight.Bold)
                    p.forEach { Text("• $it") }
                }
            }
        }
        item { Text("Capture (${caps.size})", style = MaterialTheme.typography.titleMedium) }
        items(caps, key = { it.id }) { c -> CaptureCard(c) { scope.launch { dao.deleteCapture(c.id) } } }
        item { Text("Match tersimpan (${rows.size})", style = MaterialTheme.typography.titleMedium) }
        items(rows, key = { it.id }) { r ->
            val m = r.toMatch()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${m.home} ${m.hg}-${m.ag} ${m.away}", fontWeight = FontWeight.Bold)
                    Coach.report(m, club).forEach { f ->
                        Text((if (f.good) "✅ " else "⚠️ ") + f.title, fontWeight = FontWeight.Medium)
                        if (f.cause.isNotEmpty()) Text(f.cause, style = MaterialTheme.typography.bodySmall)
                        if (f.fix.isNotEmpty()) Text("→ ${f.fix}")
                    }
                    TextButton({ scope.launch { dao.deleteMatch(r.id) } }) { Text("Hapus match") }
                }
            }
        }
    }
}

@Composable
fun CaptureCard(c: CaptureRow, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val ocrText = remember(c.id) { c.toks().joinToString(" ") { it.text } }
    val findings = remember(c.id, ocrText) {
        if (c.kind == "UNKNOWN" || c.kind == "TEAM" || c.kind == "PLAYER") {
            // Always try free-text analysis so panel analisis / mixed screens look clean
            Coach.findingsFromOcrText(ocrText)
        } else emptyList()
    }

    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${c.kind}${if (c.used) " ✓" else ""} · ${fmt.format(Date(c.ts))} · ${c.header}")
            if (open) {
                val bmp = remember(c.imgPath) { BitmapFactory.decodeFile(c.imgPath)?.asImageBitmap() }
                if (bmp != null) Image(bmp, null, Modifier.fillMaxWidth())

                if (findings.isNotEmpty()) {
                    // Structured findings – same style as Match cards
                    findings.forEach { f ->
                        Text((if (f.good) "✅ " else "⚠️ ") + f.title, fontWeight = FontWeight.Medium)
                        if (f.cause.isNotEmpty()) Text(f.cause, style = MaterialTheme.typography.bodySmall)
                        if (f.fix.isNotEmpty()) Text("→ ${f.fix}")
                    }
                } else {
                    // Fallback only when nothing could be extracted
                    Text(
                        "Teks OCR: " + ocrText.take(700),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onDelete) { Text("Hapus capture") }
            }
        }
    }
}
