package dev.semicolon.playseat.hub

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 음원 중계.
 *
 * 플레이시트는 음원을 보유하지 않습니다. 그런데 플레이리스트 앱인데 소리가 안 나면
 * "그래서 곡은 어디서 나오나요" 에 답할 수가 없습니다. 그래서 시연에서는 Deezer 공개
 * 검색 API 가 응답마다 같이 주는 **30초 미리듣기** 를 허브가 받아 차량으로 흘려 줍니다.
 * 전체 음원이 아니고, 30초가 끝이라는 점을 숨기지 않습니다. 정식 서비스에서는 이
 * 경로만 사업자 음원 세션으로 갈아끼우면 되고, 차량 앱은 그대로 둡니다 — 차량 앱이
 * 보는 것은 "허브가 주는 주소" 하나뿐이기 때문입니다.
 *
 * 왜 CDN 주소를 차량에 그대로 주지 않는가
 *   1. 차량 앱은 자기가 아는 허브 주소(`BuildConfig.HUB_HOST`)에만 붙으면 됩니다.
 *      검색 결과에 외부 호스트가 섞이면 평문/인증서 정책을 호스트마다 따져야 합니다.
 *   2. 미리듣기 주소는 수명이 짧습니다. 중계를 거치면 받는 시점에 다시 조회됩니다.
 *   3. 나중에 사업자 음원으로 바뀌면 주소 체계가 통째로 달라집니다. 차량이 CDN 주소를
 *      직접 알고 있으면 그때 차량 앱도 같이 고쳐야 합니다.
 *
 * 왜 호스트 허용 목록이 필요한가
 *   토큰은 상위 음원 주소를 담고 있을 뿐이어서, 검사가 없으면 누구든 토큰을 직접
 *   만들어 `/audio?t=...` 로 **아무 주소나** 허브에게 대신 받아오게 시킬 수 있습니다.
 *   그건 공개 프록시이고, 사내망 주소(`http://169.254.169.254/`, `http://localhost/`)까지
 *   허브 이름으로 긁히는 SSRF 구멍입니다. 심사에서 지적당하기 전에 막아 둡니다.
 *   그래서 디코드한 주소의 호스트가 Deezer 미리듣기 CDN([ALLOWED_SUFFIX]) 이 아니면
 *   받아오지 않고 400 으로 끊습니다.
 */
object AudioRelay {

    private val log = LoggerFactory.getLogger("AudioRelay")

    /** Deezer 미리듣기 길이. 응답에 길이가 안 실려 와서 상수로 둡니다. */
    const val PREVIEW_MS = 30_000

    /** 허용 호스트. 이 도메인과 그 하위 도메인만 중계합니다. */
    const val ALLOWED_SUFFIX = "dzcdn.net"

    /** 중계 경로. 차량 앱이 `http://<HUB_HOST>` 를 앞에 붙입니다. */
    const val PATH = "/audio"

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** 곡 번호로 가리키는 토큰의 접두어. 뒤에는 Deezer 트랙 번호가 붙습니다. */
    const val TRACK_PREFIX = "deezer:"

    /**
     * 곡 번호 → 미리듣기 주소를 캐시해 두는 시간.
     *
     * 미리듣기 CDN 주소에는 만료 시각이 박혀 있고, 실측으로 **발급 900초 뒤에
     * 403** 이 됩니다. 검색할 때 받은 주소를 그대로 토큰에 담아 두면 시연 15분째에
     * 소리가 조용히 끊깁니다 — 그것도 '곡은 큐에 있는데 소리만 안 나는' 가장
     * 설명하기 어려운 모양으로요. 그래서 토큰에는 **곡 번호**를 담고, 재생
     * 시점에 다시 조회합니다. 만료(900초)보다 넉넉히 짧게 잡아 두면 캐시에서
     * 꺼낸 주소가 이미 죽어 있는 일이 없습니다.
     */
    private const val RESOLVE_TTL_MS = 10 * 60_000L

    private val resolved = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * 중계 토큰.
     *
     * 담기는 내용은 둘 중 하나입니다 — `deezer:<트랙번호>` 또는 상위 음원 주소.
     * 서명이 아니라 그냥 담는 것이고, 위조를 막는 것은 토큰이 아니라 [isAllowed] 입니다.
     * 굳이 인코딩하는 이유는 CDN 주소에 쿼리 문자열이 붙어 있어서 그대로 넣으면
     * `/audio` 의 파라미터와 섞이기 때문입니다.
     */
    fun encodeToken(payload: String): String =
        encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))

    /** 토큰 내용을 꺼냅니다. 토큰이 깨졌으면 null — 호출하는 쪽이 400 으로 끊습니다. */
    fun decodeToken(token: String): String? = runCatching {
        String(decoder.decode(token), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 이 주소를 허브가 대신 받아와도 되는지.
     *
     * http(s) 이고 호스트가 [ALLOWED_SUFFIX] 또는 그 하위 도메인일 때만 참입니다.
     * 사용자 정보(`https://cdn.dzcdn.net@evil.example/`)가 붙은 주소는 호스트가
     * 뒤쪽이라 사람 눈을 속이기 쉬우므로, 있으면 그냥 거절합니다.
     */
    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return host == ALLOWED_SUFFIX || host.endsWith(".$ALLOWED_SUFFIX")
    }

    /**
     * 검색 결과에 실어 보낼 중계 경로.
     *
     * 허용 목록 밖 주소는 애초에 경로를 만들어 주지 않습니다. 받는 쪽에서 400 이
     * 나는 주소를 결과에 실어 보내면, 차량은 재생을 시도했다 실패하고 시뮬레이션으로
     * 떨어집니다 — 어차피 못 들려줄 것이면 미리듣기가 없다고 말하는 편이 정확합니다.
     */
    fun relayPath(upstreamUrl: String?, previewRef: String? = null): String? {
        val url = upstreamUrl?.takeIf { it.isNotBlank() } ?: return null
        if (!isAllowed(url)) return null
        // 미리듣기가 있다는 사실은 주소로 확인하고, 토큰에는 곡 번호를 담습니다.
        // 주소는 900초 뒤에 죽지만 곡 번호는 죽지 않습니다 ([RESOLVE_TTL_MS]).
        val ref = previewRef?.takeIf { it.startsWith(TRACK_PREFIX) && trackIdOf(it) != null }
        return "$PATH?t=${encodeToken(ref ?: url)}"
    }

    /** `deezer:12345` 에서 번호만. 숫자가 아니면 null — 조회하러 가지 않습니다. */
    fun trackIdOf(payload: String): String? =
        payload.removePrefix(TRACK_PREFIX).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /**
     * 토큰 내용을 실제로 받아올 주소로 바꿉니다.
     *
     * 곡 번호면 지금 다시 조회하고, 주소면 허용 목록만 봅니다. 어느 경로로 왔든
     * **마지막에 [isAllowed] 를 한 번 더 통과해야** 흘려보냅니다 — 조회해서 받은
     * 주소라고 믿어 버리면, 상위 응답이 바뀌는 날 그게 그대로 통로가 됩니다.
     */
    suspend fun resolve(payload: String): String? {
        if (!payload.startsWith(TRACK_PREFIX)) return payload.takeIf { isAllowed(it) }
        val id = trackIdOf(payload) ?: return null

        resolved[id]?.let { (at, url) ->
            if (System.currentTimeMillis() - at < RESOLVE_TTL_MS) return url
            resolved.remove(id)
        }

        val body = runCatching {
            val res = client.get("https://api.deezer.com/track/$id")
            if (res.status.value !in 200..299) {
                log.warn("Deezer 곡 조회 HTTP {}", res.status.value)
                null
            } else res.bodyAsText()
        }.onFailure { log.warn("Deezer 곡 조회 실패: {}", it.message) }.getOrNull() ?: return null

        val url = runCatching {
            Json { ignoreUnknownKeys = true }.parseToJsonElement(body)
                .jsonObject["preview"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() && isAllowed(it) } ?: return null

        resolved[id] = System.currentTimeMillis() to url
        return url
    }

    /**
     * 중계용 HTTP 클라이언트.
     *
     * [MusicSearch] 의 클라이언트를 같이 쓰지 않는 이유는 요청 타임아웃입니다.
     * 검색은 6초 안에 못 오면 버리는 게 맞지만, 음원은 재생되는 동안 계속 흘러오는
     * 응답이라 같은 제한을 걸면 재생 중에 끊깁니다. 연결 타임아웃만 둡니다.
     */
    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = 20_000
        }
        expectSuccess = false
    }
}
