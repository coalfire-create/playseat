package dev.semicolon.playseat.hub

import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 세션방 하나 = 차 한 대.
 *
 * 차량 앱이 단일 진실 공급원이므로 허브는 상태를 해석하지 않는다.
 * 마지막 `state.sync` 원문만 들고 있다가, 폰이 새로 붙으면 그대로 돌려준다.
 * (폰이 접속 직후 빈 화면을 보지 않게 하는 것이 목적)
 */
class SessionRoom(val sessionId: String) {
    var car: WebSocketSession? = null
    var lastState: String? = null
    val phones = ConcurrentHashMap<String, WebSocketSession>()
    val mutex = Mutex()
}

object SessionHub {
    private val log = LoggerFactory.getLogger("SessionHub")
    private val rooms = ConcurrentHashMap<String, SessionRoom>()

    fun room(sessionId: String): SessionRoom =
        rooms.computeIfAbsent(sessionId) { SessionRoom(it) }

    // ---------- 차량 ----------

    suspend fun attachCar(room: SessionRoom, session: WebSocketSession) {
        room.mutex.withLock {
            room.car?.let {
                log.info("[{}] 기존 차량 연결을 교체합니다", room.sessionId)
                runCatching { it.close(CloseReason(CloseReason.Codes.NORMAL, "replaced")) }
            }
            room.car = session
        }
        log.info("[{}] 차량 연결됨 (폰 {}대)", room.sessionId, room.phones.size)
        broadcastToPhones(room, """{"type":"${MsgType.CAR_ONLINE}","ts":${System.currentTimeMillis()}}""")
    }

    suspend fun detachCar(room: SessionRoom, session: WebSocketSession) {
        room.mutex.withLock {
            if (room.car === session) room.car = null
        }
        log.info("[{}] 차량 연결 끊김", room.sessionId)
        broadcastToPhones(room, """{"type":"${MsgType.CAR_OFFLINE}","ts":${System.currentTimeMillis()}}""")
    }

    /** 차량이 보낸 메시지를 모든 폰에 뿌린다. 폰은 자기 좌석 것만 골라 쓴다. */
    suspend fun fromCar(room: SessionRoom, type: String, raw: String) {
        if (type == MsgType.STATE_SYNC) room.lastState = raw
        broadcastToPhones(room, raw)
    }

    // ---------- 폰 ----------

    suspend fun attachPhone(room: SessionRoom, deviceId: String, session: WebSocketSession) {
        room.phones.put(deviceId, session)?.let {
            runCatching { it.close(CloseReason(CloseReason.Codes.NORMAL, "replaced")) }
        }
        log.info("[{}] 폰 연결됨 device={} (총 {}대)", room.sessionId, deviceId, room.phones.size)

        // 접속 직후 마지막 상태를 바로 내려 준다. 없으면 차량이 아직 없다는 뜻.
        val snapshot = room.lastState
        if (snapshot != null) {
            runCatching { session.send(Frame.Text(snapshot)) }
        } else {
            runCatching {
                session.send(Frame.Text("""{"type":"${MsgType.CAR_OFFLINE}","ts":${System.currentTimeMillis()}}"""))
            }
        }
    }

    fun detachPhone(room: SessionRoom, deviceId: String, session: WebSocketSession) {
        room.phones.remove(deviceId, session)
        log.info("[{}] 폰 연결 끊김 device={} (남은 {}대)", room.sessionId, deviceId, room.phones.size)
    }

    /** 폰 요청을 차량으로 넘긴다. 차량이 없으면 거절을 돌려준다. */
    suspend fun fromPhone(room: SessionRoom, session: WebSocketSession, raw: String) {
        val car = room.car
        if (car == null) {
            runCatching {
                session.send(Frame.Text("""{"type":"${MsgType.CAR_OFFLINE}","ts":${System.currentTimeMillis()}}"""))
            }
            return
        }
        runCatching { car.send(Frame.Text(raw)) }
            .onFailure { log.warn("[{}] 차량으로 전달 실패: {}", room.sessionId, it.message) }
    }

    // ---------- 공통 ----------

    private suspend fun broadcastToPhones(room: SessionRoom, raw: String) {
        val dead = mutableListOf<String>()
        room.phones.forEach { (deviceId, s) ->
            runCatching { s.send(Frame.Text(raw)) }.onFailure { dead += deviceId }
        }
        dead.forEach { room.phones.remove(it) }
    }

    fun stats(): String = rooms.values.joinToString(", ") {
        "${it.sessionId}(car=${it.car != null}, phones=${it.phones.size})"
    }.ifEmpty { "세션 없음" }
}
