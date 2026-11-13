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
)

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
}

object RejectReason {
    const val PROFILE_BLOCKED = "PROFILE_BLOCKED"
    const val SEAT_TAKEN = "SEAT_TAKEN"
    const val DUPLICATE_VOTE = "DUPLICATE_VOTE"
    const val DRIVING_RESTRICTED = "DRIVING_RESTRICTED"
}
