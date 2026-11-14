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
 *
 * 그 대신 보여 줍니다
 *   차례를 없앤 뒤 "한 사람이 독점하면 어떻게 되나요" 라는 질문에 답이
 *   없어졌습니다. 답은 좌석 지분입니다 — 이번 구간에 좌석별로 실제로 흐른
 *   시간을 세어서 화면에 띠로 깔아 둡니다. 막지 않고, 경고하지도 않습니다.
 *   사람은 숫자로 혼나는 것보다 숫자를 보고 스스로 비켜 줍니다.
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

    // 이번 구간에 좌석별로 '실제로 흐른' 시간과 곡 수. 지분의 원본입니다.
    private val tripPlayedMs = mutableMapOf<String, Long>()
    private val tripPlayedCount = mutableMapOf<String, Int>()

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

    /**
     * 차량 화면에 띄울 네 자리 탑승 코드를 심습니다. CarViewModel 이 시작할 때 한 번 부릅니다.
     *
     * 엔진이 직접 난수를 만들지 않는 이유는 그러면 테스트마다 코드가 달라져서,
     * 좌석을 잡는 모든 검사가 코드를 먼저 알아내야 하기 때문입니다. 코드를
     * 심지 않은 세션(단위 테스트·Fake 신호)은 코드 검사 없이 돌아갑니다.
     */
    fun setJoinCode(code: String?) = commit(_state.value.copy(joinCode = code))

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
            queueTotalMs = queue.sumOf { it.playMs.toLong() },
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
            // 지분은 '이번 구간' 의 것입니다. 구간이 바뀌면 0부터 다시 셉니다 —
            // 어제 많이 들려준 사람이 오늘 비켜야 할 이유는 없습니다.
            tripPlayedMs.clear()
            tripPlayedCount.clear()
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
                // 탑승 코드는 차 안 화면에만 있습니다.
                //
                // QR 만 보면 주차장에서 찍어 둔 사진 한 장으로 남의 차
                // 플레이리스트에 들어올 수 있습니다. 그래서 착석 센서와 코드를
                // 같이 봅니다 — 앉아 있고(센서), 차 안 화면을 볼 수 있어야(코드)
                // 참여합니다. 비밀번호도 계정도 없이 "그 차에 지금 타고 있다" 를
                // 증명하는 방식이고, 처음 타는 사람이 가입할 일이 없습니다.
                // 코드를 심지 않은 세션은 그대로 통과시킵니다.
                cur.joinCode?.let { if (req.joinCode != it) return reject(RejectReason.BAD_JOIN_CODE) }

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
                    // 음원 주소와 '실제로 흐르는 길이' 는 허브 검색 결과에 실려
                    // 온 값을 폰이 그대로 넘겨 줍니다. 미리듣기면 30초여서,
                    // 이것이 없으면 도착 맞춤이 3분짜리 곡으로 계산합니다.
                    previewUrl = req.previewUrl,
                    playableMs = req.playableMs,
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

            ReqType.QUEUE_FIT -> {
                // 차가 멋대로 바꾸지 않습니다. 계산은 늘 돌지만 큐를 건드리는
                // 것은 사람이 누를 때뿐입니다 — 분위기 추천의 accept/dismiss 와
                // 같은 결입니다. 바꿀 조합이 없으면 아무것도 하지 않고 거절합니다.
                val fitted = arrivalPlan(cur)?.reorder ?: return reject(RejectReason.NO_FIT)
                commit(cur.copy(queue = fitted))
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

    /**
     * 상태를 내보내기 전에 계산해서 붙는 것들.
     *
     * 큐 길이·도착 맞춤·좌석 지분은 어느 요청에서 왔든 똑같이 다시 계산해야
     * 맞습니다. 요청마다 따로 쓰면 한 군데를 빼먹고, 그 한 군데에서만 화면이
     * 과거를 보여 줍니다. 길이는 durationMs 가 아니라 playMs 로 셉니다 —
     * 미리듣기 음원은 실제로 30초만 흐르기 때문입니다.
     */
    private fun commit(next: SessionState) {
        val shares = sharesNow()
        _state.value = withArrivalFit(
            next.copy(
                ts = now(),
                queueTotalMs = next.queue.sumOf { it.playMs.toLong() },
                shares = shares,
                shareNote = shareNote(shares),
            )
        )
    }

    /** 재생이 끝나거나 스킵된 곡을 주행 기록에 남깁니다. */
    private fun remember(track: TrackDto?) {
        if (track == null) return
        if (tripStartedAt == null) tripStartedAt = now()
        tripTracks += AlbumTrack(track.title, track.artist, track.requestedBySeat)

        // 지분은 '넣은 곡' 이 아니라 '실제로 흐른 시간' 입니다.
        //
        // 넣기만 하고 바로 스킵된 곡까지 지분으로 세면, 큐를 채운 사람이 아니라
        // 큐를 막은 사람이 가장 많이 들려준 것으로 보입니다. 재생 위치까지만
        // 세기 때문에 스킵된 곡은 흐른 만큼만 들어갑니다.
        val played = (track.positionMs ?: 0).coerceIn(0, track.playMs).toLong()
        if (played <= 0) return
        tripPlayedMs[track.requestedBySeat] = (tripPlayedMs[track.requestedBySeat] ?: 0L) + played
        tripPlayedCount[track.requestedBySeat] = (tripPlayedCount[track.requestedBySeat] ?: 0) + 1
    }

    // ---------- 좌석 지분 ----------

    /**
     * 이번 구간의 좌석별 지분.
     *
     * 퍼센트는 내림한 뒤 나머지를 큰 소수점 순으로 하나씩 돌려 합을 100 으로
     * 맞춥니다. 반올림만 하면 97% 나 102% 가 나오고, 그 순간 '지분' 이라는
     * 말이 무너집니다 — 화면에 깔린 띠는 가로를 꽉 채우고 있으니까요.
     */
    private fun sharesNow(): List<SeatShare> {
        val total = tripPlayedMs.values.sum()
        if (total <= 0L) return emptyList()

        val rows = tripPlayedMs.entries.sortedWith(
            compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
        )
        val exact = rows.map { it.value * 100.0 / total }
        val pct = exact.map { it.toInt() }.toMutableList()
        var left = 100 - pct.sum()
        for (i in exact.indices.sortedByDescending { exact[it] - pct[it] }) {
            if (left <= 0) break
            pct[i]++
            left--
        }
        return rows.mapIndexed { i, e ->
            SeatShare(e.key, tripPlayedCount[e.key] ?: 0, e.value, pct[i])
        }
    }

    /**
     * 한 좌석이 구간을 가져갔을 때 다는 한 문장.
     *
     * 막지 않습니다. 경고도 아니고 제한도 아니고, 사실만 한 줄 답니다. 심사에서
     * "독점은 어떻게 막나요" 라는 질문이 나오면 답은 '막지 않는다' 입니다 —
     * 차가 남의 선곡을 거절하는 순간 그건 같이 듣는 플레이리스트가 아니라
     * 차가 운영하는 방송이 됩니다. 대신 지분이 보이면 사람은 스스로 비켜 줍니다.
     * 한 곡 두 곡으로는 달지 않습니다. 세 곡 이상 흐른 뒤라야 '구간을 가져갔다'
     * 고 말할 수 있고, 그 전에는 그냥 먼저 넣은 사람입니다.
     */
    private fun shareNote(shares: List<SeatShare>): String? {
        val top = shares.firstOrNull() ?: return null
        if (top.percent < SHARE_NOTE_PERCENT || top.tracks < SHARE_NOTE_TRACKS) return null
        val who = seatLabel(top.seatId)
        return "$who${subjectParticle(who)} 이번 구간의 ${top.percent}%를 골랐습니다"
    }

    // ---------- 도착 맞춤 ----------

    /**
     * 도착까지의 편성을 읽어 상태의 네 필드를 채웁니다.
     *
     * 목적지가 없으면 네 필드를 모두 비웁니다. 내비를 켜지 않은 주행에서 도착
     * 맞춤은 없는 기능이어야 하고, 반쯤 켜진 기능은 틀린 기능보다 나쁩니다.
     */
    private fun withArrivalFit(s: SessionState): SessionState {
        val plan = arrivalPlan(s) ?: return s.copy(
            arrivalSlackMs = null, cutIndex = null, fitPlan = null, canFitArrival = false,
        )
        return s.copy(
            arrivalSlackMs = plan.slackMs,
            cutIndex = plan.cutIndex,
            fitPlan = plan.sentence,
            canFitArrival = plan.reorder != null,
        )
    }

    /**
     * 지금 곡의 잔여와 큐의 playMs 를 쌓아 도착선이 어디에 떨어지는지 봅니다.
     *
     * [slackMs] 는 도착선에서 '마지막으로 시작한 곡' 의 끝까지의 차이입니다.
     * 전부 도착 전에 끝나면 양수(남는 시간)이고, 한 곡이 도착선에 걸치면
     * 음수(그 곡이 잘리는 양)입니다. 두 경우가 같은 식으로 나오는 이유는
     * 사람이 묻는 것도 하나이기 때문입니다 — "도착할 때 음악은 어떻게 되나".
     *
     * 도착선 뒤에서 시작하는 곡은 잘린 것으로 세지 않습니다. 그 곡은 손해를
     * 본 것이 아니라 다음 구간의 첫 곡입니다.
     */
    private fun arrivalPlan(s: SessionState): ArrivalPlan? {
        val eta = s.etaSeconds ?: return null
        // 맞출 음악이 없으면 조용히 있습니다. "0곡 남았습니다" 는 정보가 아닙니다.
        if (s.nowPlaying == null && s.queue.isEmpty()) return null

        val remain = eta * 1000L
        val head = s.nowPlaying
            ?.let { (it.playMs - (it.positionMs ?: 0)).coerceAtLeast(0).toLong() } ?: 0L

        var acc = head                 // 지금까지 쌓인 끝 시각
        var slack = remain - head
        var cut: Int? = null
        if (slack >= 0) {
            for ((i, t) in s.queue.withIndex()) {
                if (acc >= remain) break   // 도착과 동시에 끝났습니다. 걸치는 곡이 없습니다
                val end = acc + t.playMs
                if (end > remain) {
                    cut = i
                    slack = remain - end
                    break
                }
                acc = end
                slack = remain - acc
            }
        }

        // 걸친 곡에서 실제로 들리는 부분. 이 값을 줄이는 것이 도착 맞춤의 목표입니다.
        val heard = if (cut == null) 0L else remain - acc
        // '잘렸다' 고 말하려면 그 곡을 듣고 있었어야 합니다.
        //
        // 고정값 하나로만 재면, 4분짜리 곡이 22초 흐른 뒤 도착한 경우를 "마지막
        // 곡이 4분 잘렸습니다" 라고 말하게 됩니다. 듣던 사람은 그 곡을 들었다고
        // 생각조차 하지 않는데 말이죠. 그건 잘린 곡이 아니라 다음 구간의 첫
        // 곡입니다. 그래서 곡 길이에 비례한 경계를 같이 봅니다.
        val edge = cut?.let { maxOf(TAIL_EDGE_MS, s.queue[it].playMs / 8L) } ?: TAIL_EDGE_MS
        val reorder = if (cut != null && heard > edge) fitByReorder(s, cut, heard) else null

        val sentence = when {
            // 전부 도착 전에 끝나고, 마지막 곡의 끝이 도착선에 닿았습니다
            cut == null && slack in 0..TAIL_EDGE_MS -> "마지막 곡이 도착과 함께 끝납니다"
            // 전부 도착 전에 끝납니다
            cut == null && slack >= 0 && s.queue.isEmpty() -> "도착까지 ${humanMs(slack)} 남습니다"
            cut == null && slack >= 0 -> "도착까지 ${s.queue.size}곡 · ${humanMs(slack)} 남습니다"
            // 큐가 아니라 지금 곡이 걸쳤습니다. 순서를 바꿔도 손댈 수 없습니다
            cut == null -> "지금 곡이 ${humanMs(-slack)} 잘립니다"
            // 걸친 곡이 막 시작하자마자 도착합니다 — 이것이 맞춰진 상태입니다
            heard <= edge && cut == s.queue.lastIndex -> "마지막 곡이 도착과 함께 끝납니다"
            heard <= edge ->
                "마지막 곡이 도착과 함께 끝납니다 · 뒤의 ${s.queue.size - cut - 1}곡은 다음 구간으로"
            cut == s.queue.lastIndex -> "마지막 곡이 ${humanMs(-slack)} 잘립니다"
            else ->
                "${cut + 1}번째 곡이 ${humanMs(-slack)} 잘립니다 · 뒤의 ${s.queue.size - cut - 1}곡은 다음 구간으로"
        }
        return ArrivalPlan(slack, cut, sentence, reorder)
    }

    /**
     * 꼬리에서 남은 틈에 가장 알맞은 곡 하나를 앞으로 당깁니다.
     *
     * 곡을 버리거나 잘라내지 않습니다 — 남의 선곡을 차가 지우면 그건 다른
     * 앱입니다. 밀려난 곡은 큐 뒤에 그대로 남아 다음 구간의 첫 곡이 됩니다.
     *
     * 탐색을 넓히지 않는 이유: 부분집합을 다 뒤지면 '차가 알아서 재편성한'
     * 목록이 나오고, 사람은 자기가 넣은 순서를 잃습니다. 한 곡만 당기면 무엇이
     * 바뀌었는지 눈으로 보이고, 마음에 안 들면 사람이 직접 되돌릴 수 있습니다.
     * 큐는 보통 10곡 이하라서 이 이상 똑똑해질 필요도 없습니다.
     */
    private fun fitByReorder(s: SessionState, cutIndex: Int, gapMs: Long): List<TrackDto>? {
        // 도착선에 걸친 곡 자신은 틈보다 길어서 후보가 되지 않습니다.
        val tail = s.queue.drop(cutIndex).withIndex().filter { it.value.playMs <= gapMs }
        if (tail.isEmpty()) return null

        // 좌석 공정성.
        //
        // 늘 '가장 잘 맞는 곡' 만 고르면 짧은 곡을 넣은 한 좌석이 매번 앞으로
        // 당겨지고, 긴 곡을 넣은 좌석의 곡만 계속 도착 뒤로 밀립니다. 순서를
        // 바꾸는 기능이 한 좌석의 선곡만 밀어내면 그건 편성이 아니라 편애입니다.
        // 그래서 틈을 비슷하게 채우는 곡이 여럿일 때는, 이미 도착 전 자리를
        // 차지한 곡이 적은 좌석의 것을 당깁니다.
        val aheadBySeat = s.queue.take(cutIndex).groupingBy { it.requestedBySeat }.eachCount()
        val pick = tail.minWithOrNull(
            compareBy(
                // 10초 단위로 묶습니다. 듣는 쪽에서 몇 초 차이는 같은 편성입니다.
                { (gapMs - it.value.playMs) / FIT_TIE_MS },
                { aheadBySeat[it.value.requestedBySeat] ?: 0 },
                { it.index },
            )
        )!!
        val moved = pick.value
        return s.queue.take(cutIndex) + moved +
            s.queue.drop(cutIndex).filterIndexed { i, _ -> i != pick.index }
    }

    private class ArrivalPlan(
        val slackMs: Long,
        val cutIndex: Int?,
        val sentence: String,
        /** 도착에 맞춘 큐. null 이면 바꿀 조합이 없습니다 (`queue.fit` 은 거절됩니다). */
        val reorder: List<TrackDto>?,
    )

    private fun emitAlbum(s: SessionState) {
        val started = tripStartedAt
        // 들은 곡이 없는 주행은 기록을 남기지 않습니다
        if (started == null || tripTracks.isEmpty()) {
            tripStartedAt = null
            tripTracks.clear()
            tripPlayedMs.clear()
            tripPlayedCount.clear()
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
        tripPlayedMs.clear()
        tripPlayedCount.clear()
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

        /**
         * 이만큼 안쪽에서 걸치면 '도착과 함께 끝났다' 고 봅니다.
         *
         * 도착선을 초 단위로 맞추려 들면 맞출 조합이 거의 없고, 사람도 20초
         * 전에 끝난 곡과 정확히 끝난 곡을 구분하지 못합니다. 주차하고 짐을
         * 챙기는 시간이 그보다 깁니다.
         */
        const val TAIL_EDGE_MS = 20_000L

        /** 틈을 이만큼 비슷하게 채우는 곡들은 같은 후보로 보고, 좌석으로 가릅니다. */
        const val FIT_TIE_MS = 10_000L

        /** 지분 문장을 다는 기준. 이 아래로는 그냥 먼저 넣은 사람입니다. */
        const val SHARE_NOTE_PERCENT = 60
        const val SHARE_NOTE_TRACKS = 3

        /**
         * 사람이 읽는 길이. 분·초로 자연스럽게 끊습니다.
         *
         * "100초" 나 "1.67분" 은 읽는 사람이 한 번 더 계산해야 합니다.
         * 차 안에서 화면을 흘깃 보는 1초 안에 들어와야 하는 숫자입니다.
         */
        fun humanMs(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            val m = s / 60
            val sec = s % 60
            return when {
                m == 0L -> "${sec}초"
                sec == 0L -> "${m}분"
                else -> "${m}분 ${sec}초"
            }
        }

        /** 사람에게 읽어 주는 좌석 이름. 음성 안내와 같은 말을 씁니다. */
        fun seatLabel(seatId: String) = when (seatId) {
            AutoPick.SEAT -> AutoPick.LABEL
            SeatId.R1L -> "운전석"
            SeatId.R1R -> "조수석"
            SeatId.R2L -> "2열 왼쪽"
            SeatId.R2C -> "2열 가운데"
            SeatId.R2R -> "2열 오른쪽"
            else -> seatId
        }

        /** 받침이 있으면 '이', 없으면 '가'. "2열 가운데이" 가 되지 않게 합니다. */
        fun subjectParticle(word: String): String {
            val last = word.lastOrNull() ?: return "이"
            if (last !in '가'..'힣') return "이"
            return if ((last - '가') % 28 == 0) "가" else "이"
        }

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
