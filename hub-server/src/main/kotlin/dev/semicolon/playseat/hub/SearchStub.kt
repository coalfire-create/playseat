package dev.semicolon.playseat.hub

import kotlinx.serialization.json.*

/**
 * 곡 검색 스텁.
 *
 * 전체 루프(폰에서 검색 → 큐 추가 → 차량 재생)를 외부 API 없이 돌리기 위한 임시 구현이다.
 * 실제 구현에서는 스트리밍 사업자 메타데이터 검색으로 교체한다.
 * 차량 앱은 검색을 모르고, `queue.add` 로 넘어온 title/artist 만 해석한다.
 */
object SearchStub {

    private class Track(val title: String, val artist: String, val durationMs: Int)

    private val catalog = listOf(
        Track("밤편지", "아이유", 254_000),
        Track("좋은 날", "아이유", 227_000),
        Track("Love Dive", "IVE", 177_000),
        Track("I AM", "IVE", 189_000),
        Track("Hype Boy", "NewJeans", 180_000),
        Track("Ditto", "NewJeans", 185_000),
        Track("바람기 메모리", "이소라", 272_000),
        Track("Track 9", "이소라", 243_000),
        Track("벚꽃 엔딩", "버스커 버스커", 262_000),
        Track("여행", "볼빨간사춘기", 215_000),
        Track("아기상어", "핑크퐁", 96_000),
        Track("상어가족", "핑크퐁", 134_000),
        Track("Dynamite", "BTS", 199_000),
        Track("Spring Day", "BTS", 275_000),
        Track("사건의 지평선", "윤하", 321_000),
        Track("비 오는 날", "검정치마", 254_000),
        Track("Antifragile", "LE SSERAFIM", 184_000),
        Track("Drama", "aespa", 185_000),
        Track("첫눈처럼 너에게 가겠다", "에일리", 268_000),
        Track("가을 아침", "아이유", 220_000),
    )

    /** `search.query` 에 대한 `search.result` 본문을 만든다. */
    fun query(q: String, limit: Int): JsonObject {
        val needle = q.trim()
        val hits = if (needle.isEmpty()) {
            catalog
        } else {
            catalog.filter {
                it.title.contains(needle, ignoreCase = true) ||
                    it.artist.contains(needle, ignoreCase = true)
            }
        }.take(limit.coerceIn(1, 50))

        return buildJsonObject {
            put("type", MsgType.SEARCH_RESULT)
            put("ts", System.currentTimeMillis())
            put("query", needle)
            putJsonArray("items") {
                hits.forEachIndexed { i, t ->
                    addJsonObject {
                        put("title", t.title)
                        put("artist", t.artist)
                        put("durationMs", t.durationMs)
                        put("externalId", "stub_${i}_${t.title.hashCode()}")
                    }
                }
            }
        }
    }
}
