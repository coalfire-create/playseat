package dev.semicolon.playseat.car

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.res.Configuration
import dev.semicolon.playseat.car.mood.Mood
import dev.semicolon.playseat.car.mood.MoodEngine
import dev.semicolon.playseat.car.net.HubClient
import dev.semicolon.playseat.car.playback.PlaybackGateway
import dev.semicolon.playseat.car.playback.PlaybackGatewayFactory
import dev.semicolon.playseat.car.session.AutoPick
import dev.semicolon.playseat.car.session.DriveAlbumMsg
import dev.semicolon.playseat.car.session.PhoneRequest
import dev.semicolon.playseat.car.session.ReqType
import dev.semicolon.playseat.car.session.SessionEngine
import dev.semicolon.playseat.car.session.SessionState
import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.FakeVehicleSignalSource
import dev.semicolon.playseat.car.vehicle.PleosVehicleSignalSource
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
import kotlin.random.Random

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
        onSuggestions = { engine.onSuggestions(it) },
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

    /**
     * 차량 화면에 띄우는 네 자리 탑승 코드.
     *
     * 앱이 켜질 때 한 번 만듭니다. 주차장에 찍어 둔 QR 사진은 다음 주행에서
     * 이미 틀린 코드를 들고 있게 됩니다.
     */
    private val joinCode: String = Random.nextInt(1000, 10_000).toString()

    /**
     * 동승자 폰이 열 주소. 차량 화면 QR 에 담깁니다.
     *
     * 코드를 주소에 실어 두면 QR 로 들어온 폰은 코드를 자동으로 갖게 되고,
     * 사진만 가진 폰은 모릅니다 — 탄 사람에게는 아무 수고가 늘지 않습니다.
     */
    val joinUrl: String =
        "http://${BuildConfig.HUB_HOST}/?s=${BuildConfig.SESSION_ID}&c=$joinCode"

    init {
        vehicle.start()
        engine.setJoinCode(joinCode)
        // 시연 스크립트가 흉내 폰에 코드를 쥐여 주려면 읽을 데가 있어야 합니다.
        // 화면에 띄워 둔 네 자리이고 기기 안에만 남는 로그라 숨길 것이 없습니다.
        Log.i(TAG, "탑승 코드 $joinCode")

        // 야간 여부는 차량 설정(AAOS uiMode)이 쥐고 있습니다. 신호원에 넣어 주면
        // 분위기 판정이 와이퍼·전조등과 같은 자리에서 그 값을 봅니다.
        pushNightMode()

        // 추천이 필요할 때 허브에 묻습니다. 엔진은 네트워크를 모릅니다.
        engine.onNeedSuggestions = { moodId, kids, exclude ->
            hub.askMood(moodId, kids, exclude)
        }

        engine.onDriveAlbum = { album ->
            _lastAlbum.value = album
            hub.send(album)
            voice.speak("도착했습니다. 함께 들은 ${album.tracks.size}곡을 정리했습니다.")
        }

        viewModelScope.launch { vehicle.snapshot.collect { engine.onVehicle(it) } }
        // 탑승 코드는 허브로 내보내지 않습니다.
        //
        // 상태를 그대로 뿌리면 코드가 모든 폰에 같이 갑니다. 그러면 QR 을 보지
        // 못한 사람도 소켓만 열면 코드를 읽어 좌석을 잡을 수 있어서, 코드가
        // 지키려던 것("그 차에 지금 타고 있다")을 코드 자신이 무너뜨립니다.
        // 코드가 있는 곳은 차 안 화면과 그 화면의 QR, 두 곳뿐이어야 합니다.
        viewModelScope.launch { engine.state.collect { hub.send(it.copy(joinCode = null)) } }

        // 곡이 바뀌면 누가 고른 무엇인지 읽어 줍니다. 주행 중에만입니다 —
        // 정차 중에는 운전자도 화면을 보면 되고, 매번 말하면 시끄럽습니다.
        viewModelScope.launch {
            engine.state
                .map { it.nowPlaying?.itemId to it.driveState }
                .distinctUntilChanged()
                .collect { (_, drive) ->
                    val np = engine.state.value.nowPlaying ?: return@collect
                    if (drive != DriveState.DRIVING.name) return@collect
                    voice.speak(
                        if (np.requestedBySeat == AutoPick.SEAT) "다음 곡은 ${np.title} 입니다."
                        else "다음 곡은 ${seatLabel(np.requestedBySeat)}에서 고른 ${np.title} 입니다."
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

        // 차량 신호 + 세션 상태 → 분위기. 1초마다 다시 봅니다.
        // 와이퍼가 꺼진 순간 바로 곡 분위기가 바뀌어야 하는데, 이벤트로만 묶으면
        // 둘 중 하나만 바뀌었을 때를 놓칩니다.
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val reading = MoodEngine.read(vehicle.snapshot.value, engine.state.value)
                engine.onMood(reading.id, reading.label, reading.reason)
            }
        }

        // 분위기가 바뀌면 실내 조명도 따라갑니다.
        // 운전자는 화면을 오래 볼 수 없으니, 바뀐 것을 조명으로 알립니다.
        viewModelScope.launch {
            engine.state.map { it.moodId }.distinctUntilChanged().collect { id ->
                val mood = Mood.byId(id) ?: return@collect
                vehicle.applyMoodColor(mood.argb)
            }
        }

        // 랜딩 구간 진입 안내
        viewModelScope.launch {
            engine.state.map { it.landing }.distinctUntilChanged().collect { landing ->
                if (landing) voice.speak("도착 5분 전입니다.")
            }
        }

        // 차례 제한시간을 흐르게 합니다. 재생 위치 갱신과 달리 곡이 없을 때도 돌아야 해서
        // 별도 루프를 둡니다. 멈춘 순서를 푸는 유일한 장치입니다.
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                engine.tick()
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

    /** 주간·야간 전환. MainActivity 의 설정 변경에서도 다시 불러 줍니다. */
    fun pushNightMode() {
        val night = (getApplication<Application>().resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        (vehicle as? PleosVehicleSignalSource)?.setNight(night)
        fake?.setNight(night)
    }

    /** 폰 요청을 엔진에 넘기고, 거절되면 그 폰에 이유를 돌려줍니다. */
    private fun handleRequest(req: PhoneRequest) {
        engine.onRequest(req)?.let { hub.send(it) }
    }

    // ---------- 운전자 조작: 주행 중 열려 있는 유일한 경로 ----------

    fun driverSkip() = engine.driverSkip()

    /**
     * 차량 화면의 "도착에 맞추기".
     *
     * 폰이 보내는 `queue.fit` 과 같은 경로로 들어갑니다 — 차 안 화면에서 눌러도
     * 판정은 엔진이 하고, 맞출 조합이 없으면 아무것도 바뀌지 않습니다. 버튼은
     * canFitArrival 일 때만 뜨므로 여기서 거절을 따로 알릴 데가 없습니다.
     */
    fun fitArrival() {
        engine.onRequest(PhoneRequest(type = ReqType.QUEUE_FIT))
    }

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

    // 좌석 이름은 엔진의 것을 그대로 씁니다. 음성 안내와 지분 문장이 다른 말을
    // 쓰면 같은 자리를 가리키는지 알 수 없습니다.
    private fun seatLabel(seatId: String) = SessionEngine.seatLabel(seatId)

    private companion object { const val TAG = "CarViewModel" }

}
