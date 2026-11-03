package dev.semicolon.playseat.car.net

import android.util.Log
import dev.semicolon.playseat.car.session.DriveAlbumMsg
import dev.semicolon.playseat.car.session.PhoneRequest
import dev.semicolon.playseat.car.session.RejectMsg
import dev.semicolon.playseat.car.session.SessionState
import dev.semicolon.playseat.car.session.TurnNotifyMsg
import dev.semicolon.playseat.car.session.TrackDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 허브와의 WebSocket 연결.
 *
 * 차량은 상태가 바뀔 때마다 전체 상태를 올려보내고, 폰 요청을 받아 SessionEngine 으로 넘깁니다.
 * 연결이 끊겨도 세션은 차량 안에서 계속 유지됩니다 — 허브는 중계자일 뿐입니다.
 */
class HubClient(
    private val sessionId: String,
    private val host: String,
    private val onRequest: (PhoneRequest) -> Unit,
    /** 허브가 돌려준 무드 추천 곡. 비어 있을 수 있습니다. */
    private val onSuggestions: (List<TrackDto>) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile var connected: Boolean = false
        private set

    fun connect() {
        if (closed.get()) return
        val url = "ws://$host/session/$sessionId?role=car"
        Log.i(TAG, "허브 접속 시도: $url")
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun send(state: SessionState) = sendRaw(json.encodeToString(SessionState.serializer(), state))

    fun send(reject: RejectMsg) = sendRaw(json.encodeToString(RejectMsg.serializer(), reject))

    fun send(notify: TurnNotifyMsg) = sendRaw(json.encodeToString(TurnNotifyMsg.serializer(), notify))

    fun send(album: DriveAlbumMsg) = sendRaw(json.encodeToString(DriveAlbumMsg.serializer(), album))

    /**
     * 지금 분위기에 맞는 곡을 묻습니다.
     *
     * 곡 목록은 허브에 있으므로 고르는 일도 허브가 합니다. 차는 "지금 비가 온다"
     * 까지만 압니다. 답은 `mood.result` 로 이 소켓에만 돌아옵니다.
     */
    fun askMood(moodId: String, kidsAboard: Boolean, exclude: List<String>) {
        val ex = exclude.joinToString(",") { "\"${it.replace("\"", "")}\"" }
        sendRaw(
            """{"type":"mood.query","ts":${System.currentTimeMillis()},""" +
                """"mood":"$moodId","limit":5,"kidsAboard":"$kidsAboard","exclude":[$ex]}"""
        )
    }

    private fun sendRaw(text: String) {
        val s = socket ?: return
        runCatching { s.send(text) }.onFailure { Log.w(TAG, "송신 실패: ${it.message}") }
    }

    fun close() {
        closed.set(true)
        runCatching { socket?.close(1000, "bye") }
        socket = null
        connected = false
    }

    /**
     * `mood.result` 면 곡 목록을 꺼내 넘기고 true 를 돌려줍니다.
     *
     * 직렬화 모델을 따로 두지 않는 이유는 이 메시지가 차 → 허브 → 차로만 오가는
     * 단방향 응답이고, 필드가 비면 그 곡만 건너뛰면 되기 때문입니다.
     */
    private fun parseSuggestions(text: String): Boolean {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "mood.result") return false
        val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            TrackDto(
                itemId = o["externalId"]?.jsonPrimitive?.contentOrNull ?: title,
                title = title,
                artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                durationMs = o["durationMs"]?.jsonPrimitive?.intOrNull ?: 210_000,
                requestedBySeat = "AUTO",
                artworkUrl = o["artworkUrl"]?.jsonPrimitive?.contentOrNull,
            )
        }
        onSuggestions(items)
        return true
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            Log.i(TAG, "허브 연결됨")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (parseSuggestions(text)) return
            val req = runCatching { json.decodeFromString(PhoneRequest.serializer(), text) }.getOrNull()
            if (req == null) {
                Log.d(TAG, "해석 불가 메시지: ${text.take(120)}")
                return
            }
            // 허브가 생성하는 통지(car.online 등)는 무시합니다
            if (req.type.startsWith("car.") || req.type.startsWith("hub.")) return
            onRequest(req)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
            Log.w(TAG, "허브 연결 실패: ${t.message} — ${RECONNECT_MS}ms 후 재시도")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            if (!closed.get()) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (closed.get()) return
        Thread {
            Thread.sleep(RECONNECT_MS)
            if (!closed.get()) connect()
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "HubClient"
        private const val RECONNECT_MS = 3000L
    }
}
