package com.fmcoach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    private fun row(y: Int, vararg s: String) = s.mapIndexed { i, t -> Tok(t, 50 + i * 60, y) }
    private val stats = listOf(47, 41, 4, 0, 1, 1, 2, 4, 3, 1, 0, 0, 0, 0, 2, 1, 85, 8)

    @Test fun playersWithHeaderAndStrayIcon() {
        val hdr = listOf(Tok("PaA", 415, 10), Tok("PaC", 475, 10))
        val r = listOf(Tok("MC", 110, 60), Tok("Tchouaméni", 290, 60)) +
            stats.take(16).mapIndexed { i, v -> Tok("$v", 415 + i * 60, 60) } +
            Tok("0", 1368, 60) + listOf(Tok("85", 1400, 60), Tok("8", 1460, 60))
        val p = StatsParser.players(hdr + r)
        assertEquals(1, p.size)
        assertEquals("Tchouaméni", p[0].name)
        assertEquals(85, p[0].kon)
        assertEquals(8, p[0].rating)
    }

    @Test fun playersWithoutHeaderSkipsDashSubs() {
        val t = row(10, *(listOf("MC", "Tchouaméni") + stats.map { "$it" }).toTypedArray()) +
            row(70, "S1", "Curtis", "-", "-")
        assertEquals(1, StatsParser.players(t).size)
    }

    @Test fun teamAndClassify() {
        val t = row(10, "1.49", "xG", "0.48", "2", "Umpan", "Silang", "Berhasil", "1") +
            row(100, "3", "2", "0", "0", "1", "0", "6", "3") +
            row(190, "36%", "Penguasaan", "64%")
        val m = StatsParser.team(t)
        assertEquals(1.49, m["xg"]!!.first, 0.001)
        assertEquals(1.0, m["crc"]!!.second, 0.001)
        assertEquals(6.0, m["cor"]!!.first, 0.001)
        assertEquals(Kind.TEAM, StatsParser.classify(t))
        assertEquals(Kind.UNKNOWN, StatsParser.classify(row(10, "Mulai", "Capture")))
    }

    @Test fun header() {
        assertEquals(listOf("CRY", "2", "3", "NEW"), StatsParser.header(row(5, "FT", "CRY", "2", "3", "NEW")))
    }

    @Test fun findingsFromOcrTextExtractsAnalysisPoints() {
        val messy = """
            GAYA Tiki-Taka Vertikal PENAMPILAN TERBAIK Bouhamdi, A NEGATIF PEMAIN DENGAN PENAMPILAN TE.. Woltemade, N
            FORMASI 4-1-2-2-1 Pertahanan tidak cukup kuat dalam melakukan tekel
            Pertahanan terlalu sering kalah dalam duel udara Lini tengah terlalu terbuka saat tidak menguasai bola
            Pergerakan terlalu sering dipatahkan di sepertiga akhir Para pemain sayap gagal mengawal pergerakan pemin lawan
        """.trimIndent()
        val f = Coach.findingsFromOcrText(messy)
        val titles = f.map { it.title }
        assertTrue(titles.any { it.contains("Pertahanan tidak cukup kuat") })
        assertTrue(titles.any { it.contains("Lini tengah terlalu terbuka") })
        assertTrue(titles.any { it.contains("Formasi: 4-1-2-2-1") })
        assertTrue(titles.any { it.contains("Gaya:") && it.contains("Tiki-Taka") })
        assertTrue(titles.any { it.contains("Penampilan terbaik") && it.contains("Bouhamdi") })
        assertTrue(titles.any { it.contains("Penampilan lemah") && it.contains("Woltemade") })
    }
}
