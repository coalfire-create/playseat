package dev.semicolon.playseat.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 무드별 추천.
 *
 * 차가 "지금 분위기"를 판정해서 보내면(`mood.query`), 허브는 그 무드에 맞는
 * 곡을 몇 개 돌려줍니다. 판정은 차가, 선곡은 허브가 — 계약의 역할 분담 그대로입니다.
 *
 * 왜 외부 추천 API 를 쓰지 않는가
 *   차 안에서 쓰는 추천은 "틀어도 되는 곡" 이어야 합니다. 아이가 타고 있는데
 *   욕설이 섞인 곡이 자동으로 들어가면 그 기능은 다시 안 켭니다. 큐레이션 목록은
 *   74곡뿐이지만 전부 사람이 확인한 곡이고, 자동으로 큐에 넣어도 안전합니다.
 *   외부 추천은 2차 심사 이후에 붙여도 늦지 않습니다.
 *
 * 왜 장르가 아니라 무드인가
 *   같은 발라드라도 비 오는 날과 충전 중 라운지는 고르는 곡이 다릅니다.
 *   차가 아는 것은 장르 취향이 아니라 상황(비·밤·굽잇길·도착 임박)이므로,
 *   차가 실제로 아는 축으로 나눕니다.
 */
object Recommend {

    /** 차량 [dev.semicolon.playseat.car.mood.Mood] 와 같은 문자열을 씁니다. */
    object Id {
        const val RAIN = "RAIN"
        const val NIGHT = "NIGHT"
        const val SUNNY = "SUNNY"
        const val WINDING = "WINDING"
        const val LOUNGE = "LOUNGE"
        const val ARRIVING = "ARRIVING"
        const val CALM = "CALM"
    }

    /**
     * 무드별 곡 번호. 번호는 [Catalog] 의 `catalog:<n>` 입니다.
     *
     * 곡을 직접 적는 대신 번호를 쓰는 이유는, 목록에 곡을 추가할 때
     * 제목 오타로 추천이 조용히 비는 일을 막기 위해서입니다.
     * 번호가 없으면 [Catalog.byIds] 가 그냥 건너뜁니다.
     */
    private val byMood: Map<String, List<Int>> = mapOf(
        // 비. 와이퍼가 움직이는 동안. 템포를 낮추고 목소리가 앞에 있는 곡.
        Id.RAIN to listOf(10, 5, 1, 18, 6, 16, 8, 24, 7, 20, 27, 86),

        // 밤. 야간 주행. 어둡고 공간이 넓게 들리는 곡.
        Id.NIGHT to listOf(1, 24, 82, 8, 11, 28, 22, 40, 39, 18, 83, 57),

        // 맑은 낮. 밝고 빠른 곡.
        Id.SUNNY to listOf(2, 13, 14, 33, 49, 3, 32, 43, 41, 84, 80, 15),

        // 굽잇길. 조향이 계속 바뀌는 구간. 박자가 뚜렷한 곡.
        Id.WINDING to listOf(37, 55, 44, 47, 38, 56, 45, 31, 50, 89, 82, 46),

        // 충전 라운지. 정차해 있고 시간이 있는 상태. 길어도 되는 곡.
        Id.LOUNGE to listOf(3, 21, 17, 59, 86, 81, 23, 4, 58, 88),

        // 도착 임박. 한 곡으로 마무리되는 느낌.
        Id.ARRIVING to listOf(12, 19, 87, 42, 34, 9, 26),

        // 기본. 무엇으로도 특정되지 않을 때.
        Id.CALM to listOf(1, 17, 16, 23, 3, 81, 86, 27, 14, 2),
    )

    /** 아이 좌석이 있을 때 앞쪽에 섞는 곡. */
    private val kids = listOf(60, 61, 62, 63, 64, 65)

    /**
     * 무드에 맞는 곡.
     *
     * @param kidsAboard 아동 프로필 좌석이 있으면 동요를 한 곡 앞에 둡니다.
     *                   전부 동요로 채우지는 않습니다 — 어른도 같이 타고 있습니다.
     * @param exclude    이미 큐에 있거나 방금 튼 곡. 같은 곡을 다시 권하지 않습니다.
     */
    fun forMood(
        mood: String,
        limit: Int = 5,
        kidsAboard: Boolean = false,
        exclude: Set<String> = emptySet(),
    ): List<MusicSearch.Item> {
        val n = limit.coerceIn(1, 10)
        val ids = byMood[mood.uppercase()] ?: byMood.getValue(Id.CALM)

        // 매번 같은 순서로 권하면 두 번째부터 "또 이거" 가 됩니다.
        // 무드 안에서만 섞으므로 분위기는 유지됩니다.
        val picked = Catalog.byIds(ids.shuffled())
            .filterNot { normalize(it.title, it.artist) in exclude }

        if (!kidsAboard) return MusicSearch.withArtwork(picked.take(n))

        // 동요는 두 번째 자리에 끼웁니다. 맨 앞에 두면 분위기를 읽은 결과가 아니라
        // "아이가 탔으니 동요" 로만 보입니다. 비 오는 날에 뽀로로가 먼저 나오면
        // 분위기 판정이 아무 일도 안 한 것처럼 느껴집니다.
        val kid = Catalog.byIds(kids.shuffled())
            .firstOrNull { normalize(it.title, it.artist) !in exclude }
        val merged = picked.toMutableList()
        if (kid != null) merged.add(minOf(1, merged.size), kid)

        return MusicSearch.withArtwork(merged.distinctBy { it.externalId }.take(n))
    }

    /** 제외 목록 비교용. 큐의 곡은 외부 id 가 다를 수 있어 제목+가수로 봅니다. */
    fun normalize(title: String, artist: String) =
        "${title.lowercase().trim()}|${artist.lowercase().trim()}"

    /** `mood.result` 메시지 본문. */
    fun result(mood: String, items: List<MusicSearch.Item>): JsonObject = buildJsonObject {
        put("type", MsgType.MOOD_RESULT)
        put("ts", System.currentTimeMillis())
        put("mood", mood)
        putJsonArray("items") {
            items.forEach { item ->
                add(buildJsonObject {
                    put("title", item.title)
                    put("artist", item.artist)
                    put("durationMs", item.durationMs)
                    put("externalId", item.externalId)
                    item.artworkUrl?.let { put("artworkUrl", it) }
                })
            }
        }
    }

    /** 무드 식별자가 우리가 아는 것인지. 모르는 값이면 기본 무드로 답합니다. */
    fun known(mood: String) = byMood.containsKey(mood.uppercase())
}
