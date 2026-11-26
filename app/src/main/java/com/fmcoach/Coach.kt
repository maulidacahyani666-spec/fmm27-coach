package com.fmcoach

data class Finding(val good: Boolean, val title: String, val cause: String, val fix: String = "")

/** Asisten pelatih berbasis aturan: Temuan -> Penyebab (dari data) -> Solusi. */
object Coach {
    private val DEF = setOf("DR", "DL", "DC", "WBR", "WBL")
    private val MID = setOf("DMC", "MC", "MR", "ML", "AMC", "AMR", "AML")
    private fun f2(d: Double) = "%.2f".format(d)
    private fun idx(m: Match, club: String) = when {
        m.home.equals(club, true) -> 0
        m.away.equals(club, true) -> 1
        else -> -1
    }
    private fun v(m: Match, k: String, i: Int): Double? = m.team[k]?.let { if (i == 0) it.first else it.second }

    fun report(m: Match, club: String): List<Finding> {
        val i = idx(m, club)
        if (i < 0) return listOf(
            Finding(false, "Kode klub \"$club\" tidak cocok", "Match ini: ${m.home} vs ${m.away}",
                "Isi kode klub Anda persis seperti di bar skor game.")
        )
        val j = 1 - i
        val out = mutableListOf<Finding>()
        val xg = v(m, "xg", i); val oxg = v(m, "xg", j); val shots = v(m, "shots", i); val poss = v(m, "poss", i)
        val cra = v(m, "cra", i); val crc = v(m, "crc", i)
        val won = if (i == 0) m.hg > m.ag else m.ag > m.hg

        if (xg != null && oxg != null) {
            if (xg < oxg * 0.6) out += Finding(false, "Kualitas peluang kalah dari lawan",
                "xG ${f2(xg)} vs ${f2(oxg)}" + if (won) " (menang, tapi sulit diulang)" else "",
                "Kurangi tembakan jarak jauh, minta masuk lebih sabar ke kotak penalti, dan rapikan transisi bertahan.")
            else if (xg > oxg * 1.5) out += Finding(true, "Peluang lebih berkualitas dari lawan", "xG ${f2(xg)} vs ${f2(oxg)}")
        }
        if (poss != null && xg != null && poss >= 55 && xg < 0.8)
            out += Finding(false, "Menguasai bola tapi tidak menembus",
                "Penguasaan ${poss.toInt()}% dengan xG hanya ${f2(xg)}",
                "Naikkan tempo, umpan lebih langsung ke depan, dan tambah overlap/underlap di sayap.")
        if (shots != null && xg != null && shots >= 10 && xg / shots < 0.06)
            out += Finding(false, "Banyak tembakan bernilai rendah",
                "${shots.toInt()} tembakan, rata-rata ${f2(xg / shots)} xG",
                "Kurangi 'Shoot on Sight', buat tembakan dari area lebih dekat gawang.")
        if (cra != null && crc != null && cra >= 8 && crc / cra < 0.25)
            out += Finding(false, "Umpan silang tidak efektif",
                "${crc.toInt()} dari ${cra.toInt()} berhasil",
                "Kurangi frekuensi crossing, ubah winger jadi inside forward, atau pasang target man bila memang mau main sayap.")

        for (p in m.players) {
            val acc = p.passAcc
            val rating = p.rating
            val cond = p.kon
            val key = p.key
            val ass = p.ass

            if (p.pos in DEF && p.v[0] >= 25 && acc < 75) {
                out += Finding(false, "${p.name} (${p.pos}) sering kehilangan bola",
                    "Akurasi umpan ${acc.toInt()}% dari ${p.v[0]} umpan",
                    "Turunkan risk passing / pakai umpan lebih pendek, atau ganti dengan bek yang Passing & Composure lebih tinggi. Alternatif peran: Ball Playing Defender hanya jika akurasi stabil di atas 80%.")
            }
            if (rating > 0 && rating <= 6 && cond >= 85) {
                out += Finding(false, "${p.name} rating rendah meski kondisi baik",
                    "Rating $rating, kondisi $cond%",
                    "Pertimbangkan ganti atau pindah posisi. Saat ini di ${p.pos}; coba peran yang lebih sederhana atau posisi yang sesuai atribut utamanya.")
            }
            if (p.pos in MID && p.v[0] >= 30 && acc < 78 && key + ass == 0) {
                out += Finding(false, "${p.name} (${p.pos}) tidak efektif di lini tengah",
                    "Akurasi ${acc.toInt()}%, 0 umpan kunci/assist",
                    "Ubah peran menjadi lebih defensif (mis. Anchor/Defensive Midfielder) atau ganti dengan pemain yang lebih kreatif bila butuh progresi bola.")
            }
            if (key >= 4 || (rating >= 8 && ass + p.v.getOrElse(15) { 0 } > 0)) {
                out += Finding(true, "${p.name} tampil menonjol",
                    "$key umpan kunci, $ass assist, rating $rating")
            }
            if (cond < 82) {
                out += Finding(false, "${p.name} kondisi rendah ($cond%)", "",
                    "Pertimbangkan rotasi di match berikutnya.")
            }
        }

        if (out.isEmpty()) out += Finding(true, "Tidak ada temuan menonjol", "Data match terbaca, tidak ada aturan yang terpicu.")
        return out
    }

    /** Pola yang hanya terlihat jika riwayat beberapa match digabung. */
    fun patterns(ms: List<Match>, club: String): List<String> {
        val mine = ms.filter { idx(it, club) >= 0 }
        if (mine.size < 2) return emptyList()
        val out = mutableListOf<String>()
        var att = 0.0; var ok = 0.0
        mine.forEach { m -> val i = idx(m, club); att += v(m, "cra", i) ?: 0.0; ok += v(m, "crc", i) ?: 0.0 }
        if (att >= 15 && ok / att < 0.25)
            out += "Umpan silang hanya ${(100 * ok / att).toInt()}% berhasil dalam ${mine.size} match. Ganti pola serangan sayap."
        val low = mine.count { m ->
            val i = idx(m, club); val a = v(m, "xg", i); val b = v(m, "xg", 1 - i)
            a != null && b != null && a < b
        }
        if (low >= mine.size * 0.6)
            out += "xG Anda lebih rendah dari lawan di $low dari ${mine.size} match. Masalahnya struktural, bukan nasib."
        return out
    }

    /** Ekstrak temuan dari teks OCR panel analisis / papan taktik (format bebas). */
    fun findingsFromOcrText(text: String): List<Finding> {
        val out = mutableListOf<Finding>()
        val normalized = text.replace(Regex("\\s+"), " ").trim()

        val negatives = listOf(
            Regex("Pertahanan tidak cukup kuat dalam melakukan tekel", RegexOption.IGNORE_CASE),
            Regex("Pertahanan terlalu sering kalah dalam duel udara", RegexOption.IGNORE_CASE),
            Regex("Lini tengah terlalu terbuka saat tidak menguasai bola", RegexOption.IGNORE_CASE),
            Regex("Pergerakan terlalu sering dipatahkan di sepertiga akhir", RegexOption.IGNORE_CASE),
            Regex("Para pemain sayap gagal mengawal pergerakan (pemin|pemain) lawan", RegexOption.IGNORE_CASE),
            Regex("Gagal menciptakan peluang berkualitas", RegexOption.IGNORE_CASE),
            Regex("Terlalu banyak kehilangan bola di area berbahaya", RegexOption.IGNORE_CASE),
        )
        for (re in negatives) {
            re.find(normalized)?.let {
                out += Finding(false, it.value, "Terdeteksi dari panel analisis pertandingan")
            }
        }

        Regex("""PENAMPILAN TERBAIK\s+([A-Za-zÀ-ž][A-Za-zÀ-ž'’.\-, ]{1,40}?)(?=\s+NEGATIF|\s+FORMASI|\s+Pertahanan|\s*$)""", RegexOption.IGNORE_CASE)
            .find(normalized)?.let {
                out += Finding(true, "Penampilan terbaik: ${it.groupValues[1].trim()}", "Label dari panel analisis")
            }

        Regex("""(?:NEGATIF(?:\s+PEMAIN\s+DENGAN\s+PENAMPILAN\s+TE\.\.)?)\s+([A-Za-zÀ-ž][A-Za-zÀ-ž'’.\-, ]{1,40}?)(?=\s+FORMASI|\s+Pertahanan|\s*$)""", RegexOption.IGNORE_CASE)
            .find(normalized)?.let {
                out += Finding(false, "Penampilan lemah: ${it.groupValues[1].trim()}", "Label dari panel analisis",
                    "Pertimbangkan ganti atau ubah peran di match berikutnya.")
            }

        Regex("""FORMASI\s+(\d(?:-\d)+)""", RegexOption.IGNORE_CASE).find(normalized)?.let {
            out += Finding(true, "Formasi: ${it.groupValues[1]}", "Terdeteksi dari panel analisis")
        }

        Regex("""GAYA\s+([A-Za-zÀ-ž\- ]+?)(?:\s+PENAMPILAN|\s+FORMASI|\s*$)""", RegexOption.IGNORE_CASE).find(normalized)?.let {
            out += Finding(true, "Gaya: ${it.groupValues[1].trim()}", "Terdeteksi dari panel analisis")
        }

        // rating pairs
        val re = Regex("""([A-Za-zÀ-ž'’.\-]+(?:,\s*[A-Z])?)\s*(?:\([^)]+\))?\s+(\d(?:\.\d)?)\b""")
        val seen = mutableMapOf<String, Double>()
        for (m in re.findAll(normalized)) {
            val name = m.groupValues[1].trim()
            val rating = m.groupValues[2].toDoubleOrNull() ?: continue
            if (rating in 4.0..10.0 && name.length > 2 &&
                !name.matches(Regex("""^(FT|NEW|Leeds|GAYA|FORMASI|NEGATIF|PENAMPILAN|PERGANTIAN)""", RegexOption.IGNORE_CASE))
            ) {
                val prev = seen[name]
                if (prev == null || rating > prev) seen[name] = rating
            }
        }
        for ((name, rating) in seen) {
            when {
                rating <= 6.2 -> out += Finding(false, "$name rating rendah ($rating)", "Terdeteksi dari OCR panel",
                    "Pertimbangkan ganti atau ubah peran.")
                rating >= 8.0 -> out += Finding(true, "$name tampil baik (rating $rating)", "Terdeteksi dari OCR panel")
            }
        }

        if (out.isEmpty()) {
            out += Finding(false, "Tidak ada temuan yang bisa diekstrak",
                "Teks OCR tidak mengandung pola analisis atau rating yang dikenali",
                "Coba capture halaman statistik tim / pemain, atau pastikan panel analisis terlihat jelas.")
        }
        return out
    }
}
