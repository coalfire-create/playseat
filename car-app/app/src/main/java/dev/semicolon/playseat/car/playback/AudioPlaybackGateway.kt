package dev.semicolon.playseat.car.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import dev.semicolon.playseat.car.BuildConfig
import dev.semicolon.playseat.car.session.TrackDto
import dev.semicolon.playseat.car.session.playMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 실제로 소리가 나는 재생.
 *
 * 허브가 공개 API 의 **30초 미리듣기** 를 중계하고([previewUrl][TrackDto.previewUrl]),
 * 이 클래스가 그것을 `android.media.MediaPlayer` 로 재생합니다. 전체 음원이 아닙니다 —
 * 플레이시트는 음원을 보유하지 않고, 보유한 척해서도 안 됩니다. 정식 서비스에서는
 * 같은 인터페이스를 사업자 음원 세션으로 교체합니다([PlaybackGateway] 의 2단계).
 *
 * 차 안이라서 신경 쓴 세 가지
 *
 *  **오디오 포커스**. 내비 안내가 나오는 순간 음악이 겹치면 그 안내를 못 듣습니다.
 *  그건 음악의 문제가 아니라 주행의 문제입니다. `AudioFocusRequest` 로 정식으로
 *  포커스를 요청하고, 잃으면 멈추고 되찾으면 이어 갑니다. 볼륨만 줄이는 더킹을
 *  쓰지 않고 아예 일시정지하는 쪽을 고른 이유는, 미리듣기가 30초뿐이라 줄여서
 *  겹쳐 놓으면 안내가 끝났을 때 곡도 끝나 있기 때문입니다.
 *  (`setWillPauseWhenDucked(true)` 로 시스템에 그 의도를 알립니다.)
 *
 *  **MediaSession**. 헤드유닛의 미디어 위젯과 스티어링휠 미디어 키가 이 세션을
 *  봅니다. 운전자는 플레이시트 화면을 보고 있지 않아도 손을 떼지 않고 재생·정지·
 *  다음을 쓸 수 있어야 합니다. 그래서 곡 메타데이터와 재생 상태를 올리고,
 *  콜백을 열어 둡니다. "다음" 은 곡이 자연히 끝난 것과 똑같은 경로로 보냅니다 —
 *  스킵과 완료가 서로 다른 길로 가면 좌석 턴 계산이 둘로 갈라집니다.
 *
 *  **실패해도 큐는 멈추지 않습니다**. 미리듣기 주소가 없거나 MediaPlayer 가
 *  오류를 내면 조용히 시간만 흐르는 시뮬레이션으로 떨어져 끝까지 갑니다.
 *  소리가 안 나는 것과 세션이 멈추는 것은 전혀 다른 문제입니다. 좌석 턴·스킵
 *  투표·도착 맞춤은 곡이 끝났다는 신호 하나로 돌아가고, 그 신호가 안 오면
 *  차량 화면이 그 자리에서 굳습니다. 심사 중에 굳으면 되돌릴 방법이 없습니다.
 */
class AudioPlaybackGateway(
    context: Context,
    private val scope: CoroutineScope,
    /** 재생이 안 될 때 큐를 계속 굴려 줄 시뮬레이션. 같은 콜백을 그대로 넘깁니다. */
    private val fallback: PlaybackGateway,
) : PlaybackGateway {

    override val isReal = true

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val session = MediaSession(appContext, SESSION_TAG)

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var focus: AudioFocusRequest? = null

    private var track: TrackDto? = null
    private var onPosition: ((Int) -> Unit)? = null
    private var onCompleted: (() -> Unit)? = null

    /**
     * 지금 재생이 몇 번째 곡인지.
     *
     * MediaPlayer 콜백은 비동기라서, 곡을 넘긴 뒤에 이전 곡의 prepare·오류 콜백이
     * 도착할 수 있습니다. 그 콜백이 새 곡의 재생을 건드리면 한 곡을 건너뛰거나
     * 두 곡이 겹칩니다. 세대 번호가 다르면 그냥 버립니다.
     */
    private var generation = 0

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // 내비 안내·전화 벨처럼 잠깐 가져가는 경우. 돌아오면 이어 갑니다.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pausePlayback()
            AudioManager.AUDIOFOCUS_GAIN -> resumePlayback()
            // 다른 미디어 앱이 아예 가져간 경우. 소리는 포기하되 세션은 계속 돕니다.
            AudioManager.AUDIOFOCUS_LOSS -> giveUpAudio()
        }
    }

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resumePlayback()
            override fun onPause() = pausePlayback()
            // 완료와 같은 경로. 스킵이 별도 경로를 타면 턴 계산이 갈라집니다.
            override fun onSkipToNext() = finishTrack()
            override fun onStop() = stop()
        })
        session.isActive = true
        publishState(PlaybackState.STATE_NONE, 0)
    }

    // ---------- PlaybackGateway ----------

    override fun start(track: TrackDto, onPosition: (Int) -> Unit, onCompleted: () -> Unit) {
        releasePlayer()
        stopTicker()
        fallback.stop()

        generation++
        val gen = generation
        this.track = track
        this.onPosition = onPosition
        this.onCompleted = onCompleted

        // 헤드유닛 위젯은 소리가 나든 안 나든 지금 곡을 보여 줘야 합니다.
        publishMetadata(track)

        val url = resolveUrl(track.previewUrl)
        if (url == null) {
            fallBack(track, "미리듣기 주소가 없습니다")
            return
        }
        if (!requestFocus()) {
            // 포커스를 안 줬는데 그냥 소리를 내면 내비 안내 위에 음악을 덮습니다.
            fallBack(track, "오디오 포커스를 받지 못했습니다")
            return
        }

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attributes)
            mp.setOnPreparedListener { prepared ->
                if (gen != generation) return@setOnPreparedListener
                // 앱이 재시작된 뒤 이어받는 경우에만 걸립니다. 보통은 0 입니다.
                val from = track.positionMs ?: 0
                if (from in 1 until prepared.duration) prepared.seekTo(from)
                prepared.start()
                publishState(PlaybackState.STATE_PLAYING, position())
                startTicker()
            }
            mp.setOnCompletionListener {
                if (gen != generation) return@setOnCompletionListener
                finishTrack()
            }
            mp.setOnErrorListener { _, what, extra ->
                // 네트워크가 끊겼거나 중계가 400/502 를 돌려준 경우입니다.
                if (gen == generation) fallBack(track, "MediaPlayer 오류 what=$what extra=$extra")
                true
            }
            mp.setDataSource(url)
            // prepare() 를 쓰면 네트워크 음원에서 UI 스레드가 몇 초 멈춥니다.
            mp.prepareAsync()
        } catch (e: Exception) {
            fallBack(track, "음원을 열 수 없습니다: ${e.message}")
        }
    }

    override fun stop() {
        generation++
        stopTicker()
        releasePlayer()
        abandonFocus()
        fallback.stop()
        track = null
        onPosition = null
        onCompleted = null
        publishState(PlaybackState.STATE_STOPPED, 0)
    }

    override fun release() {
        stop()
        fallback.release()
        session.isActive = false
        session.release()
    }

    // ---------- 재생 제어 ----------

    private fun pausePlayback() {
        val mp = player ?: return
        if (!runCatching { mp.isPlaying }.getOrDefault(false)) return
        runCatching { mp.pause() }
        stopTicker()
        publishState(PlaybackState.STATE_PAUSED, position())
    }

    private fun resumePlayback() {
        val mp = player ?: return
        if (runCatching { mp.isPlaying }.getOrDefault(false)) return
        runCatching { mp.start() }.onFailure {
            Log.w(TAG, "재개 실패: ${it.message}")
            return
        }
        publishState(PlaybackState.STATE_PLAYING, position())
        startTicker()
    }

    /**
     * 곡이 끝났다(또는 스티어링휠에서 "다음"을 눌렀다).
     *
     * 세션 엔진에 완료를 알리는 유일한 지점입니다. 콜백을 비워 두는 것은, 늦게
     * 도착한 타이머가 같은 곡의 완료를 두 번 올려 한 곡을 건너뛰는 것을 막기 위해서입니다.
     */
    private fun finishTrack() {
        stopTicker()
        releasePlayer()
        abandonFocus()
        fallback.stop()
        publishState(PlaybackState.STATE_STOPPED, 0)
        val done = onCompleted
        onCompleted = null
        done?.invoke()
    }

    /**
     * 소리는 포기하고 큐만 계속 굴립니다.
     *
     * 미리듣기가 없을 때, 재생이 실패했을 때, 다른 앱이 미디어를 아예 가져갔을 때
     * 들어옵니다. 재생 상태를 PLAYING 으로 올려 두는 이유는 헤드유닛 위젯이 보는
     * 것이 '지금 이 세션의 곡' 이고, 소리가 없어도 큐는 실제로 흐르고 있기 때문입니다.
     */
    private fun fallBack(track: TrackDto, why: String) {
        Log.w(TAG, "시뮬레이션으로 떨어집니다 — ${track.title}: $why")
        stopTicker()
        releasePlayer()
        abandonFocus()
        publishState(PlaybackState.STATE_PLAYING, track.positionMs ?: 0)
        fallback.start(
            track = track,
            onPosition = { pos -> onPosition?.invoke(pos) },
            onCompleted = { finishTrack() },
        )
    }

    private fun giveUpAudio() {
        val current = track ?: return
        if (player == null) return
        fallBack(current.copy(positionMs = position()), "오디오 포커스를 영구히 잃었습니다")
    }

    // ---------- 위치 보고 ----------

    /**
     * 재생 위치를 0.5초마다 올립니다.
     *
     * 1초로 하면 차량 화면의 진행 바가 눈에 보이게 끊깁니다. 미리듣기는 30초뿐이라
     * 1초가 전체의 3%여서 더 두드러집니다.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val mp = player ?: break
                if (!runCatching { mp.isPlaying }.getOrDefault(false)) continue
                onPosition?.invoke(position())
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * MediaPlayer 를 정리합니다.
     *
     * 먼저 참조를 비우는 이유는, 이 메서드가 MediaPlayer 자신의 오류 콜백 안에서도
     * 불리기 때문입니다. 비우기 전에 release() 를 하면 그 사이에 돌던 위치 보고
     * 타이머가 이미 풀린 객체를 건드립니다.
     */
    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        runCatching {
            mp.setOnPreparedListener(null)
            mp.setOnCompletionListener(null)
            mp.setOnErrorListener(null)
            mp.reset()
            mp.release()
        }
    }

    private fun position(): Int =
        runCatching { player?.currentPosition }.getOrNull() ?: 0

    // ---------- 오디오 포커스 ----------

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // 더킹 대신 일시정지를 쓴다고 시스템에 알립니다. 그러면 더킹 상황에서도
            // DUCK 이 아니라 LOSS_TRANSIENT 로 알려 줍니다.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focus = if (granted) request else null
        return granted
    }

    private fun abandonFocus() {
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
    }

    // ---------- MediaSession ----------

    private fun publishMetadata(track: TrackDto) {
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            // 위젯의 진행 바는 '실제로 흐를 시간' 을 알아야 맞게 그려집니다.
            // 미리듣기면 30초이지 곡 전체 길이가 아닙니다.
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track.playMs.toLong())
        track.artworkUrl?.let { meta.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it) }
        runCatching { session.setMetadata(meta.build()) }
    }

    private fun publishState(state: Int, positionMs: Int) {
        val builder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_STOP
            )
            .setState(
                state,
                positionMs.toLong(),
                if (state == PlaybackState.STATE_PLAYING) 1f else 0f,
            )
        runCatching { session.setPlaybackState(builder.build()) }
    }

    // ---------- 주소 조립 ----------

    /**
     * 허브가 준 경로를 실제 주소로 만듭니다.
     *
     * 허브는 `"/audio?t=..."` 처럼 **경로만** 줍니다. 자기 주소(시연장 노트북 IP,
     * 에뮬레이터의 10.0.2.2 등)를 모르기 때문입니다. 호스트는 차량만 알고 있어서
     * 차량이 붙입니다. 2단계에서 사업자 음원 주소가 절대 주소로 내려오는 경우도
     * 있을 수 있어서, 이미 절대 주소면 그대로 씁니다.
     */
    private fun resolveUrl(previewUrl: String?): String? {
        val raw = previewUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val path = if (raw.startsWith("/")) raw else "/$raw"
        return "http://${BuildConfig.HUB_HOST}$path"
    }

    companion object {
        private const val TAG = "PlaySeatAudio"
        private const val SESSION_TAG = "PlaySeat"
        private const val TICK_MS = 500L
    }
}
