package dev.semicolon.playseat.car.playback

import dev.semicolon.playseat.car.session.TrackDto

/**
 * 재생 계층.
 *
 * 플레이시트는 음원을 직접 보유하지 않습니다. 선곡을 '요청'으로 받고, 실제 재생은
 * 차량에 설치·로그인된 음악 앱에 위임합니다. 그래서 음원 라이선스는 기존 스트리밍
 * 사업자가 그대로 가지고, 우리는 '누가 무엇을 들을지 결정하는 레이어'만 맡습니다.
 *
 * 이 인터페이스가 그 경계입니다. 구현을 바꿔도 세션 로직은 영향받지 않습니다.
 */
interface PlaybackGateway {
    /** 실제 음악 앱으로 소리가 나가는지. false 면 시간만 흐르는 시뮬레이션입니다. */
    val isReal: Boolean

    fun start(track: TrackDto, onPosition: (Int) -> Unit, onCompleted: () -> Unit)
    fun stop()
    fun release()
}
