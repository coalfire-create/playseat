package dev.semicolon.playseat.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 검색 규칙 검증.
 *
 * 네트워크를 타는 Deezer 보완은 빼고, 판정에 해당하는 부분만 봅니다.
 * 좌석에서 목록을 눌러 바로 큐에 넣는 구조라 "엉뚱한 곡이 섞이지 않는 것"이
 * "많이 나오는 것"보다 중요합니다.
 */
class MusicSearchTest {

    // ---------- 관련도 ----------

    @Test
    fun `아티스트가 정확히 일치하면 곡명만 걸린 것보다 높다`() {
        val exact = Relevance.of("밤편지", "아이유", "아이유")
        val partial = Relevance.of("아이유를 위한 노래", "다른가수", "아이유")
        assertTrue(exact > partial)
    }

    @Test
    fun `검색어와 상관없는 곡은 0 이다`() {
        // 실제로 섞여 들어왔던 결과들입니다
        assertEquals(0, Relevance.of("Love Forgotten By Love", "에스나 (Esna)", "에스파"))
        assertEquals(0, Relevance.of("NAKKA (with IU)", "AKMU", "아이브"))
    }

    @Test
    fun `피처링으로만 걸린 곡은 그 가수의 곡으로 보지 않는다`() {
        assertEquals(0, Relevance.of("Ma Meilleure Ennemie ft. Coldplay", "Stromae", "coldplay"))
        assertEquals(0, Relevance.of("NAKKA (with IU)", "AKMU", "iu"))
    }

    @Test
    fun `짧은 약어는 아티스트가 정확히 일치할 때만 걸린다`() {
        assertTrue(Relevance.of("Love Dive", "IVE", "아이브") > 0)
        // 'Oliver' 안에 'ive' 가 들어 있다는 이유로 걸리면 안 됩니다
        assertEquals(0, Relevance.of("Lost", "Oliver Tree", "아이브"))
    }

    @Test
    fun `띄어쓰기가 달라도 같은 곡으로 본다`() {
        // 한글 띄어쓰기는 사람마다 다릅니다
        assertTrue(Catalog.search("곰 세마리", 3).any { it.title == "곰 세 마리" })
        assertTrue(Catalog.search("곰세마리", 3).any { it.title == "곰 세 마리" })
        assertTrue(Catalog.search("버스커버스커", 3).isNotEmpty())
    }

    @Test
    fun `빈 검색어는 0 이다`() {
        assertEquals(0, Relevance.of("밤편지", "아이유", "   "))
    }

    // ---------- 표기 사전 ----------

    @Test
    fun `영문 표기로 한글 아티스트를 찾는다`() {
        assertTrue(Relevance.of("봄날", "방탄소년단", "BTS") > 0)
        assertTrue(Relevance.of("Hype Boy", "뉴진스", "newjeans") > 0)
    }

    @Test
    fun `한글 표기로도 그대로 찾는다`() {
        assertTrue(Relevance.of("봄날", "방탄소년단", "방탄소년단") > 0)
    }

    @Test
    fun `사전에 없는 검색어는 자기 자신만 쓴다`() {
        assertEquals(setOf("coldplay"), ArtistAlias.expand("coldplay"))
    }

    @Test
    fun `해외 서비스용 영문 표기를 돌려준다`() {
        assertEquals("seventeen", ArtistAlias.foreignForm("세븐틴"))
        assertEquals("bts", ArtistAlias.foreignForm("방탄소년단"))
    }

    @Test
    fun `영문 표기가 없는 아티스트는 null 이다`() {
        assertEquals(null, ArtistAlias.foreignForm("없는가수"))
    }

    // ---------- 큐레이션 목록 ----------

    @Test
    fun `목록에서 아티스트로 찾으면 그 아티스트 곡만 나온다`() {
        val hits = Catalog.search("세븐틴", 5)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.artist == "세븐틴" })
    }

    @Test
    fun `BTS 로 검색해도 방탄소년단 곡이 나온다`() {
        val hits = Catalog.search("BTS", 5)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.artist == "방탄소년단" })
    }

    @Test
    fun `심사에서 쳐 볼 만한 아티스트는 목록에 전부 있다`() {
        val must = listOf(
            "아이유", "방탄소년단", "뉴진스", "아이브", "에스파", "블랙핑크",
            "르세라핌", "세븐틴", "트와이스", "스트레이키즈", "레드벨벳",
            "임영웅", "성시경", "핑크퐁",
        )
        val missing = must.filter { Catalog.search(it, 1).isEmpty() }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `아동 좌석 시연용 짧은 동요가 있다`() {
        val short = Catalog.search("핑크퐁", 10).filter { it.durationMs <= 120_000 }
        assertTrue(short.isNotEmpty())
    }

    @Test
    fun `목록에 없는 검색어는 빈 결과를 준다`() {
        assertEquals(emptyList(), Catalog.search("qqqzzz존재하지않는검색어", 5))
    }

    @Test
    fun `검색어가 비면 목록 앞에서부터 돌려준다`() {
        assertEquals(3, Catalog.search("  ", 3).size)
    }

    @Test
    fun `곡 식별자는 겹치지 않는다`() {
        val all = Catalog.all(1000)
        assertEquals(all.size, all.map { it.externalId }.toSet().size)
    }
}
