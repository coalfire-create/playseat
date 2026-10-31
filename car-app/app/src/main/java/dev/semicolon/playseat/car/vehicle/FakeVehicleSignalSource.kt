package dev.semicolon.playseat.car.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 가짜 차량 신호원.
 *
 * Pleos 에뮬레이터 검증 전에도 세션 로직과 UI를 개발할 수 있게 합니다.
 * 화면의 디버그 버튼이 이 클래스를 직접 조작합니다.
 */
class FakeVehicleSignalSource : VehicleSignalSource {

    private val _snapshot = MutableStateFlow(
        VehicleSnapshot(
            seats = SeatId.ALL.map { id ->
                // 시작 상태: 운전석·조수석·2열 왼쪽 착석
                SeatSignal(id, occupied = id in setOf(SeatId.R1L, SeatId.R1R, SeatId.R2L))
            },
            driveState = DriveState.PARKED,
            etaSeconds = 9600,
        )
    )
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot
    override val isReal = false

    override fun start() = Unit
    override fun stop() = Unit

    fun toggleSeat(seatId: String) = _snapshot.update { s ->
        s.copy(seats = s.seats.map {
            if (it.seatId != seatId) it
            else it.copy(
                occupied = !it.occupied,
                seatedAt = if (!it.occupied) System.currentTimeMillis() else null,
            )
        })
    }

    fun toggleDriveState() = _snapshot.update { s ->
        s.copy(
            driveState = if (s.driveState == DriveState.PARKED) DriveState.DRIVING else DriveState.PARKED
        )
    }

    fun setEta(seconds: Int?) = _snapshot.update { it.copy(etaSeconds = seconds) }

    fun toggleCharging() = _snapshot.update { it.copy(charging = !it.charging) }
}
