package dev.semicolon.playseat.car.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
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
 *
 * 착석 Boolean 역전 보정 ([OCCUPANCY_BOOLEAN_IS_INVERTED])
 *   Pleos Connect v2.1.0 에뮬레이터에서 실측한 결과, Vehicle 2.3.2 의
 *   `SeatOccupancyListener`/`getCurrentSeatOccupancy` 가 넘기는 Boolean 이
 *   실제 착석과 반대입니다. Virtual Console 로 네 좌석을 모두 착석시키면
 *   VHAL 은 AAOS 표준대로 `SEAT_OCCUPANCY=2`(OCCUPIED)를 올리는데,
 *   SDK 는 그 좌석들을 `false` 로 전달했습니다. (빈 좌석 raw 1 → `true`)
 *   SDK 가 `value == VACANT` 를 참으로 보는 것으로 보입니다.
 *   그대로 쓰면 "앉으면 빠지고 비면 들어오는" 정반대 동작이 되므로 뒤집습니다.
 *   SDK 가 고쳐지면 아래 상수만 false 로 바꾸면 됩니다.
 */
class PleosVehicleSignalSource(private val context: Context) : VehicleSignalSource {

    private val _snapshot = MutableStateFlow(VehicleSnapshot.empty())
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot
    override val isReal = true

    private var vehicle: Vehicle? = null
    private val occupancyListeners = mutableMapOf<String, SeatOccupancyListener>()
    private val beltListeners = mutableMapOf<String, SeatBeltBuckledStateListener>()
    private var gearListener: GearSelectionListener? = null
    private var portListener: EvChargePortOpenListener? = null
    private var socListener: EvBatteryStateOfChargeListener? = null
    private var navi: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null

    private var wiperListener: WiperStateListener? = null
    private var lowBeamListener: LightStateListener? = null
    private var steeringListener: SteeringWheelAngleListener? = null
    private val swing = SteeringSwing()

    /** 앰비언트 색을 받아 주는 좌석. capability 가 알려준 것만 건드립니다. */
    private var ambientAreas: List<SeatArea> = emptyList()

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
        subscribeCharging(v)
        subscribeNavi()
        subscribeEnvironment(v)
    }

    // ---------- 분위기 신호 ----------

    /**
     * 와이퍼·하향등·조향.
     *
     * 세 신호 모두 차만 알 수 있는 것이고, 어느 하나가 없는 차여도 나머지로
     * 분위기 판정이 돌아야 합니다. 그래서 각각 따로 구독하고 실패를 삼킵니다.
     */
    private fun subscribeEnvironment(v: Vehicle) {
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiper = v.getWiper()

        fun refreshWiper() {
            // 단계(세기)는 스위치에, 작동 여부는 상태에 있습니다. 둘을 합쳐야
            // "안개비" 와 "폭우" 가 갈립니다. AUTO 는 비가 오는지를 말해 주지
            // 않으므로 상태(ON/OFF)로 판단합니다.
            wiper.getCurrentWiperSwitch(front, { sw ->
                if (sw == WiperSwitch.AUTO) {
                    wiper.getCurrentWiperState(front, { st ->
                        setWiper(if (st == WiperState.ON) WiperLevel.LOW else WiperLevel.OFF)
                    }, {})
                } else {
                    setWiper(levelOf(sw))
                }
            }, { e -> Log.w(TAG, "와이퍼 스위치 조회 실패: ${e.message}") })
        }

        refreshWiper()
        val wl = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = refreshWiper()
            override fun onFailed(e: Exception) {
                Log.w(TAG, "와이퍼 콜백 오류: ${e.message}")
            }
        }
        wiperListener = wl
        runCatching { wiper.registerWiperState(front, wl) }
            .onFailure { Log.w(TAG, "와이퍼 구독 실패: ${it.message}") }

        val light = v.getLight()
        fun setLowBeam(st: LightState?) = _snapshot.update {
            it.copy(env = it.env.copy(lowBeamOn = st == LightState.ON))
        }
        light.getCurrentLowBeamLightState({ st -> setLowBeam(st) }, { e ->
            Log.w(TAG, "하향등 조회 실패: ${e.message}")
        })
        val ll = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) = setLowBeam(state)
            override fun onFailed(e: Exception) {
                Log.w(TAG, "하향등 콜백 오류: ${e.message}")
            }
        }
        lowBeamListener = ll
        runCatching { light.registerLowBeamLightState(ll) }
            .onFailure { Log.w(TAG, "하향등 구독 실패: ${it.message}") }

        // 앰비언트 색을 받아 주는 좌석을 미리 확인해 둡니다.
        runCatching {
            light.checkAmbientLightBrightnessColorCapabilities({ _, areas ->
                ambientAreas = areas
                Log.i(TAG, "앰비언트 색 지원 좌석 ${areas.size}곳")
            }, { e -> Log.w(TAG, "앰비언트 capability 조회 실패: ${e.message}") })
        }

        val wheel = v.getSteeringWheel()
        val sl = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                val deg = angle ?: return
                val spread = swing.add(deg, System.currentTimeMillis())
                _snapshot.update { it.copy(env = it.env.copy(steeringSwingDeg = spread)) }
            }
            override fun onFailed(e: Exception) {
                Log.w(TAG, "조향각 콜백 오류: ${e.message}")
            }
        }
        steeringListener = sl
        runCatching { wheel.registerSteeringWheelAngle(sl) }
            .onFailure { Log.w(TAG, "조향각 구독 실패: ${it.message}") }
    }

    private fun setWiper(level: WiperLevel) =
        _snapshot.update { it.copy(env = it.env.copy(wiper = level)) }

    /** 차량 설정의 야간 모드. AAOS uiMode 를 따르므로 바깥에서 넣어 줍니다. */
    fun setNight(night: Boolean) =
        _snapshot.update { it.copy(env = it.env.copy(night = night)) }

    override fun applyMoodColor(argb: Int) {
        val light = vehicle?.getLight() ?: return
        if (ambientAreas.isEmpty()) return
        ambientAreas.forEach { area ->
            runCatching {
                light.setAmbientLightBrightnessColor(area, argb, { _, _ -> }, { e ->
                    Log.w(TAG, "앰비언트 색 설정 실패: ${e.message}")
                })
            }
        }
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
        seat.getCurrentSeatOccupancy(area, { raw -> setOccupied(seatId, readOccupancy(raw)) }, { e ->
            Log.w(TAG, "$seatId 착석 조회 실패: ${e.message}")
        })
        val l = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(raw: Boolean?) = setOccupied(seatId, readOccupancy(raw))
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

    // ---------- 충전 → 라운지 모드 ----------

    /**
     * 충전 중 판정.
     *
     * 충전 여부를 한 번에 알려주는 API 가 없어 충전 스위치 상태를 기준으로 삼습니다.
     * 충전구 개폐와 배터리 잔량이 바뀔 때마다 스위치 상태를 다시 조회해 최신으로 유지합니다.
     */
    private fun subscribeCharging(v: Vehicle) {
        val ev = v.getEvBattery()

        fun refreshSwitch() = ev.getEvChargeSwitchState({ on ->
            _snapshot.update { it.copy(charging = on == true) }
        }, { e -> Log.w(TAG, "충전 스위치 조회 실패: ${e.message}") })

        refreshSwitch()
        ev.getEvBatteryStateOfCharge({ soc ->
            _snapshot.update { it.copy(batteryPercent = soc) }
        }, { })

        val pl = object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(open: Boolean?) = refreshSwitch()
            override fun onFailed(e: Exception) {
                Log.w(TAG, "충전구 콜백 오류: ${e.message}")
            }
        }
        portListener = pl
        ev.registerEvChargePortOpen(pl)

        val sl = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(soc: Int?) {
                _snapshot.update { it.copy(batteryPercent = soc) }
                refreshSwitch()
            }
            override fun onFailed(e: Exception) {
                Log.w(TAG, "배터리 잔량 콜백 오류: ${e.message}")
            }
        }
        socListener = sl
        ev.registerEvBatteryStateOfCharge(sl)
    }

    // ---------- 내비 → 잔여 ETA ----------

    /**
     * 잔여 ETA.
     *
     * 경로 안내 중에는 onDrivingInfo 가 반복 호출되고, destination.duration 이
     * 목적지까지 남은 초입니다. 이 값이 큐 목표 길이와 랜딩 구간 판정에 쓰입니다.
     * 경로가 없으면 null 로 두어 큐 길이를 맞추지 않습니다.
     */
    private fun subscribeNavi() {
        val n = runCatching { NaviHelper(context) }
            .onFailure { Log.w(TAG, "NaviHelper 생성 실패: ${it.message}") }
            .getOrNull() ?: return

        val l = object : NaviHelperEventListener {
            override fun onDrivingInfo(drivingInfo: DrivingInfo) {
                val sec = runCatching { drivingInfo.destination.duration }.getOrNull()
                setEta(sec)
            }

            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
                setEta(0)
            }

            override fun onRouteEnded() = setEta(null)
            override fun onRouteCancelled() = setEta(null)

            override fun onNavigationStatus(navigationStatus: NavigationStatus) {
                // 경로 안내가 아니면 ETA 가 의미 없습니다
                if (navigationStatus != NavigationStatus.ROUTE_DRIVE) setEta(null)
            }
        }

        n.initialize({
            n.addListener(l)
            navi = n
            naviListener = l
            Log.i(TAG, "NaviHelper 연결 완료")
        }, { t ->
            Log.w(TAG, "NaviHelper 초기화 실패: ${t.message}")
        })
    }

    private fun setEta(seconds: Int?) = _snapshot.update { it.copy(etaSeconds = seconds) }

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
            portListener?.let { v.getEvBattery().unregisterEvChargePortOpen(it) }
            socListener?.let { v.getEvBattery().unregisterEvBatteryStateOfCharge(it) }
        }.onFailure { Log.w(TAG, "구독 해제 중 오류: ${it.message}") }
        runCatching {
            naviListener?.let { navi?.removeListener(it) }
            navi?.release()
        }.onFailure { Log.w(TAG, "NaviHelper 해제 중 오류: ${it.message}") }
        occupancyListeners.clear()
        beltListeners.clear()
        gearListener = null
        portListener = null
        socListener = null
        naviListener = null
        navi = null
        runCatching { v.release() }
        vehicle = null
    }

    companion object {
        private const val TAG = "PleosVehicle"

        /**
         * Vehicle 2.3.2 의 착석 Boolean 이 실제와 반대라서 뒤집습니다.
         * SDK 가 바로잡히면 false 로 두면 됩니다. 근거는 클래스 KDoc 참고.
         */
        private const val OCCUPANCY_BOOLEAN_IS_INVERTED = true

        /** SDK 가 준 착석 Boolean 을 "사람이 앉아 있다" 로 해석합니다. */
        internal fun readOccupancy(raw: Boolean?): Boolean =
            if (raw == null) false else raw != OCCUPANCY_BOOLEAN_IS_INVERTED

        /**
         * 와이퍼 스위치 → 우리 단계.
         *
         * SDK 는 간헐 5단·연속 5단까지 나누지만 곡을 고르는 데 그만큼 필요하지
         * 않습니다. "오는가 / 꽤 오는가" 정도면 충분합니다.
         */
        internal fun levelOf(sw: WiperSwitch?): WiperLevel {
            val name = sw?.name ?: return WiperLevel.OFF
            return when {
                name.startsWith("CONTINUOUS_LEVEL_4") || name.startsWith("CONTINUOUS_LEVEL_5") -> WiperLevel.HIGH
                name.startsWith("CONTINUOUS") -> WiperLevel.LOW
                name.startsWith("INTERMITTENT") || name == "MIST" -> WiperLevel.INTERMITTENT
                else -> WiperLevel.OFF
            }
        }

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
