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
 * 좌석 권한, 스킵 과반, 분위기 추천을 모두 여기서 판정합니다. 서버는 중계만
 * 하므로 이 클래스가 곧 플레이시트의 규칙입니다. 안드로이드 의존성이 없어
 * 단위 테스트가 됩니다.
 *
 * 선곡은 차례로 돌리지 않습니다
 *   앉은 사람은 누구나, 언제든, 몇 곡이든 넣습니다. 차례를 돌리면 "지금 듣고
 *   싶은 곡" 이 아니라 "내 차례에 쓸 곡" 을 고르게 되고, 듣고 싶은 것이 생겨도
 *   30초를 기다려야 합니다. 차 안에서 그 30초는 그냥 어색한 침묵입니다.
 *   한 사람이 연달아 넣는 것도 막지 않습니다 — 같이 탄 사람들은 서로 보고
 *   있으므로, 규칙보다 눈치가 먼저 작동합니다. 그래도 불만이면 스킵 투표가
 *   있습니다(과반, 최소 2표).
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

    /** 주행이 끝났을 때 드라이브 앨범을 전달합니다. CarViewModel 이 허브로 보냅니다. */
    var onDriveAlbum: ((DriveAlbumMsg) -> Unit)? = null

    // 현재 주행 구간의 기록. 정차 → 주행에서 시작하고, 주행 → 정차에서 앨범으로 내보냅니다.
    private var tripStartedAt: Long? = null
    private val tripTracks = mutableListOf<AlbumTrack>()

    // 큐가 빈 채로 흐른 시간을 재기 위한 시각. 곡이 들어오면 지웁니다.
    private var emptySince: Long? = null

    // 추천을 띄운 시각. 자동 담기 카운트다운의 기준입니다.
    private var suggestedAt: Long? = null

    // mood.dismiss 로 미뤄 둔 시각. 이 뒤 SUGGEST_SNOOZE_MS 동안 권하지 않습니다.
    private var snoozedAt: Long? = null

    /**
     * 추천 곡을 받아 와야 한다고 알립니다. CarViewModel 이 허브에 묻습니다.
     *
     * 엔진이 직접 네트워크를 쓰지 않는 이유는, 이 클래스가 안드로이드도 네트워크도
     * 모르는 순수 로직이어야 테스트에서 전부 돌릴 수 있기 때문입니다.
     */
    var onNeedSuggestions: ((moodId: String, kidsAboard: Boolean, exclude: List<String>) -> Unit)? = null

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

        val driving = snapshot.driveState == DriveState.DRIVING

        var next = cur.copy(
            seats = seats,
            queue = queue,
            skipVotes = votes,
            driveState = snapshot.driveState.name,
            etaSeconds = snapshot.etaSeconds,
            queueTargetMs = snapshot.etaSeconds?.let { it * 1000L },
            skipThreshold = thresholdFor(seats),
            queueTotalMs = queue.sumOf { it.durationMs.toLong() },
            // 충전 중 정차면 라운지 모드. 차 안에 갇혀 있는 대기 시간에는
            // 차례를 기다리게 할 이유가 없어 턴 규칙을 풉니다.
            mode = if (snapshot.charging && !driving) SessionMode.CHARGING_LOUNGE else SessionMode.NORMAL,
            // 도착 5분 이내. 안내용 플래그입니다.
            landing = driving && (snapshot.etaSeconds ?: Int.MAX_VALUE) <= LANDING_SECONDS,
        )

        // 주행 구간 경계를 잡아 드라이브 앨범을 만듭니다
        val wasDriving = cur.driveState == DriveState.DRIVING.name
        if (!wasDriving && driving) {
            tripStartedAt = now()
            tripTracks.clear()
        } else if (wasDriving && !driving) {
            emitAlbum(next)
        }

        commit(next)
    }

    // ---------- 폰 요청 ----------

    // ---------- 분위기 ----------

    /** 분위기 판정 결과를 상태에 싣습니다. 값이 그대로면 아무 일도 하지 않습니다. */
    fun onMood(id: String, label: String, reason: String) {
        val cur = _state.value
        if (cur.moodId == id && cur.moodReason == reason) return
        // 분위기가 바뀌면 이전 분위기로 고른 추천은 버립니다.
        val keep = cur.moodId == id
        commit(
            cur.copy(
                moodId = id, moodLabel = label, moodReason = reason,
                suggestions = if (keep) cur.suggestions else emptyList(),
                suggestAutoAddMs = if (keep) cur.suggestAutoAddMs else null,
            )
        )
        if (!keep) suggestedAt = null
    }

    /** 허브가 돌려준 추천 곡. 비어 있으면 아무것도 띄우지 않습니다. */
    fun onSuggestions(items: List<TrackDto>) {
        if (items.isEmpty()) return
        val cur = _state.value
        // 그 사이에 누가 곡을 넣었으면 끼어들지 않습니다.
        if (cur.queue.isNotEmpty() || cur.nowPlaying != null) return
        suggestedAt = now()
        commit(cur.copy(
            suggestions = items.map { it.copy(requestedBySeat = AutoPick.SEAT) },
            suggestAutoAddMs = SessionState.SUGGEST_AUTO_ADD_MS,
        ))
    }

    /** 추천을 큐에 담습니다. 자동 담기와 `mood.accept` 가 같은 경로를 씁니다. */
    private fun acceptSuggestions(s: SessionState): SessionState {
        if (s.suggestions.isEmpty()) return s
        val queued = s.suggestions.map { it.copy(itemId = "i${itemSeq++}") }
        suggestedAt = null
        emptySince = null
        val withQueue = s.copy(queue = s.queue + queued, suggestions = emptyList(), suggestAutoAddMs = null)
        // 큐에만 넣고 끝내면 아무 소리도 안 납니다. 비어 있었으니 바로 첫 곡을 겁니다.
        return if (withQueue.nowPlaying == null) playNext(withQueue) else withQueue
    }

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

                commit(cur.copy(seats = cur.seats.map {
                    if (it.seatId != seatId) it
                    else it.copy(deviceId = req.deviceId, nickname = req.nickname, connected = true)
                }))
            }

            ReqType.MOOD_ACCEPT -> commit(acceptSuggestions(cur))

            ReqType.MOOD_DISMISS -> {
                snoozedAt = now()
                suggestedAt = null
                commit(cur.copy(suggestions = emptyList(), suggestAutoAddMs = null))
            }

            ReqType.QUEUE_ADD -> {
                // 앉아 있으면 누구나, 언제든, 몇 곡이든 넣습니다. 차례도 좌석별
                // 상한도 두지 않습니다 — 듣고 싶은 곡을 듣고 싶을 때 넣는 것이
                // 플레이리스트이고, 순서를 강제하면 그게 아니게 됩니다.
                val seat = cur.seats.firstOrNull { it.seatId == seatId } ?: return reject(RejectReason.SEAT_TAKEN)
                if (!seat.occupied) return reject(RejectReason.SEAT_TAKEN)
                val title = req.title ?: return reject(RejectReason.PROFILE_BLOCKED)

                // 아동 좌석은 아주 긴 곡을 넣지 못합니다 (좌석 프로필 제한의 최소 구현)
                val duration = req.durationMs ?: DEFAULT_DURATION_MS
                if (seat.profile == SeatProfile.CHILD && duration > CHILD_MAX_DURATION_MS) {
                    return reject(RejectReason.PROFILE_BLOCKED)
                }

                val track = TrackDto(
                    itemId = "q_${itemSeq++}",
                    title = title,
                    artist = req.artist.orEmpty(),
                    durationMs = duration,
                    requestedBySeat = seat.seatId,
                    artworkUrl = req.artworkUrl,
                )
                var next = cur.copy(queue = cur.queue + track)
                if (next.nowPlaying == null) next = playNext(next)
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

            ReqType.REACTION_LIKE -> Unit // 집계만, 상태 변화 없음

            else -> Unit
        }
        return null
    }

    // ---------- 운전자 음성 조작 ----------

    /** 운전자의 "다음 곡". 주행 중에도 허용되는 유일한 경로입니다. */
    fun driverSkip() = commit(playNext(_state.value))

    /** 재생이 끝났을 때. */
    fun trackFinished() = commit(playNext(_state.value))

    /** 1초마다 호출합니다. 지금은 추천 타이머만 돕니다. */
    fun tick() = tickSuggestions()

    /**
     * 음악이 없을 때 차가 끼어드는 지점.
     *
     * 1) 큐도 재생도 비면 시계를 켭니다.
     * 2) [SessionState.SUGGEST_AFTER_MS] 가 지나도 그대로면 추천을 부릅니다.
     * 3) 추천이 떠 있는 동안 카운트다운을 내리고, 0 이 되면 담습니다.
     *
     * 아무도 타지 않았거나 아무도 폰을 연결하지 않았으면 끼어들지 않습니다.
     * 들을 사람이 없는데 음악이 켜지는 것만큼 당황스러운 것도 없습니다.
     */
    private fun tickSuggestions() {
        val cur = _state.value
        val t = now()

        if (cur.suggestions.isNotEmpty()) {
            val left = (SessionState.SUGGEST_AUTO_ADD_MS - (t - (suggestedAt ?: t))).toInt()
            if (left <= 0) commit(acceptSuggestions(cur))
            else if (left / 1000 != (cur.suggestAutoAddMs ?: -1) / 1000) {
                commit(cur.copy(suggestAutoAddMs = left))
            }
            return
        }

        val silent = cur.nowPlaying == null && cur.queue.isEmpty()
        if (!silent) { emptySince = null; return }

        val anyoneAboard = cur.seats.any { it.occupied } && cur.seats.any { it.connected }
        if (!anyoneAboard) { emptySince = null; return }

        val snoozed = snoozedAt?.let { t - it < SessionState.SUGGEST_SNOOZE_MS } == true
        if (snoozed) return

        val since = emptySince ?: t.also { emptySince = it }
        if (t - since < SessionState.SUGGEST_AFTER_MS) return

        emptySince = t   // 응답이 없어도 다시 묻기까지 같은 간격을 둡니다
        val mood = cur.moodId ?: return
        val kids = cur.seats.any { it.occupied && it.profile == SeatProfile.CHILD }
        onNeedSuggestions?.invoke(mood, kids, emptyList())
    }

    /** 재생 위치 갱신 (PlaybackGateway 가 주기적으로 호출). */
    fun updatePosition(positionMs: Int) {
        val cur = _state.value
        val np = cur.nowPlaying ?: return
        commit(cur.copy(nowPlaying = np.copy(positionMs = positionMs)))
    }

    // ---------- 내부 ----------

    private fun commit(next: SessionState) {
        _state.value = next.copy(
            ts = now(),
            queueTotalMs = next.queue.sumOf { it.durationMs.toLong() },
        )
    }

    /** 재생이 끝나거나 스킵된 곡을 주행 기록에 남깁니다. */
    private fun remember(track: TrackDto?) {
        if (track == null) return
        if (tripStartedAt == null) tripStartedAt = now()
        tripTracks += AlbumTrack(track.title, track.artist, track.requestedBySeat)
    }

    private fun emitAlbum(s: SessionState) {
        val started = tripStartedAt
        // 들은 곡이 없는 주행은 기록을 남기지 않습니다
        if (started == null || tripTracks.isEmpty()) {
            tripStartedAt = null
            tripTracks.clear()
            return
        }
        val ended = now()
        onDriveAlbum?.invoke(
            DriveAlbumMsg(
                ts = ended,
                startedAt = started,
                endedAt = ended,
                durationMs = ended - started,
                tracks = tripTracks.toList(),
                seats = s.seats.filter { it.nickname != null }
                    .map { AlbumSeat(it.seatId, it.nickname) },
            )
        )
        tripStartedAt = null
        tripTracks.clear()
    }

    private fun playNext(s: SessionState): SessionState {
        remember(s.nowPlaying)
        val head = s.queue.firstOrNull()
        return s.copy(
            nowPlaying = head?.copy(positionMs = 0),
            queue = if (head == null) emptyList() else s.queue.drop(1),
            skipVotes = emptyList(),
        )
    }

    companion object {
        const val DEFAULT_DURATION_MS = 210_000
        /** 도착 몇 초 전부터 랜딩 구간으로 볼지. */
        const val LANDING_SECONDS = 300
        const val CHILD_MAX_DURATION_MS = 400_000

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
