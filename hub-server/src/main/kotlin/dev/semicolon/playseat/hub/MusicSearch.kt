package dev.semicolon.playseat.hub

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 곡 검색.
 *
 * 플레이시트는 음원을 보유하지 않습니다. 좌석에서 올라온 선곡을 '요청'으로 만들기 위해
 * 곡명·아티스트·재생시간 메타데이터만 필요하고, 그것만 조회합니다.
 * 실제 재생은 차량의 음악 앱이 담당합니다.
 *
 * 2단 구성입니다.
 *   1. 큐레이션 목록([Catalog]) — 즉시·정확·오프라인. 국내 음원 위주
 *   2. Deezer 공개 검색 API — 목록에 없는 곡을 보완
 *
 * 왜 외부 API 를 주 소스로 쓰지 않는가
 *   - iTunes Search API: `country=KR` 로 한글 아티스트명이 정확하지만 요청 제한이 빡빡해
 *     연속 검색 시 결과가 0건으로 떨어지는 것을 확인했습니다. 시연 중에 쓸 수 없습니다.
 *   - Deezer: 키 없이 쓸 수 있고 재생시간·앨범아트를 주지만 국내 음원 커버리지가 고르지
 *     않습니다. 한글 표기로는 "세븐틴" 0건, "BTS" 는 정국의 월드컵 주제가 한 곡뿐입니다.
 *
 * 보완 장치 두 가지
 *   - [ArtistAlias]: 한글 표기와 영문 표기를 이어 줍니다. Deezer 는 국내 아티스트를
 *     영문으로 색인하므로, 한글 검색이 0건이면 영문 표기로 한 번 더 물어봅니다.
 *   - [Relevance] 하한: 관련도 0 인 결과는 버립니다. 이게 없으면 "에스파" 검색에
 *     '에스나'·'에스더' 가, "아이브" 검색에 AKMU 가 섞여 들어왔습니다.
 *     좌석에서 목록을 눌러 바로 큐에 넣는 구조라, 자리를 채우는 것보다 비우는 게 낫습니다.
 *
 * 그래서 시연·심사에서 반드시 동작해야 하는 곡은 목록으로 고정하고, 그 밖의 검색어는
 * Deezer 로 넘깁니다. 기획서의 2단계 계획대로 스트리밍 사업자 공식 API 제휴가 성립하면
 * 이 클래스만 교체하면 됩니다. 차량 앱은 검색을 모르기 때문입니다.
 */
object MusicSearch {

    private val log = LoggerFactory.getLogger("MusicSearch")
    private const val TTL_MS = 10 * 60 * 1000L

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 6000
            connectTimeoutMillis = 3000
        }
        expectSuccess = false
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, JsonObject>>()

    @Serializable
    private data class DeezerResponse(val data: List<DeezerTrack> = emptyList())

    @Serializable
    private data class DeezerTrack(
        val id: Long = 0,
        val title: String = "",
        /** 초 단위. 계약은 ms 를 쓰므로 변환합니다. */
        val duration: Int = 0,
        val artist: DeezerArtist? = null,
        val album: DeezerAlbum? = null,
    )

    @Serializable
    private data class DeezerArtist(val name: String = "")

    @Serializable
    private data class DeezerAlbum(
        @SerialName("cover_medium") val coverMedium: String? = null,
    )

    /** `search.query` 에 대한 `search.result` 본문. 실패해도 예외를 던지지 않습니다. */
    suspend fun query(q: String, limit: Int): JsonObject {
        val needle = q.trim()
        val n = limit.coerceIn(1, 50)
        if (needle.isEmpty()) return result(needle, Catalog.all(n), "catalog")

        val key = "$needle/$n"
        cache[key]?.let { (at, cached) ->
            if (System.currentTimeMillis() - at < TTL_MS) return cached
            cache.remove(key)
        }

        // 1) 큐레이션 목록에서 먼저 찾습니다
        val local = Catalog.search(needle, n)

        // 2) 모자라면 Deezer 로 보완합니다
        val remote = if (local.size >= n) emptyList() else
            runCatching { fromDeezer(needle, n - local.size) }
                .onFailure { log.warn("Deezer 검색 실패({}) — 목록 결과만 반환", it.message) }
                .getOrDefault(emptyList())

        val merged = (local + remote)
            .distinctBy { it.title.lowercase().trim() to it.artist.lowercase().trim() }
            .take(n)

        val source = when {
            local.isNotEmpty() && remote.isNotEmpty() -> "catalog+deezer"
            local.isNotEmpty() -> "catalog"
            remote.isNotEmpty() -> "deezer"
            else -> "none"
        }

        val body = result(needle, merged, source)
        if (merged.isNotEmpty()) cache[key] = System.currentTimeMillis() to body
        return body
    }

    /**
     * Deezer 보완 검색.
     *
     * 한글 표기로 먼저 묻고, 비면 영문 표기로 한 번 더 묻습니다.
     * Deezer 는 국내 아티스트를 영문으로 색인해서 "세븐틴" 은 0건이지만
     * "seventeen" 은 제대로 나옵니다.
     */
    private suspend fun fromDeezer(q: String, limit: Int): List<Item> {
        val primary = deezerSearch(q, limit)
        if (primary.isNotEmpty()) return primary
        val foreign = ArtistAlias.foreignForm(q.trim().lowercase()) ?: return emptyList()
        return deezerSearch(foreign, limit)
    }

    private suspend fun deezerSearch(q: String, limit: Int): List<Item> {
        // 넉넉히 받아 와서 직접 재정렬한 뒤 잘라냅니다.
        // Deezer 기본 정렬은 한글 아티스트명에 약해, "아이유" 로 검색하면
        // 피처링으로 참여한 다른 가수의 곡이 앞에 옵니다.
        // 적게 받으면 재정렬할 것이 없습니다. limit 이 2여도 충분히 받아 둡니다.
        val fetch = (limit * 8).coerceIn(25, 100)
        val res: HttpResponse = client.get("https://api.deezer.com/search") {
            url.parameters.append("q", q)
            url.parameters.append("limit", fetch.toString())
        }
        if (res.status.value !in 200..299) {
            log.warn("Deezer HTTP {}", res.status.value)
            return emptyList()
        }
        val parsed = Json { ignoreUnknownKeys = true }
            .decodeFromString(DeezerResponse.serializer(), res.bodyAsText())

        return parsed.data
            .filter { it.title.isNotBlank() && it.duration > 0 }
            .map {
                Item(
                    title = it.title,
                    artist = it.artist?.name.orEmpty(),
                    durationMs = it.duration * 1000,
                    externalId = "deezer:${it.id}",
                    artworkUrl = it.album?.coverMedium,
                )
            }
            // 검색어와 상관없는 곡으로 자리를 채우지 않습니다.
            .filter { Relevance.of(it.title, it.artist, q) > 0 }
            .sortedByDescending { Relevance.of(it.title, it.artist, q) }
            .distinctBy { it.title.lowercase() to it.artist.lowercase() }
            .take(limit)
    }

    data class Item(
        val title: String,
        val artist: String,
        val durationMs: Int,
        val externalId: String,
        val artworkUrl: String? = null,
    )

    private fun result(q: String, items: List<Item>, source: String) = buildJsonObject {
        put("type", MsgType.SEARCH_RESULT)
        put("ts", System.currentTimeMillis())
        put("query", q)
        // 어느 소스에서 왔는지 남겨 둡니다. 시연 중 폴백으로 떨어졌는지 바로 알 수 있습니다.
        put("source", source)
        putJsonArray("items") {
            items.forEach { t ->
                addJsonObject {
                    put("title", t.title)
                    put("artist", t.artist)
                    put("durationMs", t.durationMs)
                    put("externalId", t.externalId)
                    t.artworkUrl?.let { put("artworkUrl", it) }
                }
            }
        }
    }
}

/**
 * 큐레이션 목록.
 *
 * 시연·심사에서 반드시 정확히 나와야 하는 곡들입니다. 국내 음원 위주로 세대·장르를
 * 섞어 두었습니다. 가족 나들이 시나리오에 필요한 동요도 포함합니다
 * (아동 좌석 프로필 제한을 보여 주려면 짧은 동요가 있어야 합니다).
 *
 * 심사 중에 이름을 쳐 볼 만한 아티스트가 0건이 나면 안 되므로, 주요 그룹은
 * 빠짐없이 넣습니다. 재생시간은 실제 음원 길이에 맞춘 근사값입니다 —
 * 이 서비스는 음원을 보유하지 않고 '무엇을 틀지' 만 정하기 때문에,
 * 큐 길이 계산에 쓸 정도의 정확도면 충분합니다.
 */
object Catalog {

    private fun t(title: String, artist: String, sec: Int, id: Int) =
        MusicSearch.Item(title, artist, sec * 1000, "catalog:$id")

    private val catalog = listOf(
        // 발라드·싱어송라이터
        t("밤편지", "아이유", 254, 1),
        t("좋은 날", "아이유", 227, 2),
        t("가을 아침", "아이유", 220, 3),
        t("무릎", "아이유", 229, 4),
        t("바람기 메모리", "이소라", 272, 5),
        t("Track 9", "이소라", 243, 6),
        t("제자리", "이소라", 258, 7),
        t("사건의 지평선", "윤하", 321, 8),
        t("기다릴게", "윤하", 232, 9),
        t("비 오는 날", "검정치마", 254, 10),
        t("Everything", "검정치마", 268, 11),
        t("첫눈처럼 너에게 가겠다", "에일리", 268, 12),
        t("벚꽃 엔딩", "버스커 버스커", 262, 13),
        t("여행", "볼빨간사춘기", 215, 14),
        t("우주를 줄게", "볼빨간사춘기", 229, 15),
        t("너를 만나", "폴킴", 262, 16),
        t("모든 날, 모든 순간", "폴킴", 251, 17),
        t("이 밤", "폴킴", 245, 18),
        t("봄날", "방탄소년단", 275, 19),
        t("취중진담", "김동률", 300, 20),
        t("감사", "김동률", 282, 21),
        t("서울의 달", "김건모", 268, 22),
        t("너의 의미", "아이유", 198, 23),
        t("밤이 되니까", "펜타곤", 203, 24),
        t("사랑은 늘 도망가", "임영웅", 237, 25),
        t("이제 나만 믿어요", "임영웅", 249, 26),
        t("두 사람", "성시경", 258, 27),
        t("거리에서", "성시경", 263, 28),

        // 아이돌·댄스
        t("Love Dive", "아이브", 177, 30),
        t("I AM", "아이브", 189, 31),
        t("After LIKE", "아이브", 175, 32),
        t("Hype Boy", "뉴진스", 180, 33),
        t("Ditto", "뉴진스", 185, 34),
        t("OMG", "뉴진스", 215, 35),
        t("Attention", "뉴진스", 180, 36),
        t("Antifragile", "르세라핌", 184, 37),
        t("FEARLESS", "르세라핌", 200, 38),
        t("Drama", "에스파", 185, 39),
        t("Next Level", "에스파", 223, 40),
        t("Dynamite", "방탄소년단", 199, 41),
        t("Butter", "방탄소년단", 164, 42),
        t("Permission to Dance", "방탄소년단", 187, 43),
        t("How You Like That", "블랙핑크", 182, 44),
        t("Pink Venom", "블랙핑크", 187, 45),
        t("Queencard", "아이들", 176, 46),
        t("TOMBOY", "아이들", 174, 47),
        t("Cupid", "피프티피프티", 174, 48),
        t("Super Shy", "뉴진스", 155, 49),
        t("손오공", "세븐틴", 184, 50),
        t("God of Music", "세븐틴", 198, 51),
        t("HOT", "세븐틴", 196, 52),
        t("FANCY", "트와이스", 214, 53),
        t("What is Love?", "트와이스", 208, 54),
        t("God's Menu", "스트레이키즈", 196, 55),
        t("MANIAC", "스트레이키즈", 186, 56),
        t("Psycho", "레드벨벳", 194, 57),
        t("Feel My Rhythm", "레드벨벳", 189, 58),
        t("예뻤어", "데이식스", 212, 59),

        // 동요 (아동 좌석 시나리오용 — 짧은 곡)
        t("아기상어", "핑크퐁", 96, 60),
        t("상어가족", "핑크퐁", 134, 61),
        t("곰 세 마리", "핑크퐁", 72, 62),
        t("뽀로로 노래", "뽀로로", 88, 63),
        t("동물농장", "핑크퐁", 105, 64),
        t("과일 나라", "핑크퐁", 92, 65),

        // 팝
        t("Shape of You", "Ed Sheeran", 233, 80),
        t("Perfect", "Ed Sheeran", 263, 81),
        t("Blinding Lights", "The Weeknd", 200, 82),
        t("As It Was", "Harry Styles", 167, 83),
        t("Levitating", "Dua Lipa", 203, 84),
        t("Flowers", "Miley Cyrus", 200, 85),
        t("Yellow", "Coldplay", 266, 86),
        t("Viva La Vida", "Coldplay", 242, 87),
        t("Anti-Hero", "Taylor Swift", 200, 88),
        t("Cruel Summer", "Taylor Swift", 178, 89),
    )

    fun all(limit: Int) = catalog.take(limit)

    /** 관련도 순으로 돌려줍니다. 아티스트가 걸리면 가장 높게 봅니다. */
    fun search(q: String, limit: Int): List<MusicSearch.Item> {
        if (q.isBlank()) return all(limit)
        return catalog
            .map { item -> item to Relevance.of(item.title, item.artist, q) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (item, _) -> item }
            .take(limit)
    }
}

/**
 * 검색어와 곡의 관련도.
 *
 * 아티스트명이 걸리면 가장 높게 봅니다. 사람은 보통 가수를 먼저 떠올리고,
 * 좌석에서 한 곡을 고르는 상황에서는 "그 가수의 곡"이 나오는 게 맞습니다.
 * 0 이면 검색어와 상관없는 곡이라는 뜻이고, 호출하는 쪽은 그것을 버립니다.
 *
 * 큐레이션 목록과 Deezer 결과가 같은 자를 쓰도록 한곳에 둡니다.
 */
object Relevance {

    fun of(title: String, artist: String, query: String): Int {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return 0
        // 한글은 띄어쓰기가 제각각입니다. "곰 세마리" 와 "곰 세 마리" 는 같은 곡이고,
        // "버스커버스커" 와 "버스커 버스커" 도 같은 가수입니다. 공백은 지우고 봅니다.
        val a = squash(artist)
        val t = squash(stripFeaturing(title.lowercase()))
        // 표기가 달라도 같은 아티스트면 걸려야 합니다. "BTS" 로 "방탄소년단" 을 찾습니다.
        return ArtistAlias.expand(needle).maxOf { n -> score(a, t, squash(n)) }
    }

    private fun score(artist: String, title: String, needle: String): Int {
        // 짧은 약어는 부분일치를 받지 않습니다. "ive" 는 'Oliver' 안에도 들어 있어서
        // 아이브를 찾으면 엉뚱한 곡이 딸려 옵니다.
        val exactOnly = needle.length <= SHORT_FORM
        var s = 0
        if (artist == needle) s += 100              // 아티스트 정확히 일치
        else if (!exactOnly && artist.contains(needle)) s += 60
        if (title == needle) s += 40                // 곡명 정확히 일치
        else if (!exactOnly && title.contains(needle)) s += 20
        return s
    }

    /**
     * 제목에서 피처링 표기를 걷어냅니다.
     *
     * "Ma Meilleure Ennemie ft. Coldplay" 는 Stromae 의 곡이지 콜드플레이의 곡이 아닙니다.
     * 좌석에서 가수 이름을 치는 사람은 그 가수의 곡을 원하는 것이므로,
     * 참여 표기로만 걸린 곡은 관련도에서 빼는 편이 맞습니다.
     */
    private fun squash(s: String) = s.lowercase().filterNot { it.isWhitespace() }

    private fun stripFeaturing(title: String): String =
        title.replace(FEATURING_PAREN, " ").replace(FEATURING_TAIL, " ").trim()

    private const val SHORT_FORM = 3

    private val FEATURING_PAREN =
        Regex("[(\\[][^)\\]]*\\b(feat|ft|with|prod)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)

    private val FEATURING_TAIL =
        Regex("\\s[-\u2013]?\\s*\\b(feat|ft)\\b\\.?\\s.*$", RegexOption.IGNORE_CASE)
}

/**
 * 아티스트 표기 사전.
 *
 * 국내 아티스트는 한글과 영문 표기가 둘 다 쓰입니다. 사람마다 "BTS" 라고도
 * "방탄소년단" 이라고도 치는데, 목록에는 하나로만 들어 있어 한쪽이 0건이 났습니다.
 * Deezer 쪽은 더해서, 국내 아티스트를 거의 영문으로만 색인합니다.
 */
object ArtistAlias {

    private val groups = listOf(
        setOf("방탄소년단", "bts", "bangtan"),
        setOf("아이유", "iu"),
        setOf("뉴진스", "newjeans", "new jeans"),
        setOf("아이브", "ive"),
        setOf("에스파", "aespa"),
        setOf("블랙핑크", "blackpink", "black pink"),
        setOf("르세라핌", "le sserafim", "lesserafim"),
        setOf("아이들", "(g)i-dle", "gidle", "g-idle"),
        setOf("세븐틴", "seventeen"),
        setOf("트와이스", "twice"),
        setOf("스트레이키즈", "stray kids", "straykids"),
        setOf("레드벨벳", "red velvet", "redvelvet"),
        setOf("데이식스", "day6"),
        setOf("피프티피프티", "fifty fifty"),
        setOf("펜타곤", "pentagon"),
        setOf("볼빨간사춘기", "볼빨간 사춘기", "bol4"),
        setOf("버스커 버스커", "버스커버스커", "busker busker"),
        setOf("윤하", "younha"),
        setOf("폴킴", "paul kim"),
        setOf("성시경", "sung si kyung"),
        setOf("임영웅", "lim young woong"),
        setOf("김동률", "kim dong ryul"),
        setOf("이소라", "lee sora"),
        setOf("검정치마", "the black skirts"),
        setOf("에일리", "ailee"),
        setOf("핑크퐁", "pinkfong"),
        setOf("뽀로로", "pororo"),
    )

    private val index: Map<String, Set<String>> =
        buildMap { groups.forEach { g -> g.forEach { form -> put(form, g) } } }

    /** 같은 뜻으로 볼 표기들. 사전에 없으면 자기 자신만 돌려줍니다. */
    fun expand(needle: String): Set<String> = index[needle] ?: setOf(needle)

    /** 해외 서비스에 물어볼 때 쓸 영문 표기. 없으면 null. */
    fun foreignForm(needle: String): String? =
        index[needle]?.firstOrNull { form -> form.none { ch -> ch in '\uAC00'..'\uD7A3' } }
}
