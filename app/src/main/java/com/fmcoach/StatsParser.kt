package com.fmcoach

import kotlin.math.abs

sealed class ParseResult {
    data class Team(val header: Header?, val home: TeamStats, val away: TeamStats) : ParseResult()
    data class Player(val header: Header?, val players: List<PlayerStat>) : ParseResult()
    data class Unknown(val header: Header?) : ParseResult()
}

data class Header(val homeTeam: String, val awayTeam: String, val homeScore: Int, val awayScore: Int)

object StatsParser {

    private val labelMap = mapOf(
        "nilai tim" to "rating",
        "penguasaan" to "poss",
        "tembakan" to "shots",
        "tembakan tepat sasaran" to "sot",
        "xg" to "xg",
        "umpan sukses" to "pc",
        "umpan dicoba" to "pa",
        "umpan silang berhasil" to "crc",
        "percobaan umpan silang" to "cra",
        "pelanggaran" to "fouls",
        "tekel berhasil" to "tkw",
        "sundulan berhasil" to "hdw",
    )

    fun parse(tokens: List<TextToken>): ParseResult {
        val header = parseHeader(tokens)
        val players = parsePlayers(tokens)
        if (players.size >= 5) return ParseResult.Player(header, players)
        
        val teamStats = parseTeamStats(tokens)
        if (teamStats != null) return ParseResult.Team(header, teamStats.first, teamStats.second)
        
        return ParseResult.Unknown(header)
    }

    private fun parseHeader(tokens: List<TextToken>): Header? {
        val lines = clusterLines(tokens)
        for (line in lines) {
            val texts = line.map { it.text }
            val ftIdx = texts.indexOfFirst { it.equals("FT", true) }
            if (ftIdx < 0) continue
            
            val numericIdx = texts.indices.filter { i -> i > ftIdx && texts[i].toIntOrNull() != null }
            if (numericIdx.size < 2) continue
            
            val hs = texts[numericIdx[0]].toInt()
            val as_ = texts[numericIdx[1]].toInt()
            
            val home = texts.subList(ftIdx + 1, numericIdx[0]).joinToString(" ").trim()
            val away = texts.subList(numericIdx[1] + 1, texts.size).joinToString(" ").trim()
            
            if (home.isNotEmpty() && away.isNotEmpty()) {
                return Header(home, away, hs, as_)
            }
        }
        return null
    }

    fun parseTeamStats(tokens: List<TextToken>): Pair<TeamStats, TeamStats>? {
        if (tokens.isEmpty()) return null
        val tolY = tokens.map { it.h }.maxOrNull()?.times(1.2f) ?: 20f

        fun cleanDouble(text: String): Double? {
            return text.replace("%", "").replace(",", ".").toDoubleOrNull()
        }

        fun nearestLeft(label: TextToken): Double? {
            val token = tokens.filter {
                it !== label && abs(it.y - label.y) <= tolY && it.x + it.w <= label.x + 2f
            }.maxByOrNull { it.x }
            return token?.let { cleanDouble(it.text) }
        }

        fun nearestRight(label: TextToken): Double? {
            val token = tokens.filter {
                it !== label && abs(it.y - label.y) <= tolY && it.x >= label.x + label.w - 2f
            }.minByOrNull { it.x }
            return token?.let { cleanDouble(it.text) }
        }

        var found = 0
        val homeBuilder = TeamStatsBuilder()
        val awayBuilder = TeamStatsBuilder()

        for (t in tokens) {
            val key = labelMap[t.text.lowercase().trim()] ?: continue
            val lv = nearestLeft(t)
            val rv = nearestRight(t)
            if (lv == null && rv == null) continue
            homeBuilder.set(key, lv)
            awayBuilder.set(key, rv)
            found++
        }
        
        if (found < 3) return null

        val lines = clusterLines(tokens)
        val numericLines = lines.filter { line ->
            if (line.size < 2) return@filter false
            cleanDouble(line.first().text) != null && cleanDouble(line.last().text) != null
        }
        
        if (numericLines.size >= 4) {
            val iconLines = numericLines.takeLast(4)
            val iconKeys = listOf("yc", "rc", "off", "cor")
            for (i in 0 until 4) {
                val line = iconLines[i]
                val lv = cleanDouble(line.first().text)
                val rv = cleanDouble(line.last().text)
                homeBuilder.set(iconKeys[i], lv)
                awayBuilder.set(iconKeys[i], rv)
            }
        }

        return homeBuilder.build() to awayBuilder.build()
    }

    private class TeamStatsBuilder {
        private val m = mutableMapOf<String, Double?>()
        fun set(key: String, v: Double?) { m[key] = v }
        fun build(): TeamStats = TeamStats(
            rating = m["rating"], poss = m["poss"],
            shots = m["shots"]?.toInt(), sot = m["sot"]?.toInt(),
            xg = m["xg"], pc = m["pc"]?.toInt(), pa = m["pa"]?.toInt(),
            crc = m["crc"]?.toInt(), cra = m["cra"]?.toInt(),
            fouls = m["fouls"]?.toInt(), tkw = m["tkw"]?.toInt(), hdw = m["hdw"]?.toInt(),
            yc = m["yc"]?.toInt(), rc = m["rc"]?.toInt(),
            off = m["off"]?.toInt(), cor = m["cor"]?.toInt(),
        )
    }

    fun parsePlayers(tokens: List<TextToken>): List<PlayerStat> {
        val lines = clusterLines(tokens)
        return lines.mapNotNull { parsePlayerLine(it) }
    }

    private fun parsePlayerLine(line: List<TextToken>): PlayerStat? {
        if (line.size < 19) return null 
        
        val pos = line.first().text
        if (pos.matches(Regex("S\\d+", RegexOption.IGNORE_CASE))) return null 
        
        val statsTokens = line.takeLast(18)
        val nums = statsTokens.map { it.text.replace(",", ".").toDoubleOrNull() }
        if (nums.any { it == null }) return null 
        
        val name = line.subList(1, line.size - 18).joinToString(" ") { it.text }.trim()
        if (name.isEmpty() || name == "-") return null
        
        return PlayerStat(
            pos = pos, name = name,
            paA = nums[0], paC = nums[1], key = nums[2], ass = nums[3],
            taA = nums[4], taW = nums[5], int = nums[6],
            heA = nums[7], heW = nums[8],
            crA = nums[9], crC = nums[10], dri = nums[11],
            dispossessed = nums[12], mistakeGoal = nums[13],
            shA = nums[14], shO = nums[15],
            kon = nums[16], rating = nums[17],
        )
    }

    fun clusterLines(tokens: List<TextToken>): List<List<TextToken>> {
        if (tokens.isEmpty()) return emptyList()
        val tol = tokens.map { it.h }.maxOrNull()?.times(0.6f) ?: 10f
        val sorted = tokens.sortedBy { it.y }
        val lines = mutableListOf<MutableList<TextToken>>()
        for (t in sorted) {
            val cur = lines.lastOrNull()
            if (cur != null && abs(cur.first().y - t.y) <= tol) cur.add(t)
            else lines.add(mutableListOf(t))
        }
        return lines.map { it.sortedBy { tk -> tk.x } }
    }
}
