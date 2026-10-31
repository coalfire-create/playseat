package dev.semicolon.playseat.car.vehicle

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pleos Connect SDK 로 실제 차량 신호를 읽는 구현.
 *
 * 아직 SDK 의존성이 연결되지 않았습니다. 지금은 사용 불가를 보고하고,
 * VehicleSignalSourceFactory 가 FakeVehicleSignalSource 로 넘깁니다.
 *
 * 연결 순서
 *   1. settings.gradle.kts 에 Pleos Maven 저장소 추가
 *   2. gradle/libs.versions.toml 의 pleos-* 좌표 확인 후 app/build.gradle.kts 에서 활성화
 *   3. 아래 TODO 위치에 SDK 호출을 채우고 isAvailable() 을 실제 연결 확인으로 바꿉니다
 *
 * 매핑 (기획서 5번 개발 계획과 동일)
 *   좌석 착석        Vehicle SDK · CAR_SEATS
 *   도어 개폐        Vehicle SDK · CAR_DOORS
 *   기어·주행 상태   Vehicle SDK · CAR_DRIVE
 *   충전·배터리      Vehicle SDK · CAR_ENERGY
 *   잔여 ETA         NaviHelper SDK · NAVI_ROUTE
 */
class PleosVehicleSignalSource(private val context: Context) : VehicleSignalSource {

    private val _snapshot = MutableStateFlow(VehicleSnapshot.empty())
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot
    override val isReal = true

    override fun start() {
        // TODO(Pleos): Vehicle SDK 연결 및 좌석·도어·기어·충전 콜백 등록
        // TODO(Pleos): NaviHelper 로 목적지·잔여 ETA 구독
        Log.w(TAG, "Pleos SDK 연동 미구현 — start() 가 아무 동작도 하지 않습니다")
    }

    override fun stop() {
        // TODO(Pleos): 콜백 해제 및 SDK 연결 종료
    }

    companion object {
        private const val TAG = "PleosVehicle"

        /**
         * 이 단말에서 Pleos SDK 로 신호를 받을 수 있는지.
         *
         * TODO(Pleos): SDK 연결을 실제로 시도해 판정하도록 바꿉니다.
         *   지금은 항상 false 이므로 FakeVehicleSignalSource 가 쓰입니다.
         */
        fun isAvailable(context: Context): Boolean = false
    }
}

object VehicleSignalSourceFactory {
    /** 실제 차량 신호를 쓸 수 있으면 Pleos, 아니면 Fake. */
    fun create(context: Context): VehicleSignalSource =
        if (PleosVehicleSignalSource.isAvailable(context)) PleosVehicleSignalSource(context)
        else FakeVehicleSignalSource()
}
