package dev.semicolon.playseat.hub

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("Hub")
private val json = Json { ignoreUnknownKeys = true }

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    log.info("플레이시트 허브 시작 — http://0.0.0.0:$port")
    log.info("에뮬레이터에서 호스트 PC는 10.0.2.2 로 접근합니다")
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::hubModule)
        .start(wait = true)
}

fun Application.hubModule() {
    // 큐레이션 목록 곡의 앨범 커버를 뒤에서 채웁니다.
    // 시작을 막지 않고, 실패하면 커버 없이 그대로 돕니다.
    launch {
        runCatching { MusicSearch.warmCatalogArtwork() }
            .onFailure { log.warn("커버 예열 실패: {}", it.message) }
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 60.seconds
        maxFrameSize = 256 * 1024
    }
    install(CORS) {
        anyHost() // 개발용. 동승자 폰이 어느 주소에서 열릴지 모르기 때문.
        allowHeader(HttpHeaders.ContentType)
    }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/session").not() }
    }

    routing {
        get("/health") { call.respondText("ok") }
        get("/stats") { call.respondText(SessionHub.stats()) }

        // 미리듣기 음원 중계. 차량 앱의 MediaPlayer 가 직접 붙는 유일한 HTTP 경로입니다.
        get("/audio") { relayAudio(call) }

        // 무드 추천을 눈으로 확인하는 용도. 차량은 WebSocket 으로 묻습니다.
        get("/recommend") {
            val mood = call.request.queryParameters["mood"].orEmpty()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5
            val kids = call.request.queryParameters["kids"] == "true"
            val items = Recommend.forMood(mood, limit, kids)
            call.respondText(Recommend.result(mood, items).toString(), ContentType.Application.Json)
        }

        // 동승자 웹앱을 허브가 직접 서빙한다. 차량 화면 QR → 이 주소.
        val webRoot = File("../passenger-web")
        if (webRoot.isDirectory) {
            staticFiles("/", webRoot, index = "index.html")
            log.info("동승자 웹앱 서빙: {}", webRoot.absolutePath)
        } else {
            log.warn("passenger-web 디렉터리를 찾지 못했습니다: {}", webRoot.absolutePath)
        }

        webSocket("/session/{sessionId}") { handleSession() }
    }
}

/**
 * 미리듣기 음원 중계.
 *
 * 차량 앱은 자기가 아는 허브 주소 하나에만 붙고, 외부 CDN 은 모릅니다.
 * 왜 중계하는지, 왜 허용 목록이 필요한지는 [AudioRelay] 주석에 적었습니다.
 *
 * 세 가지를 지킵니다.
 *   - **허용 목록**: 토큰에 담긴 호스트가 Deezer 미리듣기 CDN 이 아니면 400.
 *     이 검사가 없으면 허브가 아무 주소나 대신 받아 주는 공개 프록시가 됩니다.
 *   - **Range 통과**: MediaPlayer 는 이어받기·탐색에 Range 를 씁니다. 요청 헤더를
 *     상위로 넘기고 206 과 Content-Range 를 그대로 돌려줘야 탐색이 동작합니다.
 *   - **스트리밍**: 받은 바이트를 바로 흘려보냅니다. 전체를 메모리에 담으면
 *     좌석 네 개가 동시에 받을 때 허브가 먼저 쓰러집니다.
 */
private suspend fun relayAudio(call: ApplicationCall) {
    val token = call.request.queryParameters["t"]
    if (token.isNullOrBlank()) {
        call.respondText("t 파라미터가 필요합니다", status = HttpStatusCode.BadRequest)
        return
    }

    val payload = AudioRelay.decodeToken(token)
    if (payload == null) {
        call.respondText("토큰을 읽을 수 없습니다", status = HttpStatusCode.BadRequest)
        return
    }

    // 토큰에는 곡 번호가 담겨 있고, 받아올 주소는 지금 다시 조회합니다.
    // 미리듣기 주소는 발급 900초 뒤에 죽기 때문입니다 (AudioRelay.RESOLVE_TTL_MS).
    // 어느 경로로 왔든 마지막에 허용 목록을 통과해야 흘려보냅니다 — 이 검사가
    // 없으면 허브가 아무 주소나 대신 받아 주는 SSRF 대리인이 됩니다.
    val upstream = AudioRelay.resolve(payload)
    if (upstream == null) {
        if (payload.startsWith(AudioRelay.TRACK_PREFIX)) {
            log.warn("미리듣기 주소를 다시 찾지 못했습니다")
            call.respondText("미리듣기를 찾지 못했습니다", status = HttpStatusCode.BadGateway)
        } else {
            log.warn("허용 목록 밖 음원 호스트 요청을 거절했습니다")
            call.respondText(
                "허용된 음원 호스트가 아닙니다 (${AudioRelay.ALLOWED_SUFFIX} 만 중계합니다)",
                status = HttpStatusCode.BadRequest,
            )
        }
        return
    }

    val range = call.request.header(HttpHeaders.Range)
    try {
        AudioRelay.client.prepareGet(upstream) {
            range?.let { header(HttpHeaders.Range, it) }
        }.execute { upstreamRes ->
            if (upstreamRes.status.value !in 200..299) {
                log.warn("상위 음원 HTTP {}", upstreamRes.status.value)
                call.respondText(
                    "상위 음원 조회 실패 (${upstreamRes.status.value})",
                    status = HttpStatusCode.BadGateway,
                )
                return@execute
            }

            // Range 응답은 Content-Range 가 함께 와야 플레이어가 위치를 계산합니다.
            upstreamRes.headers[HttpHeaders.ContentRange]
                ?.let { call.response.header(HttpHeaders.ContentRange, it) }
            call.response.header(HttpHeaders.AcceptRanges, "bytes")

            call.respondBytesWriter(
                contentType = ContentType.Audio.MPEG,
                status = if (upstreamRes.status == HttpStatusCode.PartialContent)
                    HttpStatusCode.PartialContent else HttpStatusCode.OK,
                contentLength = upstreamRes.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
            ) {
                upstreamRes.bodyAsChannel().copyTo(this)
            }
        }
    } catch (e: Exception) {
        // 재생 중에 차량이 연결을 끊으면(다음 곡으로 넘어감) 여기로 들어옵니다.
        // 고장이 아니라 정상적인 중단이라, 응답을 이미 쓰기 시작했으면 조용히 끝냅니다.
        if (call.response.isCommitted) {
            log.debug("음원 중계 중단: {}", e.message)
            return
        }
        log.warn("음원 중계 실패: {}", e.message)
        call.respondText("상위 음원을 받을 수 없습니다", status = HttpStatusCode.BadGateway)
    }
}

private suspend fun DefaultWebSocketServerSession.handleSession() {
    val sessionId = call.parameters["sessionId"]?.takeIf { it.isNotBlank() }
    if (sessionId == null) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "sessionId 누락"))
        return
    }

    val role = call.request.queryParameters["role"]
    val room = SessionHub.room(sessionId)

    when (role) {
        "car" -> serveCar(room)
        "phone" -> {
            val deviceId = call.request.queryParameters["deviceId"]?.takeIf { it.isNotBlank() }
            if (deviceId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "deviceId 누락"))
                return
            }
            servePhone(room, deviceId)
        }
        else -> close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "role 은 car 또는 phone"))
    }
}

private suspend fun DefaultWebSocketServerSession.serveCar(room: SessionRoom) {
    SessionHub.attachCar(room, this)
    try {
        for (frame in incoming) {
            val raw = (frame as? Frame.Text)?.readText() ?: continue
            val type = typeOf(raw) ?: continue
            if (type in CAR_ASKS_HUB) handleCarAsk(type, raw) else SessionHub.fromCar(room, type, raw)
        }
    } finally {
        SessionHub.detachCar(room, this)
    }
}

private suspend fun DefaultWebSocketServerSession.servePhone(room: SessionRoom, deviceId: String) {
    SessionHub.attachPhone(room, deviceId, this)
    try {
        for (frame in incoming) {
            val raw = (frame as? Frame.Text)?.readText() ?: continue
            val type = typeOf(raw)
            if (type == null) {
                send(Frame.Text("""{"type":"${MsgType.HUB_ERROR}","reason":"type 없음"}"""))
                continue
            }
            if (type in HUB_HANDLED) {
                handleHubLocal(type, raw)
            } else {
                SessionHub.fromPhone(room, this, raw)
            }
        }
    } finally {
        SessionHub.detachPhone(room, deviceId, this)
    }
}

/**
 * 차량이 허브에 직접 묻는 메시지. 폰으로는 뿌리지 않고 물어본 차에만 답합니다.
 *
 * 추천 곡 선정을 차가 아니라 허브가 하는 이유는 곡 목록이 허브에 있기 때문입니다.
 * 차는 "지금 분위기가 무엇인가" 만 판정합니다 — 그건 차만 알 수 있는 일입니다.
 */
private suspend fun DefaultWebSocketServerSession.handleCarAsk(type: String, raw: String) {
    if (type != MsgType.MOOD_QUERY) return
    val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
    val mood = obj["mood"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: 5
    val kids = obj["kidsAboard"]?.jsonPrimitive?.contentOrNull == "true"
    val exclude = obj["exclude"]?.jsonArray.orEmpty()
        .mapNotNull { it.jsonPrimitive.contentOrNull }
        .toSet()
    val items = Recommend.forMood(mood, limit, kids, exclude)
    send(Frame.Text(Recommend.result(mood, items).toString()))
}

/** 차량을 거치지 않고 허브가 직접 답하는 메시지 (현재는 곡 검색뿐). */
private suspend fun DefaultWebSocketServerSession.handleHubLocal(type: String, raw: String) {
    if (type != MsgType.SEARCH_QUERY) return
    val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
    val q = obj["q"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: 20
    send(Frame.Text(MusicSearch.query(q, limit).toString()))
}

private fun typeOf(raw: String): String? = runCatching {
    json.parseToJsonElement(raw).jsonObject["type"]?.jsonPrimitive?.contentOrNull
}.getOrNull()
