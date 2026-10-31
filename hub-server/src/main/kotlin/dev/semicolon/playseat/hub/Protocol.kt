package dev.semicolon.playseat.hub

/**
 * contract/protocol.md v0.1 의 메시지 타입.
 *
 * 허브는 메시지를 완전히 파싱하지 않는다. "type" 만 읽고 라우팅한다.
 * 판정(턴 순서, 좌석 권한, 스킵 과반)은 전부 차량 앱이 하기 때문에,
 * 허브가 본문 구조를 알 필요가 없다. 계약이 바뀌어도 허브는 그대로 돈다.
 */
object MsgType {
    // 차량 → 폰
    const val STATE_SYNC = "state.sync"
    const val TURN_NOTIFY = "turn.notify"
    const val REJECT = "reject"

    // 폰 → 차량
    const val SEAT_CLAIM = "seat.claim"
    const val QUEUE_ADD = "queue.add"
    const val QUEUE_REMOVE = "queue.remove"
    const val VOTE_SKIP = "vote.skip"
    const val REACTION_LIKE = "reaction.like"
    const val TURN_PASS = "turn.pass"

    // 허브가 단독 처리 (차량 경유 안 함)
    const val SEARCH_QUERY = "search.query"
    const val SEARCH_RESULT = "search.result"

    // 허브가 생성
    const val CAR_OFFLINE = "car.offline"
    const val CAR_ONLINE = "car.online"
    const val HUB_ERROR = "hub.error"
}

/** 허브가 단독 처리하는 타입 — 차량으로 넘기지 않는다. */
val HUB_HANDLED = setOf(MsgType.SEARCH_QUERY)
