package com.fmcoach

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

data class Tok(val text: String, val x: Int, val y: Int, val h: Int = 24)

data class PlayerLine(val pos: String, val name: String, val v: List<Int>) {
    // v: PaA,PaC,Key,Ass,TaA,TaW,Int,HeA,HeW,CrA,CrC,Dri,Lain,MiG,ShA,ShO,Kon,Rating
    val passAcc get() = if (v[0] > 0) v[1] * 100.0 / v[0] else 100.0
    val key get() = v[2]; val ass get() = v[3]; val kon get() = v[16]; val rating get() = v[17]
}

data class Match(val home: String, val away: String, val hg: Int, val ag: Int,
                 val team: Map<String, Pair<Double, Double>>, val players: List<PlayerLine>)

@Entity(tableName = "captures")
data class CaptureRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val ts: Long, val kind: String,
                      val header: String, val tokensJson: String, val imgPath: String, val used: Boolean = false)

@Entity(tableName = "matches")
data class MatchRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val home: String, val away: String,
                    val hg: Int, val ag: Int, val ts: Long, val teamJson: String, val playersJson: String) {
    fun toMatch(): Match {
        val t = JSONObject(teamJson)
        val team = t.keys().asSequence().associateWith { k -> t.getJSONArray(k).let { it.getDouble(0) to it.getDouble(1) } }
        val pa = JSONArray(playersJson)
        val pl = (0 until pa.length()).map { i ->
            val o = pa.getJSONObject(i); val v = o.getJSONArray("v")
            PlayerLine(o.getString("pos"), o.getString("name"), (0 until v.length()).map { v.getInt(it) })
        }
        return Match(home, away, hg, ag, team, pl)
    }
    companion object {
        fun from(m: Match) = MatchRow(home = m.home, away = m.away, hg = m.hg, ag = m.ag, ts = System.currentTimeMillis(),
            teamJson = JSONObject().also { j -> m.team.forEach { (k, p) -> j.put(k, JSONArray(listOf(p.first, p.second))) } }.toString(),
            playersJson = JSONArray().also { a -> m.players.forEach { p -> a.put(JSONObject().put("pos", p.pos).put("name", p.name).put("v", JSONArray(p.v))) } }.toString())
    }
}

fun List<Tok>.toJson(): String = JSONArray().also { a -> forEach { a.put(JSONObject().put("t", it.text).put("x", it.x).put("y", it.y).put("h", it.h)) } }.toString()
fun CaptureRow.toks(): List<Tok> { val a = JSONArray(tokensJson); return (0 until a.length()).map { val o = a.getJSONObject(it); Tok(o.getString("t"), o.getInt("x"), o.getInt("y"), o.getInt("h")) } }

@Dao interface AppDao {
    @Insert suspend fun insertCapture(c: CaptureRow): Long
    @Insert suspend fun insertMatch(m: MatchRow)
    @Query("SELECT * FROM captures ORDER BY ts DESC") fun captures(): Flow<List<CaptureRow>>
    @Query("SELECT * FROM matches ORDER BY id DESC") fun matches(): Flow<List<MatchRow>>
    @Query("SELECT * FROM captures WHERE kind = :k AND used = 0 ORDER BY ts DESC LIMIT 1") suspend fun latest(k: String): CaptureRow?
    @Query("UPDATE captures SET used = 1 WHERE id IN (:ids)") suspend fun markUsed(ids: List<Long>)
    @Query("DELETE FROM captures WHERE id = :id") suspend fun deleteCapture(id: Long)
    @Query("DELETE FROM matches WHERE id = :id") suspend fun deleteMatch(id: Long)
}

@Database(entities = [CaptureRow::class, MatchRow::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var i: Db? = null
        fun get(c: Context): Db = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "fm27coach_v2.db")
                .fallbackToDestructiveMigration().build().also { i = it }
        }
    }
}

/** Menggabungkan 1 capture TEAM + 1 capture PLAYER yang belum dipakai menjadi satu Match. */
object Assembler {
    suspend fun run(dao: AppDao): String {
        val t = dao.latest("TEAM") ?: return "Belum ada capture statistik tim"
        val p = dao.latest("PLAYER") ?: return "Belum ada capture statistik pemain"
        val team = StatsParser.team(t.toks()); val players = StatsParser.players(p.toks())
        if (team.size < 3) return "Statistik tim hanya terbaca ${team.size} baris. Buka capture di daftar untuk melihat teks OCR."
        if (players.isEmpty()) return "Tidak ada baris pemain terbaca. Buka capture di daftar untuk melihat teks OCR."
        val h = StatsParser.header(t.toks()) ?: StatsParser.header(p.toks())
        val m = Match(h?.get(0) ?: "HOME", h?.get(3) ?: "AWAY", h?.get(1)?.toInt() ?: 0, h?.get(2)?.toInt() ?: 0, team, players)
        dao.insertMatch(MatchRow.from(m)); dao.markUsed(listOf(t.id, p.id))
        return "Match ${m.home} ${m.hg}-${m.ag} ${m.away} disimpan (${players.size} pemain, ${team.size} statistik tim)"
    }
}
