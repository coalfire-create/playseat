package dev.semicolon.playseat.car.vehicle

import kotlinx.coroutines.flow.StateFlow

/**
 * 차량 신호 계층.
 *
 * Pleos Connect SDK 의존성을 이 패키지 안에만 둡니다. 세션 로직과 UI는 이 인터페이스만
 * 알기 때문에, SDK가 아직 연결되지 않았거나 특정 신호를 제공하지 않아도
 * FakeVehicleSignalSource 로 앱 전체가 빌드·실행됩니다.
 */

/** 좌석 식별자. contract/protocol.md 와 동일한 고정 문자열. */
object SeatId {
    const val R1L = "R1L" // 1열 좌 = 운전석 (좌핸들 가정)
    const val R1R = "R1R"
    const val R2L = "R2L"
    const val R2C = "R2C"
    const val R2R = "R2R"

    /** 턴이 돌아가는 순서. 운전석을 마지막에 둡니다. */
    val TURN_ORDER = listOf(R1R, R2L, R2C, R2R, R1L)
    val ALL = listOf(R1L, R1R, R2L, R2C, R2R)
}

object SeatProfile {
    const val DRIVER = "DRIVER"
    const val ADULT = "ADULT"
    const val CHILD = "CHILD"
    const val GUEST = "GUEST"
}

enum class DriveState { PARKED, DRIVING }

/** 좌석 하나의 물리 신호. */
data class SeatSignal(
    val seatId: String,
    val occupied: Boolean,
    /** 도어가 열렸다 닫힌 뒤 착석이 확인된 시각. 짐 적재와 탑승을 구분하는 데 씁니다. */
    val seatedAt: Long? = null,
)

/**
 * 와이퍼 단계.
 *
 * 차가 비를 아는 방법은 기상청이 아니라 와이퍼입니다. 위치로 날씨 API 를 부르면
 * 터널 안이나 지하주차장에서도 "맑음" 이라고 답하지만, 와이퍼는 지금 이 차 앞유리에
 * 비가 오는지를 말합니다. 세기까지 나누는 이유는 안개비와 폭우의 분위기가 다르기
 * 때문입니다. (Pleos `WiperState` → 이 단계로 옮겨 담습니다)
 */
enum class WiperLevel { OFF, INTERMITTENT, LOW, HIGH }

/**
 * 분위기 판정에 쓰는 환경 신호.
 *
 * 전부 Pleos Vehicle SDK 에서 실제로 읽을 수 있는 값입니다. 외기온은
 * SDK 공개 API 에 없어서 쓰지 않습니다 — 없는 신호를 쓰는 척하면
 * 실차에서 그 기능만 조용히 죽습니다.
 */
data class CabinEnvironment(
    val wiper: WiperLevel = WiperLevel.OFF,
    /** 하향등 점등. 어두워졌거나 터널에 들어갔다는 뜻입니다. */
    val lowBeamOn: Boolean = false,
    /** 차량 설정의 야간 모드. AAOS uiMode 를 그대로 따릅니다. */
    val night: Boolean = false,
    /**
     * 최근 조향 변동폭(도).
     *
     * 굽잇길인지 직선인지를 가릅니다. 산길·해안도로는 조향이 끊임없이 바뀌고
     * 고속도로는 거의 0 입니다. 지형을 묻는 API 없이 지형을 아는 방법입니다.
     */
    val steeringSwingDeg: Float = 0f,
    /** ECO / COMFORT / SPORT 등. 운전자가 고른 성향. */
    val drivingMode: String? = null,
)

/** 차량에서 읽어 오는 신호 묶음. */
data class VehicleSnapshot(
    val seats: List<SeatSignal>,
    val driveState: DriveState,
    /** 잔여 ETA(초). 내비 목적지가 없으면 null. */
    val etaSeconds: Int? = null,
    val charging: Boolean = false,
    val batteryPercent: Int? = null,
    val env: CabinEnvironment = CabinEnvironment(),
) {
    fun seat(seatId: String): SeatSignal? = seats.firstOrNull { it.seatId == seatId }

    companion object {
        fun empty() = VehicleSnapshot(
            seats = SeatId.ALL.map { SeatSignal(it, occupied = false) },
            driveState = DriveState.PARKED,
        )
    }
}

interface VehicleSignalSource {
    val snapshot: StateFlow<VehicleSnapshot>

    /** 신호를 실제 차량에서 받고 있는지. false면 Fake 로 동작 중입니다. */
    val isReal: Boolean

    fun start()
    fun stop()

    /**
     * 실내 앰비언트 조명을 무드 색으로 바꿉니다.
     *
     * 화면 안에서만 분위기가 바뀌면 운전자는 못 봅니다 — 운전자는 화면을
     * 오래 볼 수 없으니까요. 조명은 안 보고도 느껴집니다. 지원하지 않는
     * 차에서는 조용히 아무 일도 일어나지 않아야 하므로 기본 구현은 비어 있습니다.
     */
    fun applyMoodColor(argb: Int) = Unit
}
