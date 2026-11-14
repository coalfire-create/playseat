package dev.semicolon.playseat.hub

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 음원 중계 검증.
 *
 * 두 가지가 걸려 있습니다.
 *   1. 호스트 허용 목록 — 뚫리면 허브가 아무 주소나 대신 받아 주는 공개 프록시가
 *      됩니다. 토큰은 서명이 아니라 주소를 담고 있을 뿐이어서 누구든 만들 수 있고,
 *      막는 것은 이 검사뿐입니다. 그래서 '통과해야 하는 것' 보다
 *      '막혀야 하는 것' 을 더 많이 적어 둡니다.
 *   2. 검색·추천 결과에 미리듣기 필드가 실리는지 — 안 실리면 차량은 조용히
 *      시뮬레이션으로 떨어지고, 아무 오류 없이 그냥 소리가 안 납니다.
 *      가장 알아채기 어려운 고장이라 테스트로 묶어 둡니다.
 */
class AudioRelayTest {

    private val realPreview =
        "https://cdnt-preview.dzcdn.net/api/1/1/8/e/0/0/8e0abc.mp3?hdnea=exp=1763000000~acl=/api/1/1/8/e/0/0/8e0abc.mp3*"

    // ---------- 토큰 ----------

    @Test
    fun `토큰을 다시 풀면 같은 주소가 나온다`() {
        // 미리듣기 주소에는 쿼리 문자열과 `*` 가 붙어 있습니다. 그대로 넣으면
        // /audio 의 파라미터와 섞여서, 인코딩을 거치는 이유가 바로 이것입니다.
        assertEquals(realPreview, AudioRelay.decodeToken(AudioRelay.encodeToken(realPreview)))
    }

    @Test
    fun `토큰에 base64 패딩이나 URL 예약문자가 남지 않는다`() {
        val token = AudioRelay.encodeToken(realPreview)
        assertFalse(token.contains('='), "패딩이 남으면 쿼리에서 다시 인코딩해야 합니다")
        assertFalse(token.contains('+'))
        assertFalse(token.contains('/'))
    }

    @Test
    fun `깨진 토큰은 null 이다`() {
        assertNull(AudioRelay.decodeToken("!!!이건base64가아닙니다!!!"))
        assertNull(AudioRelay.decodeToken(""))
    }

    // ---------- 호스트 허용 목록 ----------

    @Test
    fun `Deezer 미리듣기 CDN 은 통과한다`() {
        assertTrue(AudioRelay.isAllowed(realPreview))
        assertTrue(AudioRelay.isAllowed("http://cdns-preview-9.dzcdn.net/stream/c-9abc.mp3"))
        assertTrue(AudioRelay.isAllowed("https://dzcdn.net/x.mp3"))
    }

    @Test
    fun `허용 목록 밖 호스트는 거절한다`() {
        // 이 검사가 없으면 허브가 SSRF 대리인이 됩니다. 사내망·메타데이터 주소까지
        // 허브 이름으로 긁히기 때문에, 외부 도메인만의 문제가 아닙니다.
        assertFalse(AudioRelay.isAllowed("https://evil.example/payload.mp3"))
        assertFalse(AudioRelay.isAllowed("http://localhost:8080/stats"))
        assertFalse(AudioRelay.isAllowed("http://127.0.0.1/health"))
        assertFalse(AudioRelay.isAllowed("http://169.254.169.254/latest/meta-data/"))
        assertFalse(AudioRelay.isAllowed("http://10.0.0.5/internal"))
    }

    @Test
    fun `도메인 이름이 비슷한 것만으로는 통과하지 못한다`() {
        // 접미사 비교를 문자열 contains 로 하면 전부 뚫립니다.
        assertFalse(AudioRelay.isAllowed("https://notdzcdn.net/x.mp3"))
        assertFalse(AudioRelay.isAllowed("https://dzcdn.net.evil.example/x.mp3"))
        assertFalse(AudioRelay.isAllowed("https://evil.example/?x=dzcdn.net"))
        assertFalse(AudioRelay.isAllowed("https://evil.example/dzcdn.net/x.mp3"))
    }

    @Test
    fun `사용자 정보로 호스트를 가린 주소는 거절한다`() {
        // 사람 눈에는 dzcdn.net 으로 보이지만 실제 호스트는 뒤쪽입니다.
        assertFalse(AudioRelay.isAllowed("https://cdn.dzcdn.net@evil.example/x.mp3"))
    }

    @Test
    fun `http 와 https 외의 스킴은 거절한다`() {
        assertFalse(AudioRelay.isAllowed("file:///etc/passwd"))
        assertFalse(AudioRelay.isAllowed("ftp://cdn.dzcdn.net/x.mp3"))
        assertFalse(AudioRelay.isAllowed("주소가 아닙니다"))
        assertFalse(AudioRelay.isAllowed(""))
    }

    // ---------- 중계 경로 ----------

    @Test
    fun `중계 경로는 허브 호스트 없는 경로 문자열이다`() {
        val path = assertNotNull(AudioRelay.relayPath(realPreview))
        // 차량 앱이 "http://" + BuildConfig.HUB_HOST 를 앞에 붙입니다.
        // 허브가 자기 주소를 몰라도 되게 하는 것이 목적이라, 절대 주소면 안 됩니다.
        assertTrue(path.startsWith("/audio?t="), "실제: $path")
        assertEquals(realPreview, AudioRelay.decodeToken(path.removePrefix("/audio?t=")))
    }

    @Test
    fun `허용 목록 밖 주소는 경로를 만들어 주지 않는다`() {
        // 받는 쪽에서 400 이 날 주소를 결과에 실어 보내면, 차량은 재생을 시도했다
        // 실패합니다. 어차피 못 들려줄 것이면 없다고 말하는 편이 정확합니다.
        assertNull(AudioRelay.relayPath("https://evil.example/x.mp3"))
        assertNull(AudioRelay.relayPath(null))
        assertNull(AudioRelay.relayPath("   "))
    }

    @Test
    fun `곡 번호가 있으면 토큰에 주소 대신 번호를 담는다`() {
        // 미리듣기 주소는 발급 900초 뒤에 죽습니다. 검색할 때 받은 주소를 토큰에
        // 그대로 담아 두면 시연 15분째에 소리만 조용히 끊깁니다.
        val path = assertNotNull(AudioRelay.relayPath(realPreview, "deezer:3135556"))
        assertEquals("deezer:3135556", AudioRelay.decodeToken(path.removePrefix("/audio?t=")))
    }

    @Test
    fun `곡 번호가 숫자가 아니면 주소를 담는다`() {
        // catalog:12 처럼 Deezer 번호가 아닌 참조로는 다시 조회할 수 없습니다.
        val path = assertNotNull(AudioRelay.relayPath(realPreview, "catalog:12"))
        assertEquals(realPreview, AudioRelay.decodeToken(path.removePrefix("/audio?t=")))
    }

    @Test
    fun `미리듣기가 없으면 곡 번호가 있어도 경로를 만들지 않는다`() {
        // 번호만으로는 그 곡에 미리듣기가 있는지 알 수 없습니다. 있다는 증거는
        // 주소뿐이어서, 주소가 없으면 없다고 말합니다.
        assertNull(AudioRelay.relayPath(null, "deezer:3135556"))
    }

    @Test
    fun `곡 번호만 꺼낸다`() {
        assertEquals("3135556", AudioRelay.trackIdOf("deezer:3135556"))
        assertNull(AudioRelay.trackIdOf("deezer:"))
        assertNull(AudioRelay.trackIdOf("deezer:12a"))
        // 번호 자리에 경로를 끼워 넣어 조회 주소를 비틀 수 없어야 합니다.
        assertNull(AudioRelay.trackIdOf("deezer:../../search?q=x"))
    }

    // ---------- 검색·추천 결과 JSON ----------

    private fun itemsOf(json: kotlinx.serialization.json.JsonObject) =
        json["items"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `검색 결과에 중계 경로와 재생 길이가 실린다`() {
        val item = MusicSearch.Item(
            title = "밤편지", artist = "아이유", durationMs = 254_000,
            externalId = "deezer:1", previewUrl = realPreview,
        )
        val got = itemsOf(MusicSearch.result("아이유", listOf(item), "deezer")).single()
        assertEquals(
            "/audio?t=${AudioRelay.encodeToken(realPreview)}",
            got["previewUrl"]!!.jsonPrimitive.content,
        )
        // 미리듣기는 30초입니다. durationMs 는 곡의 원래 길이로 그대로 둡니다 —
        // 화면에는 곡 길이를 보여 주고, 큐 계산은 실제로 흐를 시간으로 해야 맞습니다.
        assertEquals(AudioRelay.PREVIEW_MS, got["playableMs"]!!.jsonPrimitive.content.toInt())
        assertEquals(254_000, got["durationMs"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `미리듣기가 없으면 두 필드를 아예 빼 둔다`() {
        val item = MusicSearch.Item("밤편지", "아이유", 254_000, "catalog:1")
        val got = itemsOf(MusicSearch.result("아이유", listOf(item), "catalog")).single()
        // 빈 문자열이 실리면 차량이 그 주소로 재생을 시도합니다. 없는 게 분명합니다.
        assertNull(got["previewUrl"])
        assertNull(got["playableMs"])
    }

    @Test
    fun `허용 목록 밖 음원은 결과에서 조용히 빠진다`() {
        val item = MusicSearch.Item(
            "밤편지", "아이유", 254_000, "deezer:1",
            previewUrl = "https://evil.example/payload.mp3",
        )
        val got = itemsOf(MusicSearch.result("아이유", listOf(item), "deezer")).single()
        assertNull(got["previewUrl"])
        assertNull(got["playableMs"])
    }

    @Test
    fun `분위기 추천에도 같은 두 필드가 실린다`() {
        // 추천으로 담긴 곡만 소리가 안 나면 아무도 원인을 못 찾습니다.
        val item = MusicSearch.Item(
            "비 오는 날", "검정치마", 254_000, "catalog:10",
            previewUrl = realPreview,
        )
        val got = itemsOf(Recommend.result(Recommend.Id.RAIN, listOf(item))).single()
        assertTrue(got["previewUrl"]!!.jsonPrimitive.content.startsWith("/audio?t="))
        assertEquals(AudioRelay.PREVIEW_MS, got["playableMs"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `검색 결과와 추천 결과의 곡 모양이 같다`() {
        // 폰 웹앱이 두 목록을 같은 코드로 다룹니다. 한쪽에만 필드가 늘면
        // 그 목록에서 담은 곡만 조용히 깨집니다.
        val item = MusicSearch.Item(
            "밤편지", "아이유", 254_000, "catalog:1",
            artworkUrl = "https://e-cdn-images.dzcdn.net/images/cover/x/264x264-000000-80-0-0.jpg",
            previewUrl = realPreview,
        )
        val fromSearch = itemsOf(MusicSearch.result("아이유", listOf(item), "catalog")).single()
        val fromMood = itemsOf(Recommend.result(Recommend.Id.CALM, listOf(item))).single()
        assertEquals(fromSearch.keys, fromMood.keys)
        assertEquals(fromSearch, fromMood)
    }
}
