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
    const val REJECT = "reject"

    // 폰 → 차량
    const val SEAT_CLAIM = "seat.claim"
    const val QUEUE_ADD = "queue.add"
    const val QUEUE_REMOVE = "queue.remove"
    const val VOTE_SKIP = "vote.skip"
    const val REACTION_LIKE = "reaction.like"

    // 허브가 단독 처리 (차량 경유 안 함)
    const val SEARCH_QUERY = "search.query"
    const val SEARCH_RESULT = "search.result"

    // 무드 추천 (v0.5). 차가 분위기를 판정해 묻고, 허브가 곡을 고른다.
    const val MOOD_QUERY = "mood.query"
    const val MOOD_RESULT = "mood.result"

    // 폰 → 차량 (v0.5). 추천을 지금 담거나, 이번에는 넣지 말라고 한다.
    const val MOOD_ACCEPT = "mood.accept"
    const val MOOD_DISMISS = "mood.dismiss"

    // 허브가 생성
    const val CAR_OFFLINE = "car.offline"
    const val CAR_ONLINE = "car.online"
    const val HUB_ERROR = "hub.error"
}

/** 허브가 단독 처리하는 타입 — 차량으로 넘기지 않는다. */
val HUB_HANDLED = setOf(MsgType.SEARCH_QUERY)

/** 차량이 허브에 직접 묻는 타입 — 폰으로 뿌리지 않는다. */
val CAR_ASKS_HUB = setOf(MsgType.MOOD_QUERY)
