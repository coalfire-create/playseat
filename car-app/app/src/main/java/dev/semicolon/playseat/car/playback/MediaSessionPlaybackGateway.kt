package dev.semicolon.playseat.car.playback

import android.content.Context
import android.util.Log
import dev.semicolon.playseat.car.session.TrackDto
import kotlinx.coroutines.CoroutineScope

/**
 * 차량의 음악 앱에 재생을 위임하는 구현 (미완).
 *
 * 설계
 *   1. MediaSessionManager 로 현재 활성 MediaSession 을 찾아 MediaController 를 얻는다
 *   2. AudioManager 로 오디오 포커스를 정리한다
 *   3. 좌석이 요청한 곡을 그 앱에서 찾아 재생한다
 *        - MediaController.TransportControls.playFromSearch(title + " " + artist, null)
 *          → 앱이 메타데이터로 곡을 찾아 재생하므로 우리가 음원을 들고 있지 않아도 된다
 *   4. 재생 위치·완료는 MediaController.Callback 의 onPlaybackStateChanged 로 받는다
 *
 * 걸림돌
 *   - getActiveSessions(ComponentName) 는 MEDIA_CONTENT_CONTROL(시스템 권한) 또는
 *     NotificationListenerService 등록이 필요하다. Pleos 에서 외부 앱에 어떤 경로를
 *     허용하는지 확인해야 한다.
 *   - 앱마다 playFromSearch 지원 범위가 달라, 실패 시 큐의 다음 곡으로 넘기는 폴백이 필요하다.
 *
 * 그때까지는 SimulatedPlaybackGateway 로 세션 로직 전체를 검증한다.
 */
class MediaSessionPlaybackGateway(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlaybackGateway {

    override val isReal = true

    override fun start(track: TrackDto, onPosition: (Int) -> Unit, onCompleted: () -> Unit) {
        // TODO(재생위임): MediaController.transportControls.playFromSearch(...)
        Log.w(TAG, "재생 위임 미구현 — ${track.title}")
    }

    override fun stop() {
        // TODO(재생위임): transportControls.pause() + 오디오 포커스 반환
    }

    override fun release() = stop()

    companion object {
        private const val TAG = "MediaPlayback"

        /** TODO(재생위임): MediaSession 접근 권한이 확보되었는지 실제로 확인하도록 바꿉니다. */
        fun isAvailable(context: Context): Boolean = false
    }
}

object PlaybackGatewayFactory {
    fun create(context: Context, scope: CoroutineScope, speed: Int): PlaybackGateway =
        if (MediaSessionPlaybackGateway.isAvailable(context)) MediaSessionPlaybackGateway(context, scope)
        else SimulatedPlaybackGateway(scope, speed)
}
