package com.fmcoach

import kotlin.math.abs
import kotlin.math.max

enum class Kind { TEAM, PLAYER, UNKNOWN }

/** Mengubah token OCR (teks + posisi) dari layar laporan FM27 Mobile (bahasa Indonesia) menjadi data. */
object StatsParser {
    private val NUM = Regex("""^\d+([.,]\d+)?%?$""")
    private val POS = setOf("GK", "DR", "DL", "DC", "DMC", "DMR", "DML", "WBR", "WBL", "MC", "MR", "ML", "AMC", "AMR", "AML", "FC", "ST")

    fun num(s: String): Double? = if (NUM.matches(s)) s.replace(",", ".").removeSuffix("%").toDouble() else null
    private fun numStat(s: String): Int? = num(s)?.toInt() ?: if (s == "O" || s == "o") 0 else null

    private fun keyFor(l: String): String? {
        val s = l.lowercase()
        return when {
            "silang" in s && "berhasil" in s -> "crc"
            "silang" in s -> "cra"
            "tepat" in s -> "sot"
            "tembakan" in s -> "shots"
            "xg" in s -> "xg"
            "penguasaan" in s -> "poss"
            "sukses" in s -> "pc"
            "dicoba" in s -> "pa"
            "nilai" in s -> "rating"
            "pelanggaran" in s -> "fouls"
            "tekel" in s -> "tkw"
            "sundulan" in s -> "hdw"
            else -> null
        }
    }

    fun rows(toks: List<Tok>): List<List<Tok>> {
        if (toks.isEmpty()) return emptyList()
        val hs = toks.map { it.h }.sorted()
        val tol = max(10.0, hs[hs.size / 2] * 0.7)
        val out = mutableListOf<MutableList<Tok>>()
        for (t in toks.sortedBy { it.y }) {
            val r = out.lastOrNull()
            if (r != null && abs(t.y - r.first().y) <= tol) r.add(t) else out.add(mutableListOf(t))
        }
        return out.map { r -> r.sortedBy { it.x } }
    }

    fun classify(toks: List<Tok>): Kind {
        if (toks.isEmpty()) return Kind.UNKNOWN
        val txt = toks.joinToString(" ") { it.text.lowercase() }
        val hits = listOf("penguasaan", "nilai tim", "tembakan", "umpan sukses", "umpan dicoba", "tekel berhasil", "sundulan", "pelanggaran", "xg").count { it in txt }
        if (hits >= 2 || team(toks).size >= 4) return Kind.TEAM
        val hdr = listOf("paa", "pac", "taa", "hea", "cra", "sha", "kon").count { h -> toks.any { it.text.lowercase().startsWith(h) } }
        if (hdr >= 3 || players(toks).size >= 5) return Kind.PLAYER
        // Panel analisis / papan taktik sering memuat kata-kata ini
        val analysisHits = listOf("formasi", "penampilan terbaik", "negatif", "gaya", "pertahanan", "lini tengah").count { it in txt }
        if (analysisHits >= 2) return Kind.TEAM   // treat as useful capture, not pure UNKNOWN
        return Kind.UNKNOWN
    }

    /** [home, hg, ag, away] dari bar atas, mis. "FT CRY 2 3 NEW". */
    fun header(toks: List<Tok>): List<String>? {
        val line = rows(toks).take(2).joinToString(" ") { r -> r.joinToString(" ") { it.text } }
        val m = Regex("""([A-Z]{2,4})\s+(\d+)\D+(\d+)\s+([A-Z]{2,4})""").find(line) ?: return null
        return listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
    }

    /** Kiri = tuan rumah, kanan = tamu. */
    fun team(toks: List<Tok>): Map<String, Pair<Double, Double>> {
        val out = linkedMapOf<String, Pair<Double, Double>>()
        for (r in rows(toks)) {
            val d = ArrayList<Double>(); val s = ArrayList<String>(); var lb = ""
            for (t in r) {
                val n = num(t.text)
                if (n == null) lb = "$lb ${t.text}".trim()
                else { if (lb.isNotEmpty()) { s.add(lb); lb = "" }; d.add(n) }
            }
            if (lb.isNotEmpty()) s.add(lb)
            val k = s.map { keyFor(it) }
            when {
                s.size == 2 && d.size == 4 && k.all { it != null } -> { out[k[0]!!] = d[0] to d[1]; out[k[1]!!] = d[2] to d[3] }
                s.size == 1 && d.size == 2 && k[0] != null -> out[k[0]!!] = d[0] to d[1]
                d.size == 8 && k.none { it != null } -> { out["yc"] = d[0] to d[1]; out["rc"] = d[2] to d[3]; out["off"] = d[4] to d[5]; out["cor"] = d[6] to d[7] }
            }
        }
        return out
    }

    fun players(toks: List<Tok>): List<PlayerLine> {
        val statX = toks.firstOrNull { it.text.equals("PaA", true) }?.x?.minus(30)
        return rows(toks).mapNotNull { r ->
            val raw = r.first().text.uppercase().trim('.')
            val pos = if (raw.matches(Regex("S\\d+"))) "SUB" else raw
            if (pos != "SUB" && pos !in POS) return@mapNotNull null
            val rest = r.drop(1)
            val nameT = if (statX != null) rest.filter { it.x < statX } else rest.takeWhile { num(it.text) == null }
            val statT = if (statX != null) rest.filter { it.x >= statX } else rest.dropWhile { num(it.text) == null }
            var nums = statT.mapNotNull { numStat(it.text) }
            if (nums.size > 18) nums = nums.take(16) + nums.takeLast(2)   // ikon bola/kartu terbaca sebagai angka
            if (nums.size < 18) null
            else PlayerLine(pos, nameT.filter { t -> t.text.any { it.isLetter() } }.joinToString(" ") { it.text }, nums)
        }
    }
}
