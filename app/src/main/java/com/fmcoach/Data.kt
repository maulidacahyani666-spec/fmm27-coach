package com.fmcoach

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class TextToken(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

@Serializable
data class TeamStats(
    val rating: Double? = null,
    val poss: Double? = null,
    val shots: Int? = null,
    val sot: Int? = null,
    val xg: Double? = null,
    val pc: Int? = null,
    val pa: Int? = null,
    val crc: Int? = null,
    val cra: Int? = null,
    val fouls: Int? = null,
    val tkw: Int? = null,
    val hdw: Int? = null,
    val yc: Int? = null,
    val rc: Int? = null,
    val off: Int? = null,
    val cor: Int? = null,
)

@Serializable
data class PlayerStat(
    val pos: String,
    val name: String,
    val paA: Double?, val paC: Double?, val key: Double?, val ass: Double?,
    val taA: Double?, val taW: Double?, val int: Double?, val heA: Double?, val heW: Double?,
    val crA: Double?, val crC: Double?, val dri: Double?,
    val dispossessed: Double?, // Lain..
    val mistakeGoal: Double?,  // MiG
    val shA: Double?, val shO: Double?,
    val kon: Double?, val rating: Double?,
)

@Serializable
data class MatchData(
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val home: TeamStats,
    val away: TeamStats,
    val players: List<PlayerStat>,
)

@Entity(tableName = "captures")
data class Capture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val kind: String,
    val tokensJson: String,
)

@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val dataJson: String,
)

@Dao
interface CoachDao {
    @Insert suspend fun insertCapture(c: Capture): Long
    @Insert suspend fun insertMatch(m: MatchEntity): Long
    @Query("SELECT * FROM captures ORDER BY timestamp DESC") fun capturesFlow(): Flow<List<Capture>>
    @Query("SELECT * FROM matches ORDER BY timestamp DESC") fun matchesFlow(): Flow<List<MatchEntity>>
    @Query("SELECT * FROM captures WHERE kind = :kind ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastCaptureOfKind(kind: String): Capture?
}

@Database(entities = [Capture::class, MatchEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CoachDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "fmcoach.db")
                .build().also { inst = it }
        }
    }
}
