package dev.semicolon.playseat.car.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos Connect SDK 로 실제 차량 신호를 읽는 구현.
 *
 * 진입점은 `Vehicle(context)` 이고, `initialize()` 후 `getSeat()`·`getGear()` 로
 * 도메인 객체를 얻어 Get/Callback/Capability API 를 호출합니다. 차량 통신은 IPC 라
 * 결과가 콜백으로 비동기 전달되므로, 콜백이 끝나기 전에 `release()` 하지 않습니다.
 *
 * 퍼미션은 매니페스트에 `pleos.car.permission.{상수}` 형식으로 선언해야 합니다.
 *   CAR_SEATS  좌석 착석·안전벨트
 *   CAR_DRIVE  기어·주행 상태
 *   CAR_ENERGY 충전·배터리
 *
 * 착석 감지 폴백
 *   차종에 따라 착석 센서가 없을 수 있습니다. `checkSeatOccupancyCapabilities` 가
 *   돌려주는 좌석 목록만 구독하고, 지원 좌석이 없으면 안전벨트 착용 상태로 대체합니다.
 *   둘 다 없으면 좌석이 비어 있는 상태로 남고, 사용자가 좌석을 직접 지정하게 됩니다.
 */
class PleosVehicleSignalSource(private val context: Context) : VehicleSignalSource {

    private val _snapshot = MutableStateFlow(VehicleSnapshot.empty())
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot
    override val isReal = true

    private var vehicle: Vehicle? = null
    private val occupancyListeners = mutableMapOf<String, SeatOccupancyListener>()
    private val beltListeners = mutableMapOf<String, SeatBeltBuckledStateListener>()
    private var gearListener: GearSelectionListener? = null

    /** 착석 감지를 무엇으로 하고 있는지. 화면에 표시해 심사·디버깅에 쓸 수 있게 둡니다. */
    @Volatile var occupancySource: String = "미확인"
        private set

    override fun start() {
        val v = runCatching { Vehicle(context).also { it.initialize() } }
            .onFailure { Log.e(TAG, "Vehicle 초기화 실패: ${it.message}") }
            .getOrNull() ?: return
        vehicle = v

        subscribeSeats(v)
        subscribeGear(v)
        // TODO(충전): 충전 상태 전용 API 를 찾지 못했습니다. EvChargePortOpenListener 를
        //   대리 신호로 쓸지, EvBatteryStateOfCharge 증가 추세로 판정할지 결정해야 합니다.
        //   그때까지 charging 은 false 로 두어 라운지 모드가 켜지지 않습니다.
        // TODO(ETA): NaviHelper SDK 로 목적지·잔여 ETA 를 구독해 etaSeconds 를 채웁니다.
    }

    // ---------- 좌석 ----------

    private fun subscribeSeats(v: Vehicle) {
        val seat = v.getSeat()
        seat.checkSeatOccupancyCapabilities({ _, areas ->
            val ids = areas.flatMap { it.values.toList() }.mapNotNull { seatIdOf(it) }.distinct()
            if (ids.isEmpty()) {
                Log.w(TAG, "착석 감지 지원 좌석 없음 → 안전벨트로 폴백")
                subscribeBelts(v)
            } else {
                occupancySource = "착석 센서 (${ids.size}석)"
                Log.i(TAG, "착석 감지 지원: $ids")
                ids.forEach { id -> subscribeOccupancy(seat, id) }
            }
        }, { e ->
            Log.w(TAG, "착석 capability 조회 실패(${e.message}) → 안전벨트로 폴백")
            subscribeBelts(v)
        })
    }

    private fun subscribeOccupancy(seat: ai.pleos.playground.vehicle.api.Seat, seatId: String) {
        val area = areaOf(seatId) ?: return
        seat.getCurrentSeatOccupancy(area, { occupied -> setOccupied(seatId, occupied == true) }, { e ->
            Log.w(TAG, "$seatId 착석 조회 실패: ${e.message}")
        })
        val l = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupied: Boolean?) = setOccupied(seatId, occupied == true)
            override fun onFailed(e: Exception) {
                Log.w(TAG, "$seatId 착석 콜백 오류: ${e.message}")
            }
        }
        occupancyListeners[seatId] = l
        seat.registerSeatOccupancy(area, l)
    }

    /** 폴백 1: 안전벨트 착용을 탑승으로 간주합니다. */
    private fun subscribeBelts(v: Vehicle) {
        val seat = v.getSeat()
        seat.checkSeatBeltBuckledCapabilities({ _, areas ->
            val ids = areas.flatMap { it.values.toList() }.mapNotNull { seatIdOf(it) }.distinct()
            if (ids.isEmpty()) {
                occupancySource = "없음 (좌석 수동 지정 필요)"
                Log.w(TAG, "안전벨트 신호도 없음 — 좌석을 수동 지정해야 합니다")
                return@checkSeatBeltBuckledCapabilities
            }
            occupancySource = "안전벨트 (${ids.size}석)"
            ids.forEach { id ->
                val area = areaOf(id) ?: return@forEach
                seat.getCurrentSeatBeltBuckledState(area, { on -> setOccupied(id, on == true) }, {})
                val l = object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) =
                        setOccupied(id, buckled == true)
                    override fun onFailed(e: Exception) {
                        Log.w(TAG, "$id 안전벨트 콜백 오류: ${e.message}")
                    }
                }
                beltListeners[id] = l
                seat.registerSeatBeltBuckledState(area, l)
            }
        }, { e ->
            occupancySource = "없음 (좌석 수동 지정 필요)"
            Log.w(TAG, "안전벨트 capability 조회 실패: ${e.message}")
        })
    }

    // ---------- 기어 → 주행 상태 ----------

    /**
     * 기어 → 주행 상태.
     *
     * SDK 가 이 API 를 비권장으로 표시하며 "Google GEAR_SELECTION property" 사용을 권합니다.
     * TODO(기어): AAOS 표준 CarPropertyManager 의 GEAR_SELECTION 으로 교체합니다.
     *   지금은 Pleos API 로 동작을 먼저 확인하고, 에뮬레이터 검증 후 바꿉니다.
     */
    @Suppress("DEPRECATION")
    private fun subscribeGear(v: Vehicle) {
        val gear = v.getGear()
        gear.getCurrentGearSelectionState({ g -> setGear(g) }, { e ->
            Log.w(TAG, "기어 조회 실패: ${e.message}")
        })
        val l = object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) = setGear(g)
            override fun onFailed(e: Exception) {
                Log.w(TAG, "기어 콜백 오류: ${e.message}")
            }
        }
        gearListener = l
        gear.registerGearSelection(l)
    }

    /**
     * P 단만 정차로 봅니다. 알 수 없는 값은 주행으로 처리합니다 —
     * 운전자 화면을 잠그는 쪽이 안전한 기본값이기 때문입니다.
     */
    private fun setGear(g: VehicleGear?) {
        val parked = g == VehicleGear.GEAR_PARK
        _snapshot.update {
            it.copy(driveState = if (parked) DriveState.PARKED else DriveState.DRIVING)
        }
    }

    // ---------- 공통 ----------

    private fun setOccupied(seatId: String, occupied: Boolean) {
        _snapshot.update { s ->
            s.copy(seats = s.seats.map {
                if (it.seatId != seatId) it
                else it.copy(
                    occupied = occupied,
                    seatedAt = if (occupied && !it.occupied) System.currentTimeMillis()
                    else if (!occupied) null else it.seatedAt,
                )
            })
        }
    }

    override fun stop() {
        val v = vehicle ?: return
        @Suppress("DEPRECATION")
        runCatching {
            val seat = v.getSeat()
            occupancyListeners.forEach { (id, l) -> areaOf(id)?.let { seat.unregisterSeatOccupancy(it, l) } }
            beltListeners.forEach { (id, l) -> areaOf(id)?.let { seat.unregisterSeatBeltBuckledState(it, l) } }
            gearListener?.let { v.getGear().unregisterGearSelection(it) }
        }.onFailure { Log.w(TAG, "구독 해제 중 오류: ${it.message}") }
        occupancyListeners.clear()
        beltListeners.clear()
        gearListener = null
        runCatching { v.release() }
        vehicle = null
    }

    companion object {
        private const val TAG = "PleosVehicle"

        /** 좌석 ID ↔ Pleos SeatValue 매핑. 3열은 현재 쓰지 않습니다. */
        private val SEAT_MAP = mapOf(
            SeatId.R1L to SeatValue.ROW_1_LEFT,
            SeatId.R1R to SeatValue.ROW_1_RIGHT,
            SeatId.R2L to SeatValue.ROW_2_LEFT,
            SeatId.R2C to SeatValue.ROW_2_CENTER,
            SeatId.R2R to SeatValue.ROW_2_RIGHT,
        )

        private fun areaOf(seatId: String): SeatArea? =
            SEAT_MAP[seatId]?.let { SeatArea(it) }

        private fun seatIdOf(value: SeatValue): String? =
            SEAT_MAP.entries.firstOrNull { it.value == value }?.key

        /**
         * 이 단말에서 Pleos SDK 로 신호를 받을 수 있는지.
         *
         * 자동차 하드웨어 기능이 있고 SDK 클래스를 적재할 수 있을 때만 실제 구현을 씁니다.
         * 일반 휴대폰 AVD 에서는 false 가 되어 FakeVehicleSignalSource 로 떨어집니다.
         */
        fun isAvailable(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
                runCatching { Class.forName("ai.pleos.playground.vehicle.Vehicle") }.isSuccess
    }
}

object VehicleSignalSourceFactory {
    /** 실제 차량 신호를 쓸 수 있으면 Pleos, 아니면 Fake. */
    fun create(context: Context): VehicleSignalSource =
        if (PleosVehicleSignalSource.isAvailable(context)) PleosVehicleSignalSource(context)
        else FakeVehicleSignalSource()
}
