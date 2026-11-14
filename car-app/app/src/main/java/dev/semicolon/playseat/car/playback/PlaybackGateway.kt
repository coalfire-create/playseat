package dev.semicolon.playseat.car.playback

import android.content.Context
import dev.semicolon.playseat.car.session.TrackDto
import kotlinx.coroutines.CoroutineScope

/**
 * 재생 계층.
 *
 * 플레이시트는 음원을 보유하지 않습니다. 이 앱이 맡는 것은 '차 안의 누가 무엇을
 * 들을지 결정하는 레이어' 이고, 음원은 끝까지 남의 것입니다. 그래서 이 인터페이스를
 * 경계로 두고, 어디서 소리가 나오는지는 구현을 갈아끼워 바꿉니다.
 *
 * 단계별 계획
 *
 *  1단계 — 지금. [AudioPlaybackGateway]
 *     허브가 공개 API(Deezer)의 **30초 미리듣기** 를 중계하고, 차량이 그것을
 *     MediaPlayer 로 실제로 재생합니다. 전체 음원이 아니라 30초가 끝입니다.
 *     그래도 "플레이리스트 앱인데 소리가 안 난다" 는 상태보다는, 한계가 분명한
 *     소리가 실제로 나는 편이 보여 줄 것이 있습니다.
 *
 *  2단계 — 사업자 음원 세션 위임 (미구현)
 *     차량에 설치·로그인된 음악 앱에 재생을 넘기는 방식입니다. 음원 라이선스는
 *     기존 스트리밍 사업자가 그대로 가지고, 우리는 선곡만 넘깁니다.
 *     설계는 이렇습니다.
 *       - MediaSessionManager 로 활성 MediaSession 을 찾아 MediaController 를 얻는다
 *       - MediaController.TransportControls.playFromSearch("제목 아티스트", null)
 *         → 그 앱이 메타데이터로 곡을 찾아 재생하므로 우리가 음원을 들고 있지 않아도 된다
 *       - 재생 위치·완료는 MediaController.Callback.onPlaybackStateChanged 로 받는다
 *     걸림돌이 두 개 남아 있어서 이번 제출에는 넣지 않았습니다.
 *       - getActiveSessions(ComponentName) 는 MEDIA_CONTENT_CONTROL(시스템 권한)
 *         또는 NotificationListenerService 등록이 필요합니다. Pleos 가 외부 앱에
 *         어떤 경로를 허용하는지 확인되지 않았습니다.
 *       - 앱마다 playFromSearch 지원 범위가 달라, 실패 시 큐의 다음 곡으로 넘기는
 *         폴백이 필요합니다. 1단계의 폴백 구조를 그대로 쓸 수 있습니다.
 *     바뀌는 것은 이 인터페이스의 구현 하나뿐이고, 세션 로직·허브·폰 웹앱은 그대로입니다.
 */
interface PlaybackGateway {
    /** 실제로 소리가 나가는 경로인지. false 면 시간만 흐르는 시뮬레이션입니다. */
    val isReal: Boolean

    fun start(track: TrackDto, onPosition: (Int) -> Unit, onCompleted: () -> Unit)
    fun stop()
    fun release()
}

/**
 * 어떤 재생 구현을 쓸지 고릅니다.
 *
 * [speed] 가 1보다 크면 시연 영상 녹화 모드입니다. 2시간 40분 주행 시나리오를 수십
 * 초로 압축해 녹화할 때 쓰는데, 실제 음원은 그렇게 빨리 돌릴 수 없으므로 그때는
 * 시뮬레이션만 씁니다. 평소에는 실제 재생을 쓰고, 그 안에서 미리듣기가 없거나
 * 재생이 실패하면 같은 시뮬레이션으로 조용히 떨어집니다.
 *
 * 시그니처는 고정입니다 — CarViewModel 이 이 함수만 보고 있습니다.
 */
object PlaybackGatewayFactory {
    fun create(context: Context, scope: CoroutineScope, speed: Int): PlaybackGateway {
        val simulated = SimulatedPlaybackGateway(scope, speed)
        return if (speed > 1) simulated else AudioPlaybackGateway(context, scope, simulated)
    }
}
