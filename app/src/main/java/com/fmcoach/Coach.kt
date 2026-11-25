package com.fmcoach

data class Finding(val temuan: String, val penyebab: String, val solusi: String)
data class Report(val match: MatchData, val findings: List<Finding>, val patterns: List<Finding>)

object Coach {
    fun report(match: MatchData, allMatches: List<MatchData> = emptyList()): Report {
        val f = mutableListOf<Finding>()
        val h = match.home
        val a = match.away

        val hxg = h.xg ?: 0.0
        val axg = a.xg ?: 0.0
        val hposs = h.poss ?: 0.0
        val hshots = h.shots ?: 0

        if (axg > 0 && hxg < 0.6 * axg) f += Finding(
            "Kualitas peluang kalah dari lawan",
            "xG ${fmt(hxg)} vs ${fmt(axg)}",
            "Latih pola serangan; cari peluang di dalam kotak, bukan tembakan jauh."
        )
        if (axg > 0 && hxg > 1.5 * axg) f += Finding(
            "Peluang lebih berkualitas",
            "xG ${fmt(hxg)} vs ${fmt(axg)}",
            "Pertahankan pola; fokus pada finishing untuk konversi."
        )
        if (hposs >= 55.0 && hxg < 0.8) f += Finding(
            "Menguasai bola tapi tidak menembus",
            "Penguasaan ${fmt(hposs)}% dengan xG ${fmt(hxg)}",
            "Kurangi risk passing, ubah role sayap, tambah gelandang bertahan sebagai opsi umpan."
        )
        if (hshots >= 10 && hxg > 0) {
            val per = hxg / hshots
            if (per < 0.06) f += Finding(
                "Banyak tembakan bernilai rendah",
                "$hshots tembakan, xG/tembakan ${fmt(per, 3)}",
                "Kurangi tembakan jauh; latih movement di kotak."
            )
        }
        val cra = h.cra ?: 0
        val crc = h.crc ?: 0
        if (cra >= 8 && crc.toDouble() / cra < 0.25) f += Finding(
            "Umpan silang tidak efektif",
            "$crc sukses dari $cra percobaan",
            "Ubah role sayap; tambah gelandang bertahan sebagai opsi umpan."
        )

        match.players.filter { it.pos.uppercase().startsWith("D") }.forEach { p ->
            val attempts = p.paA ?: 0.0
            val completed = p.paC ?: 0.0
            if (attempts >= 25 && completed / attempts < 0.75) {
                f += Finding(
                    "Bek sering kehilangan bola: ${p.name}",
                    "${completed.toInt()}/${attempts.toInt()} umpan (${(100 * completed / attempts).toInt()}%)",
                    "Kurangi risk passing untuk bek ini; opsi umpan lebih aman."
                )
            }
        }

        match.players.forEach { p ->
            val lost = p.dispossessed ?: 0.0
            if (lost >= 5) {
                f += Finding(
                    "Sering kehilangan bola: ${p.name}",
                    "Kehilangan bola ${lost.toInt()} kali",
                    "Minta pemain ini mengurangi dribel berisiko atau mainkan umpan cepat."
                )
            }
            
            if ((p.key ?: 0.0) >= 4 || ((p.rating ?: 0.0) >= 8.0 && ((p.ass ?: 0.0) > 0 || (p.shO ?: 0.0) > 0))) {
                f += Finding(
                    "Pemain tampil menonjol: ${p.name}",
                    "Rating ${fmt(p.rating ?: 0.0)}, key ${p.key ?: 0.0}, assist ${p.ass ?: 0.0}",
                    "Pertahankan peran; beri kebebasan lebih."
                )
            }
            val kon = p.kon ?: 100.0
            if (kon < 82.0) f += Finding(
                "Kondisi rendah: ${p.name}",
                "Kondisi ${fmt(kon)}%",
                "Pertimbangkan rotasi pada laga berikutnya."
            )
        }

        val patterns = crossPatterns(allMatches)
        return Report(match, f, patterns)
    }

    fun crossPatterns(matches: List<MatchData>): List<Finding> {
        if (matches.size < 2) return emptyList()
        val out = mutableListOf<Finding>()

        var crAttempts = 0; var crDone = 0
        matches.forEach { m ->
            crAttempts += m.home.cra ?: 0
            crDone += m.home.crc ?: 0
        }
        if (crAttempts >= 15 && crDone.toDouble() / crAttempts < 0.25) {
            out += Finding(
                "Pola lintas match: umpan silang kronis",
                "Agregat $crDone/$crAttempts sukses (${(100.0 * crDone / crAttempts).toInt()}%) dari ${matches.size} laga",
                "Ubah pendekatan sayap secara permanen; latih crossing."
            )
        }

        val xgLower = matches.count { (it.home.xg ?: 0.0) < (it.away.xg ?: 0.0) }
        if (xgLower.toDouble() / matches.size >= 0.6) {
            out += Finding(
                "Pola lintas match: kualitas peluang konsisten kalah",
                "xG lebih rendah dari lawan pada $xgLower dari ${matches.size} laga",
                "Tinjau ulang taktik ofensif dan peran penyerang."
            )
        }
        return out
    }

    private fun fmt(v: Double, digits: Int = 2) =
        String.format(java.util.Locale.US, "%.${digits}f", v)
}
