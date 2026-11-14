package dev.semicolon.playseat.car.playback

import dev.semicolon.playseat.car.session.TrackDto
import dev.semicolon.playseat.car.session.playMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 소리 없이 시간만 흐르는 재생.
 *
 * 차량 음악 앱 위임이 붙기 전에도 큐가 실제로 진행되고 위치가 흐르게 해서,
 * 좌석 턴·스킵 투표·랜딩 구간·드라이브 앨범을 전부 검증할 수 있게 합니다.
 *
 * [speed] 를 올리면 재생이 빨라집니다. 2시간 40분 주행 시나리오를 수십 초로 압축해
 * 시연 영상을 녹화할 때 씁니다 (BuildConfig.PLAYBACK_SPEED).
 */
class SimulatedPlaybackGateway(
    private val scope: CoroutineScope,
    private val speed: Int = 1,
) : PlaybackGateway {

    override val isReal = false
    private var job: Job? = null

    override fun start(track: TrackDto, onPosition: (Int) -> Unit, onCompleted: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            var pos = track.positionMs ?: 0
            val step = TICK_MS * speed.coerceAtLeast(1)
            // '실제로 흐를 길이' 로 셉니다. 미리듣기 음원은 30초인데 원곡 길이로
            // 재면 소리가 끝난 뒤에도 위치가 계속 올라가, 화면에 0:56 / 0:30 처럼
            // 길이를 넘긴 숫자가 뜹니다.
            while (isActive && pos < track.playMs) {
                delay(TICK_MS.toLong())
                pos += step
                onPosition(pos.coerceAtMost(track.playMs))
            }
            if (isActive) onCompleted()
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun release() = stop()

    companion object { private const val TICK_MS = 1000 }
}
