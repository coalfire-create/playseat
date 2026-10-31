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

/** 차량에서 읽어 오는 신호 묶음. */
data class VehicleSnapshot(
    val seats: List<SeatSignal>,
    val driveState: DriveState,
    /** 잔여 ETA(초). 내비 목적지가 없으면 null. */
    val etaSeconds: Int? = null,
    val charging: Boolean = false,
    val batteryPercent: Int? = null,
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
}
