package dev.semicolon.playseat.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 무드 추천 검증.
 *
 * 이 목록은 아무도 누르지 않으면 자동으로 큐에 들어갑니다. 비어 있거나
 * 엉뚱한 곡이 들어가면 사람이 다시는 이 기능을 켜지 않습니다.
 */
class RecommendTest {

    @Test
    fun `모든 무드가 곡을 돌려준다`() {
        listOf(
            Recommend.Id.RAIN, Recommend.Id.NIGHT, Recommend.Id.SUNNY,
            Recommend.Id.WINDING, Recommend.Id.LOUNGE, Recommend.Id.ARRIVING, Recommend.Id.CALM,
        ).forEach { mood ->
            val items = Recommend.forMood(mood, limit = 5)
            assertEquals(5, items.size, "$mood 가 5곡을 못 채웠습니다")
            assertTrue(items.all { it.title.isNotBlank() && it.artist.isNotBlank() })
            assertTrue(items.all { it.durationMs > 0 })
        }
    }

    @Test
    fun `목록에 적힌 번호가 전부 실재한다`() {
        // 번호가 틀리면 추천이 조용히 짧아집니다. 가장 알아채기 어려운 고장입니다.
        listOf(
            Recommend.Id.RAIN, Recommend.Id.NIGHT, Recommend.Id.SUNNY,
            Recommend.Id.WINDING, Recommend.Id.LOUNGE, Recommend.Id.ARRIVING, Recommend.Id.CALM,
        ).forEach { mood ->
            val got = Recommend.forMood(mood, limit = 10)
            assertTrue(got.size >= 5, "$mood: 실재하는 곡이 ${got.size}곡뿐입니다")
        }
    }

    @Test
    fun `모르는 무드는 기본 무드로 답한다`() {
        assertFalse(Recommend.known("HURRICANE"))
        assertTrue(Recommend.forMood("HURRICANE").isNotEmpty())
    }

    @Test
    fun `같은 곡을 두 번 권하지 않는다`() {
        val items = Recommend.forMood(Recommend.Id.RAIN, limit = 10)
        assertEquals(items.size, items.map { it.externalId }.distinct().size)
    }

    @Test
    fun `이미 들은 곡은 빼고 권한다`() {
        val first = Recommend.forMood(Recommend.Id.LOUNGE, limit = 5)
        val exclude = first.map { Recommend.normalize(it.title, it.artist) }.toSet()
        val second = Recommend.forMood(Recommend.Id.LOUNGE, limit = 5, exclude = exclude)
        assertTrue(second.none { Recommend.normalize(it.title, it.artist) in exclude })
    }

    @Test
    fun `아이가 타면 동요를 한 곡 섞는다`() {
        val items = Recommend.forMood(Recommend.Id.SUNNY, limit = 5, kidsAboard = true)
        assertTrue(items.any { it.artist == "핑크퐁" || it.artist == "뽀로로" })
        // 전부 동요로 채우지는 않습니다 — 어른도 같이 타고 있습니다.
        assertTrue(items.count { it.artist == "핑크퐁" || it.artist == "뽀로로" } <= 2)
    }

    @Test
    fun `동요는 맨 앞에 오지 않는다`() {
        // 첫 곡이 분위기를 읽은 결과여야 합니다. 뽀로로가 먼저 나오면
        // 분위기 판정이 아무 일도 안 한 것처럼 보입니다.
        repeat(20) {
            val items = Recommend.forMood(Recommend.Id.RAIN, limit = 5, kidsAboard = true)
            assertFalse(items.first().artist == "핑크퐁" || items.first().artist == "뽀로로")
        }
    }

    @Test
    fun `무드마다 곡 구성이 다르다`() {
        // 전부 같은 곡을 돌려주면 분위기를 읽는 의미가 없습니다.
        val rain = Recommend.forMood(Recommend.Id.RAIN, limit = 10).map { it.externalId }.toSet()
        val winding = Recommend.forMood(Recommend.Id.WINDING, limit = 10).map { it.externalId }.toSet()
        assertTrue(rain.intersect(winding).size <= 2)
    }

    @Test
    fun `굽잇길에는 동요를 권하지 않는다`() {
        val items = Recommend.forMood(Recommend.Id.WINDING, limit = 10)
        assertTrue(items.none { it.artist == "핑크퐁" || it.artist == "뽀로로" })
    }

    @Test
    fun `응답 본문에 필요한 필드가 다 있다`() {
        val items = Recommend.forMood(Recommend.Id.NIGHT, limit = 3)
        val body = Recommend.result(Recommend.Id.NIGHT, items).toString()
        assertTrue(body.contains("\"type\":\"mood.result\""))
        assertTrue(body.contains("\"mood\":\"NIGHT\""))
        assertTrue(body.contains("\"durationMs\""))
        assertTrue(body.contains("\"externalId\""))
    }

    @Test
    fun `limit 은 범위를 벗어나도 안전하다`() {
        assertEquals(1, Recommend.forMood(Recommend.Id.CALM, limit = 0).size)
        assertTrue(Recommend.forMood(Recommend.Id.CALM, limit = 999).size <= 10)
    }
}
