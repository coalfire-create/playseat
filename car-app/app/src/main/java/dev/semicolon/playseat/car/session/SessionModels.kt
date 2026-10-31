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
)

/** 차량이 내려보내는 단일 진실 상태. */
@Serializable
data class SessionState(
    val type: String = "state.sync",
    val ts: Long = 0,
    val sessionId: String,
    val driveState: String = "PARKED",
    val seats: List<SeatDto> = emptyList(),
    val turnSeatId: String? = null,
    val turnDeadlineMs: Int = TURN_DEADLINE_MS,
    val nowPlaying: TrackDto? = null,
    val queue: List<TrackDto> = emptyList(),
    val etaSeconds: Int? = null,
    val queueTargetMs: Long? = null,
    val skipVotes: List<String> = emptyList(),
    val skipThreshold: Int = 2,
) {
    companion object { const val TURN_DEADLINE_MS = 30_000 }
}

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
)

@Serializable
data class RejectMsg(
    val type: String = "reject",
    val ts: Long,
    val requestType: String,
    val reason: String,
    val seatId: String? = null,
)

@Serializable
data class TurnNotifyMsg(
    val type: String = "turn.notify",
    val ts: Long,
    val seatId: String,
    val deadlineMs: Int = SessionState.TURN_DEADLINE_MS,
)

object ReqType {
    const val SEAT_CLAIM = "seat.claim"
    const val QUEUE_ADD = "queue.add"
    const val QUEUE_REMOVE = "queue.remove"
    const val VOTE_SKIP = "vote.skip"
    const val REACTION_LIKE = "reaction.like"
    const val TURN_PASS = "turn.pass"
}

object RejectReason {
    const val NOT_YOUR_TURN = "NOT_YOUR_TURN"
    const val PROFILE_BLOCKED = "PROFILE_BLOCKED"
    const val SEAT_TAKEN = "SEAT_TAKEN"
    const val DUPLICATE_VOTE = "DUPLICATE_VOTE"
    const val DRIVING_RESTRICTED = "DRIVING_RESTRICTED"
}
