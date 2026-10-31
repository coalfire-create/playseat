package dev.semicolon.playseat.car

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.semicolon.playseat.car.net.HubClient
import dev.semicolon.playseat.car.session.PhoneRequest
import dev.semicolon.playseat.car.session.SessionEngine
import dev.semicolon.playseat.car.session.SessionState
import dev.semicolon.playseat.car.session.TurnNotifyMsg
import dev.semicolon.playseat.car.vehicle.FakeVehicleSignalSource
import dev.semicolon.playseat.car.vehicle.VehicleSignalSourceFactory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 차량 신호 → 세션 엔진 → 허브 송신을 잇습니다.
 *
 * 세션 판정은 전부 SessionEngine 이 하고, 이 클래스는 배선만 담당합니다.
 */
class CarViewModel(app: Application) : AndroidViewModel(app) {

    private val vehicle = VehicleSignalSourceFactory.create(app)
    private val engine = SessionEngine(BuildConfig.SESSION_ID)

    private val hub: HubClient = HubClient(
        sessionId = BuildConfig.SESSION_ID,
        host = BuildConfig.HUB_HOST,
        onRequest = ::handleRequest,
    )

    /** 폰 요청을 엔진에 넘기고, 거절되면 그 폰에 이유를 돌려줍니다. */
    private fun handleRequest(req: PhoneRequest) {
        engine.onRequest(req)?.let { hub.send(it) }
    }

    val state: StateFlow<SessionState> = engine.state

    /** Fake 신호원일 때만 디버그 조작을 노출합니다. */
    val fake: FakeVehicleSignalSource? = vehicle as? FakeVehicleSignalSource

    val usingRealVehicleSignals: Boolean = vehicle.isReal

    /** 동승자 폰이 열 주소. 차량 화면 QR 에 담깁니다. */
    val joinUrl: String = "http://${BuildConfig.HUB_HOST}/?s=${BuildConfig.SESSION_ID}"

    init {
        vehicle.start()

        viewModelScope.launch {
            vehicle.snapshot.collect { engine.onVehicle(it) }
        }
        viewModelScope.launch {
            engine.state.collect { hub.send(it) }
        }
        // 차례가 바뀔 때만 해당 좌석에 알림을 보냅니다
        viewModelScope.launch {
            engine.state.map { it.turnSeatId }.distinctUntilChanged().collect { seatId ->
                if (seatId != null) {
                    hub.send(TurnNotifyMsg(ts = System.currentTimeMillis(), seatId = seatId))
                    // TODO(Pleos): TextToSpeech SDK 로 "다음은 OO 차례입니다" 안내
                }
            }
        }

        hub.connect()
    }

    // 운전자 조작 — 주행 중에는 이 경로만 허용됩니다
    fun driverSkip() = engine.driverSkip()
    fun driverPassTurn() = engine.driverPassTurn()

    override fun onCleared() {
        hub.close()
        vehicle.stop()
        super.onCleared()
    }
}
