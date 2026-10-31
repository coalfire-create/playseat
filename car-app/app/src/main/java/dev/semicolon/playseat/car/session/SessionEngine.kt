package dev.semicolon.playseat.car.session

import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.SeatId
import dev.semicolon.playseat.car.vehicle.SeatProfile
import dev.semicolon.playseat.car.vehicle.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 세션의 단일 진실 공급원.
 *
 * 턴 순서, 좌석 권한, 스킵 과반을 모두 여기서 판정합니다. 서버는 중계만 하므로
 * 이 클래스가 곧 플레이시트의 규칙입니다. 안드로이드 의존성이 없어 단위 테스트가 됩니다.
 */
class SessionEngine(
    sessionId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(
        SessionState(
            sessionId = sessionId,
            seats = SeatId.ALL.map { SeatDto(it, occupied = false, profile = defaultProfile(it)) },
        )
    )
    val state: StateFlow<SessionState> = _state

    private var itemSeq = 1

    // ---------- 차량 신호 ----------

    /** 차량 신호가 바뀔 때마다 호출합니다. 좌석 슬롯 생성·회수와 주행 상태를 반영합니다. */
    fun onVehicle(snapshot: VehicleSnapshot) {
        val cur = _state.value

        val seats = cur.seats.map { seat ->
            val signal = snapshot.seat(seat.seatId)
            val occupied = signal?.occupied ?: false
            when {
                // 하차: 좌석을 비우고 참여 정보를 지웁니다
                seat.occupied && !occupied ->
                    seat.copy(occupied = false, deviceId = null, nickname = null, connected = false)
                else -> seat.copy(occupied = occupied)
            }
        }

        val leftSeats = cur.seats.filter { it.occupied }.map { it.seatId }
            .minus(seats.filter { it.occupied }.map { it.seatId }.toSet())

        // 하차한 사람의 미재생 곡과 투표를 정리합니다
        val queue = cur.queue.filterNot { it.requestedBySeat in leftSeats }
        val votes = cur.skipVotes.filterNot { it in leftSeats }

        var next = cur.copy(
            seats = seats,
            queue = queue,
            skipVotes = votes,
            driveState = snapshot.driveState.name,
            etaSeconds = snapshot.etaSeconds,
            queueTargetMs = snapshot.etaSeconds?.let { it * 1000L },
            skipThreshold = thresholdFor(seats),
        )

        // 차례였던 사람이 내렸거나 아직 차례가 없으면 다시 계산합니다
        if (next.turnSeatId == null || next.turnSeatId in leftSeats) {
            next = next.copy(turnSeatId = nextTurn(next, from = null))
        }

        commit(next)
    }

    // ---------- 폰 요청 ----------

    /** 요청을 적용합니다. 거절하면 RejectMsg 를 돌려주고 상태는 바뀌지 않습니다. */
    fun onRequest(req: PhoneRequest): RejectMsg? {
        val cur = _state.value
        val seatId = req.seatId

        fun reject(reason: String) = RejectMsg(
            ts = now(), requestType = req.type, reason = reason, seatId = seatId,
        )

        when (req.type) {
            ReqType.SEAT_CLAIM -> {
                val seat = cur.seats.firstOrNull { it.seatId == seatId } ?: return reject(RejectReason.SEAT_TAKEN)
                if (!seat.occupied) return reject(RejectReason.SEAT_TAKEN)
                if (seat.deviceId != null && seat.deviceId != req.deviceId) return reject(RejectReason.SEAT_TAKEN)

                var next = cur.copy(seats = cur.seats.map {
                    if (it.seatId != seatId) it
                    else it.copy(deviceId = req.deviceId, nickname = req.nickname, connected = true)
                })
                if (next.turnSeatId == null) next = next.copy(turnSeatId = nextTurn(next, from = null))
                commit(next)
            }

            ReqType.QUEUE_ADD -> {
                if (cur.turnSeatId != seatId) return reject(RejectReason.NOT_YOUR_TURN)
                val seat = cur.seats.firstOrNull { it.seatId == seatId } ?: return reject(RejectReason.SEAT_TAKEN)
                val title = req.title ?: return reject(RejectReason.PROFILE_BLOCKED)

                // 아동 좌석은 아주 긴 곡을 넣지 못합니다 (좌석 프로필 제한의 최소 구현)
                val duration = req.durationMs ?: DEFAULT_DURATION_MS
                if (seat.profile == SeatProfile.CHILD && duration > CHILD_MAX_DURATION_MS) {
                    return reject(RejectReason.PROFILE_BLOCKED)
                }
                // 한 좌석이 큐를 독점하지 못하게 막습니다
                if (cur.queue.count { it.requestedBySeat == seatId } >= MAX_PENDING_PER_SEAT) {
                    return reject(RejectReason.PROFILE_BLOCKED)
                }

                val track = TrackDto(
                    itemId = "q_${itemSeq++}",
                    title = title,
                    artist = req.artist.orEmpty(),
                    durationMs = duration,
                    requestedBySeat = seat.seatId,
                )
                var next = cur.copy(queue = cur.queue + track)
                if (next.nowPlaying == null) next = playNext(next)
                next = next.copy(turnSeatId = nextTurn(next, from = seatId))
                commit(next)
            }

            ReqType.QUEUE_REMOVE -> {
                val item = cur.queue.firstOrNull { it.itemId == req.itemId } ?: return null
                if (item.requestedBySeat != seatId) return reject(RejectReason.PROFILE_BLOCKED)
                commit(cur.copy(queue = cur.queue - item))
            }

            ReqType.VOTE_SKIP -> {
                val seat = cur.seats.firstOrNull { it.seatId == seatId } ?: return reject(RejectReason.SEAT_TAKEN)
                // 주행 중 운전자의 화면 조작은 막습니다. 운전자는 음성으로 참여합니다.
                if (cur.driveState == DriveState.DRIVING.name && seat.profile == SeatProfile.DRIVER) {
                    return reject(RejectReason.DRIVING_RESTRICTED)
                }
                if (seatId in cur.skipVotes) return reject(RejectReason.DUPLICATE_VOTE)

                val votes = cur.skipVotes + seatId!!
                val next = if (votes.size >= cur.skipThreshold) playNext(cur) else cur.copy(skipVotes = votes)
                commit(next)
            }

            ReqType.TURN_PASS -> {
                if (cur.turnSeatId != seatId) return reject(RejectReason.NOT_YOUR_TURN)
                commit(cur.copy(turnSeatId = nextTurn(cur, from = seatId)))
            }

            ReqType.REACTION_LIKE -> Unit // 집계만, 상태 변화 없음

            else -> Unit
        }
        return null
    }

    // ---------- 운전자 음성 조작 ----------

    /** 운전자의 "다음 곡". 주행 중에도 허용되는 유일한 경로입니다. */
    fun driverSkip() = commit(playNext(_state.value))

    /** 운전자의 "내 차례 넘겨". */
    fun driverPassTurn() {
        val cur = _state.value
        if (cur.turnSeatId == SeatId.R1L) commit(cur.copy(turnSeatId = nextTurn(cur, from = SeatId.R1L)))
    }

    /** 재생이 끝났을 때. */
    fun trackFinished() = commit(playNext(_state.value))

    /** 재생 위치 갱신 (PlaybackGateway 가 주기적으로 호출). */
    fun updatePosition(positionMs: Int) {
        val cur = _state.value
        val np = cur.nowPlaying ?: return
        commit(cur.copy(nowPlaying = np.copy(positionMs = positionMs)))
    }

    // ---------- 내부 ----------

    private fun commit(next: SessionState) {
        _state.value = next.copy(ts = now())
    }

    private fun playNext(s: SessionState): SessionState {
        val head = s.queue.firstOrNull()
        return s.copy(
            nowPlaying = head?.copy(positionMs = 0),
            queue = if (head == null) emptyList() else s.queue.drop(1),
            skipVotes = emptyList(),
        )
    }

    /** 착석한 좌석들 사이에서 라운드로빈. 참여(폰 연결) 여부와 무관하게 좌석 기준입니다. */
    private fun nextTurn(s: SessionState, from: String?): String? {
        val eligible = SeatId.TURN_ORDER.filter { id -> s.seats.any { it.seatId == id && it.occupied } }
        if (eligible.isEmpty()) return null
        val at = eligible.indexOf(from)
        return eligible[(at + 1) % eligible.size]
    }

    companion object {
        const val DEFAULT_DURATION_MS = 210_000
        const val CHILD_MAX_DURATION_MS = 400_000
        const val MAX_PENDING_PER_SEAT = 2

        fun defaultProfile(seatId: String) = when (seatId) {
            SeatId.R1L -> SeatProfile.DRIVER
            SeatId.R2C, SeatId.R2R -> SeatProfile.CHILD
            else -> SeatProfile.ADULT
        }

        /** 스킵 과반 기준. 최소 2표를 요구해 1명이 임의로 넘기지 못하게 합니다. */
        fun thresholdFor(seats: List<SeatDto>): Int {
            val n = seats.count { it.occupied }
            return maxOf(2, (n + 1) / 2)
        }
    }
}
