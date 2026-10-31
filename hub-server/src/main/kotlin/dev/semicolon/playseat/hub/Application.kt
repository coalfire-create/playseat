package dev.semicolon.playseat.hub

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
            SessionHub.fromCar(room, type, raw)
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
