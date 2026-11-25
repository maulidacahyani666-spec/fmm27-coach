package com.fmcoach

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val i = CaptureService.start(this, result.resultCode, result.data!!)
                startForegroundService(i)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(onRequestProjection = { askProjection() })
                }
            }
        }
    }

    private fun askProjection() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(onRequestProjection: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val dao = db.dao()
    val captures by dao.capturesFlow().collectAsState(initial = emptyList())
    val matches by dao.matchesFlow().collectAsState(initial = emptyList())
    var currentReport by remember { mutableStateOf<Report?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("FM27 Coach") }) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            if (currentReport == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRequestProjection) { Text("Mulai Capture") }
                    OutlinedButton(onClick = { ctx.startService(CaptureService.stop(ctx)) }) { Text("Stop") }
                }
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val team = dao.lastCaptureOfKind("TEAM") ?: return@launch
                            val player = dao.lastCaptureOfKind("PLAYER") ?: return@launch
                            val json = Json { ignoreUnknownKeys = true }
                            val tTokens = json.decodeFromString<List<TextToken>>(team.tokensJson)
                            val pTokens = json.decodeFromString<List<TextToken>>(player.tokensJson)
                            
                            val tr = StatsParser.parse(tTokens)
                            val pr = StatsParser.parse(pTokens)
                            
                            if (tr !is ParseResult.Team || pr !is ParseResult.Player) return@launch
                            val header = tr.header ?: pr.header
                            
                            val md = MatchData(
                                homeTeam = header?.homeTeam ?: "Home",
                                awayTeam = header?.awayTeam ?: "Away",
                                homeScore = header?.homeScore ?: 0,
                                awayScore = header?.awayScore ?: 0,
                                home = tr.home,
                                away = tr.away,
                                players = pr.players,
                            )
                            
                            val all = matches.mapNotNull {
                                runCatching { json.decodeFromString<MatchData>(it.dataJson) }.getOrNull()
                            } + md
                            
                            val rep = Coach.report(md, all)
                            dao.insertMatch(
                                MatchEntity(
                                    timestamp = System.currentTimeMillis(),
                                    homeTeam = md.homeTeam,
                                    awayTeam = md.awayTeam,
                                    homeScore = md.homeScore,
                                    awayScore = md.awayScore,
                                    dataJson = json.encodeToString(MatchData.serializer(), md),
                                )
                            )
                            CoroutineScope(Dispatchers.Main).launch { currentReport = rep }
                        }
                    },
                    enabled = captures.any { it.kind == "TEAM" } && captures.any { it.kind == "PLAYER" }
                ) { Text("Susun Match dari Capture terakhir") }

                Spacer(Modifier.height(12.dp))
                Text("Capture (${captures.size})", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(captures) { c ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${c.kind} — ${c.timestamp}")
                            }
                        }
                    }
                }

                Text("Match tersimpan (${matches.size})", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(matches) { m ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                Modifier.padding(8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${m.homeTeam} ${m.homeScore}-${m.awayScore} ${m.awayTeam}")
                                }
                                Button(onClick = {
                                    val json = Json { ignoreUnknownKeys = true }
                                    val md = runCatching {
                                        json.decodeFromString<MatchData>(m.dataJson)
                                    }.getOrNull() ?: return@Button
                                    val all = matches.mapNotNull {
                                        runCatching { json.decodeFromString<MatchData>(it.dataJson) }.getOrNull()
                                    }
                                    currentReport = Coach.report(md, all)
                                }) { Text("Laporan") }
                            }
                        }
                    }
                }
            } else {
                val rep = currentReport!!
                Button(onClick = { currentReport = null }) { Text("← Kembali") }
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    item {
                        Text(
                            "${rep.match.homeTeam} ${rep.match.homeScore}-${rep.match.awayScore} ${rep.match.awayTeam}",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (rep.patterns.isNotEmpty()) {
                        item { Text("Pola lintas match", style = MaterialTheme.typography.titleMedium) }
                        items(rep.patterns) { f -> FindingCard(f) }
                    }
                    item { Text("Temuan match ini", style = MaterialTheme.typography.titleMedium) }
                    items(rep.findings) { f -> FindingCard(f) }
                }
            }
        }
    }
}

@Composable
private fun FindingCard(f: Finding) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text("Temuan: ${f.temuan}", style = MaterialTheme.typography.titleSmall)
            Text("Penyebab: ${f.penyebab}")
            Text("Solusi: ${f.solusi}")
        }
    }
}
