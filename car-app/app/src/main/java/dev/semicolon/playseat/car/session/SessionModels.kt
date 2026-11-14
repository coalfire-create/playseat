package dev.semicolon.playseat.car.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** contract/protocol.md v0.1 의 메시지 모델. 필드 이름을 계약과 똑같이 유지합니다. */

@Serializable
data class SeatDto(
    val seatId: String,
    val occupied: Boolean,
    val deviceId: String? = null,
    val nickname: String? = null,
    val profile: String,
    val connected: Boolean = false,
)

@Serializable
data class TrackDto(
    val itemId: String,
    val title: String,
    val artist: String,
    val durationMs: Int,
    val requestedBySeat: String,
    val positionMs: Int? = null,
    /**
     * 앨범 커버 주소 (v0.4).
     *
     * 허브의 검색 결과에 실려 오는 값을 폰이 그대로 넘겨 줍니다.
     * 큐레이션 목록 곡은 비어 있을 수 있으므로 없을 때를 항상 그려야 합니다.
     */
    val artworkUrl: String? = null,
    /**
     * 실제로 소리가 나는 음원 주소 (v0.7).
     *
     * 허브가 중계하는 주소입니다. 플레이시트는 음원을 보유하지 않고, 공개 API 가
     * 제공하는 30초 미리듣기를 허브가 받아 차량에 흘려 줍니다. 비어 있으면
     * 소리 없이 시간만 흐르는 시뮬레이션으로 떨어집니다 — 재생이 안 되는 것과
     * 큐가 멈추는 것은 다른 문제여서, 한쪽이 없어도 다른 쪽은 돌아야 합니다.
     */
    val previewUrl: String? = null,
    /**
     * 이 곡이 실제로 재생되는 길이 (v0.7).
     *
     * 미리듣기면 30초입니다. [durationMs] 는 곡의 원래 길이여서 화면에 그대로
     * 보여 주되, 큐 길이·도착 맞춤 계산은 '실제로 흐를 시간' 으로 해야 맞습니다.
     * null 이면 [durationMs] 를 씁니다.
     */
    val playableMs: Int? = null,
)

/** 큐 계산에 쓰는 길이. 화면에 쓰는 [TrackDto.durationMs] 와 구분합니다. */
val TrackDto.playMs: Int get() = playableMs ?: durationMs

/** 차량이 내려보내는 단일 진실 상태. */
@Serializable
data class SessionState(
    val type: String = "state.sync",
    val ts: Long = 0,
    val sessionId: String,
    val driveState: String = "PARKED",
    val seats: List<SeatDto> = emptyList(),
    val nowPlaying: TrackDto? = null,
    val queue: List<TrackDto> = emptyList(),
    val etaSeconds: Int? = null,
    val queueTargetMs: Long? = null,
    val skipVotes: List<String> = emptyList(),
    val skipThreshold: Int = 2,
    /** NORMAL | CHARGING_LOUNGE. 충전 중임을 알리는 표시용입니다. */
    val mode: String = SessionMode.NORMAL,
    /** 도착 5분 이내. 안내용이며 큐 추가를 막지는 않습니다. */
    val landing: Boolean = false,
    /** 현재 큐의 총 재생 길이. queueTargetMs 와 비교해 "도착까지 몇 곡 더"를 보여줍니다. */
    val queueTotalMs: Long = 0,
    /**
     * 지금 차 안의 분위기 (v0.5).
     *
     * 차량 신호(와이퍼·전조등·조향·충전·도착)로 판정한 값입니다.
     * [moodReason] 은 사람에게 보여 줄 근거 문장입니다 — 판정이 틀렸을 때
     * 왜 그렇게 봤는지가 보여야 사람이 고쳐 줍니다.
     */
    val moodId: String? = null,
    val moodLabel: String? = null,
    val moodReason: String? = null,

    /**
     * 아무도 곡을 넣지 않을 때 차가 권하는 곡 (v0.5).
     *
     * [suggestAutoAddMs] 가 0 이 되면 이 곡들이 큐에 들어갑니다. 아무나
     * `mood.accept` 로 지금 담거나 `mood.dismiss` 로 이번에는 그만둘 수 있습니다.
     */
    val suggestions: List<TrackDto> = emptyList(),
    val suggestAutoAddMs: Int? = null,

    /**
     * 차량 화면에 뜬 네 자리 탑승 코드 (v0.7).
     *
     * QR 만으로 좌석을 잡게 하면, 주차장에서 찍어 둔 사진 한 장으로 남의 차
     * 플레이리스트에 들어올 수 있습니다. 코드는 차 안 화면에만 있으므로
     * '그 차에 지금 타고 있다' 는 사실을 증명합니다. 착석 센서와 둘을
     * 같이 보는 이유입니다 — 앉아 있고, 화면을 볼 수 있어야 참여합니다.
     */
    val joinCode: String? = null,

    /**
     * 도착 맞춤 편성 (v0.7).
     *
     * 폰의 플레이리스트는 도착을 모릅니다. 그래서 늘 마지막 곡이 중간에
     * 끊기고, 사람은 그걸 당연하게 여깁니다. 차는 남은 시간을 알기 때문에
     * '마지막 곡이 도착과 함께 끝나는' 편성을 만들 수 있습니다.
     *
     * [arrivalSlackMs] 는 도착까지 남는 시간입니다. 음수면 그만큼 잘립니다.
     * [cutIndex] 는 도착이 걸쳐 버리는 큐 위치(0부터), 없으면 null.
     * [fitPlan] 은 사람에게 보여 줄 한 문장이고, [canFitArrival] 가 true 면
     * 순서를 바꿔 딱 맞출 수 있다는 뜻입니다 (`queue.fit` 요청).
     */
    val arrivalSlackMs: Long? = null,
    val cutIndex: Int? = null,
    val fitPlan: String? = null,
    val canFitArrival: Boolean = false,

    /**
     * 좌석별 선곡 지분 (v0.7).
     *
     * 차례를 돌리지 않기로 했으므로 한 사람이 계속 넣을 수 있습니다. 그래서
     * 규칙으로 막는 대신 보이게 합니다. 사람은 숫자로 혼나는 것보다 숫자를
     * 보고 스스로 비켜 줍니다. 막지 않고 보여 주기만 하는 것이 요점입니다.
     */
    val shares: List<SeatShare> = emptyList(),
    val shareNote: String? = null,
) {
    companion object {

        /**
         * 큐가 빈 채로 이만큼 지나면 추천을 띄웁니다.
         *
         * 곡이 끝나자마자 권하면 아직 고르는 중인 사람의 선택을 가로챕니다.
         * 짧게 기다렸다가, 그래도 아무도 넣지 않으면 그때 끼어듭니다.
         */
        const val SUGGEST_AFTER_MS = 12_000

        /** 추천을 띄운 뒤 자동으로 담기까지. 담기 전에 누구나 멈출 수 있습니다. */
        const val SUGGEST_AUTO_ADD_MS = 20_000

        /** `mood.dismiss` 뒤 다시 권하지 않는 시간. */
        const val SUGGEST_SNOOZE_MS = 10 * 60_000L
    }
}

/**
 * 이번 주행에서 한 좌석이 가져간 선곡 지분.
 *
 * [playedMs] 는 실제로 흐른 시간입니다. 넣기만 하고 스킵된 곡까지 지분으로
 * 세면 "넣은 사람" 이 아니라 "들린 사람" 이 과대평가됩니다.
 */
@Serializable
data class SeatShare(
    val seatId: String,
    val tracks: Int,
    val playedMs: Long,
    val percent: Int,
)

/** 차가 스스로 담은 곡의 "선곡한 좌석". 좌석 이름 자리에 이것이 옵니다. */
object AutoPick {
    const val SEAT = "AUTO"
    const val LABEL = "분위기 추천"
}

object SessionMode {
    const val NORMAL = "NORMAL"
    const val CHARGING_LOUNGE = "CHARGING_LOUNGE"
}

/** 주행이 끝나면 한 번 전송합니다. 이동 1회를 하나의 기록 단위로 만드는 리텐션 장치입니다. */
@Serializable
data class DriveAlbumMsg(
    val type: String = "drive.album",
    val ts: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val tracks: List<AlbumTrack> = emptyList(),
    val seats: List<AlbumSeat> = emptyList(),
)

@Serializable
data class AlbumTrack(val title: String, val artist: String, val requestedBySeat: String)

@Serializable
data class AlbumSeat(val seatId: String, val nickname: String? = null)

/**
 * 폰이 보내는 요청.
 *
 * 타입마다 쓰는 필드가 다르지만, 2주 일정에서는 sealed 계층보다 관대한 단일 모델이
 * 훨씬 다루기 쉽습니다. 검증은 SessionEngine 이 합니다.
 */
@Serializable
data class PhoneRequest(
    val type: String,
    val deviceId: String? = null,
    val seatId: String? = null,
    val nickname: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Int? = null,
    val externalId: String? = null,
    val itemId: String? = null,
    val artworkUrl: String? = null,
    /** 허브 검색 결과에 실려 온 값을 폰이 그대로 넘겨 줍니다 (v0.7). */
    val previewUrl: String? = null,
    val playableMs: Int? = null,
    /** 차량 화면의 네 자리 코드. `seat.claim` 에서만 봅니다 (v0.7). */
    val joinCode: String? = null,
)

@Serializable
data class RejectMsg(
    val type: String = "reject",
    val ts: Long,
    val requestType: String,
    val reason: String,
    val seatId: String? = null,
)

object ReqType {
    const val SEAT_CLAIM = "seat.claim"
    const val QUEUE_ADD = "queue.add"
    const val QUEUE_REMOVE = "queue.remove"
    const val VOTE_SKIP = "vote.skip"
    const val REACTION_LIKE = "reaction.like"

    /** 추천을 지금 담기 / 이번에는 그만두기 (v0.5). */
    const val MOOD_ACCEPT = "mood.accept"
    const val MOOD_DISMISS = "mood.dismiss"

    /** 도착에 맞춰 큐 순서를 바꾸기 (v0.7). */
    const val QUEUE_FIT = "queue.fit"
}

object RejectReason {
    const val PROFILE_BLOCKED = "PROFILE_BLOCKED"
    const val SEAT_TAKEN = "SEAT_TAKEN"
    const val DUPLICATE_VOTE = "DUPLICATE_VOTE"
    const val DRIVING_RESTRICTED = "DRIVING_RESTRICTED"

    /** 차량 화면의 코드와 다릅니다 (v0.7). 그 차에 타고 있지 않다는 뜻입니다. */
    const val BAD_JOIN_CODE = "BAD_JOIN_CODE"

    /** 어떻게 바꿔도 도착에 맞출 수 없습니다 (v0.7). */
    const val NO_FIT = "NO_FIT"
}
