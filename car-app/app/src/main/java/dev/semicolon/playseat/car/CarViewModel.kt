package dev.semicolon.playseat.car

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.semicolon.playseat.car.net.HubClient
import dev.semicolon.playseat.car.playback.PlaybackGateway
import dev.semicolon.playseat.car.playback.PlaybackGatewayFactory
import dev.semicolon.playseat.car.session.DriveAlbumMsg
import dev.semicolon.playseat.car.session.PhoneRequest
import dev.semicolon.playseat.car.session.SessionEngine
import dev.semicolon.playseat.car.session.SessionState
import dev.semicolon.playseat.car.session.TurnNotifyMsg
import dev.semicolon.playseat.car.vehicle.FakeVehicleSignalSource
import dev.semicolon.playseat.car.vehicle.SeatId
import dev.semicolon.playseat.car.vehicle.VehicleSignalSourceFactory
import dev.semicolon.playseat.car.voice.VoiceCommands
import dev.semicolon.playseat.car.voice.VoiceController
import dev.semicolon.playseat.car.voice.VoiceControllerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 차량 신호 → 세션 엔진 → 허브·재생·음성을 잇습니다.
 *
 * 판정은 전부 SessionEngine 이 하고 이 클래스는 배선만 담당합니다.
 */
class CarViewModel(app: Application) : AndroidViewModel(app) {

    private val vehicle = VehicleSignalSourceFactory.create(app)
    private val engine = SessionEngine(BuildConfig.SESSION_ID)
    private val voice: VoiceController = VoiceControllerFactory.create(app)
    private val playback: PlaybackGateway =
        PlaybackGatewayFactory.create(app, viewModelScope, BuildConfig.PLAYBACK_SPEED)

    private val hub: HubClient = HubClient(
        sessionId = BuildConfig.SESSION_ID,
        host = BuildConfig.HUB_HOST,
        onRequest = ::handleRequest,
    )

    val state: StateFlow<SessionState> = engine.state

    private val _lastAlbum = MutableStateFlow<DriveAlbumMsg?>(null)
    /** 가장 최근 주행의 드라이브 앨범. 정차 화면에 카드로 보여 줍니다. */
    val lastAlbum: StateFlow<DriveAlbumMsg?> = _lastAlbum

    private val _voiceStatus = MutableStateFlow<String?>(null)
    /** 음성 인식 진행 상황. 운전자에게 짧게만 보여 줍니다. */
    val voiceStatus: StateFlow<String?> = _voiceStatus

    /** Fake 신호원일 때만 디버그 조작을 노출합니다. */
    val fake: FakeVehicleSignalSource? = vehicle as? FakeVehicleSignalSource

    val usingRealVehicleSignals: Boolean = vehicle.isReal
    val usingRealPlayback: Boolean = playback.isReal
    val canListen: Boolean get() = voice.canListen

    /** 동승자 폰이 열 주소. 차량 화면 QR 에 담깁니다. */
    val joinUrl: String = "http://${BuildConfig.HUB_HOST}/?s=${BuildConfig.SESSION_ID}"

    init {
        vehicle.start()

        engine.onDriveAlbum = { album ->
            _lastAlbum.value = album
            hub.send(album)
            voice.speak("도착했습니다. 함께 들은 ${album.tracks.size}곡을 정리했습니다.")
        }

        viewModelScope.launch { vehicle.snapshot.collect { engine.onVehicle(it) } }
        viewModelScope.launch { engine.state.collect { hub.send(it) } }

        // 차례가 바뀔 때만 알림과 안내 음성을 냅니다
        viewModelScope.launch {
            engine.state.map { it.turnSeatId }.distinctUntilChanged().collect { seatId ->
                if (seatId == null) return@collect
                hub.send(TurnNotifyMsg(ts = System.currentTimeMillis(), seatId = seatId))
                voice.speak(
                    if (seatId == SeatId.R1L) "운전석 차례입니다. 말씀하세요."
                    else "${seatLabel(seatId)} 차례입니다."
                )
            }
        }

        // 곡이 바뀔 때만 재생을 다시 시작합니다. 위치 갱신으로는 재시작하지 않습니다.
        viewModelScope.launch {
            engine.state.map { it.nowPlaying?.itemId }.distinctUntilChanged().collect { itemId ->
                val track = engine.state.value.nowPlaying
                if (itemId == null || track == null) {
                    playback.stop()
                    return@collect
                }
                playback.start(
                    track = track,
                    onPosition = { engine.updatePosition(it) },
                    onCompleted = { engine.trackFinished() },
                )
                if (engine.state.value.driveState == "DRIVING") {
                    voice.speak("${seatLabel(track.requestedBySeat)}이 고른 ${track.title}")
                }
            }
        }

        // 랜딩 구간 진입 안내
        viewModelScope.launch {
            engine.state.map { it.landing }.distinctUntilChanged().collect { landing ->
                if (landing) voice.speak("도착 5분 전입니다.")
            }
        }

        // Fake 신호원일 때 잔여 ETA 를 실제로 흐르게 합니다
        fake?.let { f ->
            viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    f.tick()
                }
            }
        }

        hub.connect()
    }

    /** 폰 요청을 엔진에 넘기고, 거절되면 그 폰에 이유를 돌려줍니다. */
    private fun handleRequest(req: PhoneRequest) {
        engine.onRequest(req)?.let { hub.send(it) }
    }

    // ---------- 운전자 조작: 주행 중 열려 있는 유일한 경로 ----------

    fun driverSkip() = engine.driverSkip()
    fun driverPassTurn() = engine.driverPassTurn()

    /** 음성 버튼. 한 번 눌러 한 명령을 받습니다. */
    fun startListening() {
        if (!voice.canListen) {
            _voiceStatus.value = "마이크 권한이 필요합니다"
            return
        }
        _voiceStatus.value = "듣고 있습니다…"
        voice.listen(
            onResult = { text ->
                when (VoiceCommands.parse(text)) {
                    VoiceCommands.Command.NEXT_TRACK -> {
                        _voiceStatus.value = "다음 곡"
                        engine.driverSkip()
                    }
                    VoiceCommands.Command.PASS_TURN -> {
                        _voiceStatus.value = "차례를 넘겼습니다"
                        engine.driverPassTurn()
                    }
                    VoiceCommands.Command.LIKE -> {
                        _voiceStatus.value = "저장했습니다"
                        // TODO: 좋아요 기록을 드라이브 앨범에 표시
                    }
                    VoiceCommands.Command.UNKNOWN ->
                        _voiceStatus.value = "\"$text\" — 알아듣지 못했습니다"
                }
            },
            onError = { msg -> _voiceStatus.value = msg },
        )
    }

    fun clearVoiceStatus() { _voiceStatus.value = null }

    override fun onCleared() {
        playback.release()
        voice.release()
        hub.close()
        vehicle.stop()
        super.onCleared()
    }

    private fun seatLabel(seatId: String) = when (seatId) {
        SeatId.R1L -> "운전석"
        SeatId.R1R -> "조수석"
        SeatId.R2L -> "2열 왼쪽"
        SeatId.R2C -> "2열 가운데"
        SeatId.R2R -> "2열 오른쪽"
        else -> seatId
    }
}
