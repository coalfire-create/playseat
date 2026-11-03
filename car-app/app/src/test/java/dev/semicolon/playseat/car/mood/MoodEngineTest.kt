package dev.semicolon.playseat.car.mood

import dev.semicolon.playseat.car.session.SessionMode
import dev.semicolon.playseat.car.session.SessionState
import dev.semicolon.playseat.car.vehicle.CabinEnvironment
import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.SeatId
import dev.semicolon.playseat.car.vehicle.SeatSignal
import dev.semicolon.playseat.car.vehicle.SteeringSwing
import dev.semicolon.playseat.car.vehicle.VehicleSnapshot
import dev.semicolon.playseat.car.vehicle.WiperLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 분위기 판정은 추천 곡을 바꾸고, 추천 곡은 아무도 안 눌러도 큐에 들어갑니다.
 * 즉 틀리면 멋대로 음악을 트는 기능이 되므로 조합을 전부 고정해 둡니다.
 */
class MoodEngineTest {

    private fun vehicle(
        drive: DriveState = DriveState.PARKED,
        wiper: WiperLevel = WiperLevel.OFF,
        night: Boolean = false,
        lowBeam: Boolean = false,
        swing: Float = 0f,
    ) = VehicleSnapshot(
        seats = SeatId.ALL.map { SeatSignal(it, occupied = true) },
        driveState = drive,
        env = CabinEnvironment(
            wiper = wiper, night = night, lowBeamOn = lowBeam, steeringSwingDeg = swing,
        ),
    )

    private fun state(
        mode: String = SessionMode.NORMAL,
        landing: Boolean = false,
    ) = SessionState(sessionId = "t", mode = mode, landing = landing)

    @Test
    fun `와이퍼가 돌면 비 오는 날`() {
        val r = MoodEngine.read(vehicle(drive = DriveState.DRIVING, wiper = WiperLevel.LOW), state())
        assertEquals(Mood.RAIN, r.mood)
        assertTrue(r.reason.contains("와이퍼"))
    }

    @Test
    fun `폭우는 근거 문장이 다르다`() {
        val r = MoodEngine.read(vehicle(wiper = WiperLevel.HIGH), state())
        assertEquals(Mood.RAIN, r.mood)
        assertTrue(r.reason.contains("빠르게"))
    }

    @Test
    fun `비는 밤보다 먼저다`() {
        // 밤에 비가 오면 '밤 드라이브' 가 아니라 '비 오는 날' 입니다.
        // 더 구체적인 상황이 곡 선택을 더 많이 바꿉니다.
        val r = MoodEngine.read(
            vehicle(drive = DriveState.DRIVING, wiper = WiperLevel.INTERMITTENT, night = true),
            state(),
        )
        assertEquals(Mood.RAIN, r.mood)
    }

    @Test
    fun `충전 중이면 라운지`() {
        val r = MoodEngine.read(vehicle(), state(mode = SessionMode.CHARGING_LOUNGE))
        assertEquals(Mood.LOUNGE, r.mood)
    }

    @Test
    fun `도착 임박은 굽잇길보다 먼저다`() {
        val r = MoodEngine.read(
            vehicle(drive = DriveState.DRIVING, swing = 120f),
            state(landing = true),
        )
        assertEquals(Mood.ARRIVING, r.mood)
    }

    @Test
    fun `조향이 크게 흔들리면 굽잇길`() {
        val r = MoodEngine.read(vehicle(drive = DriveState.DRIVING, swing = 90f), state())
        assertEquals(Mood.WINDING, r.mood)
    }

    @Test
    fun `조향 변동이 기준보다 작으면 굽잇길이 아니다`() {
        val r = MoodEngine.read(
            vehicle(drive = DriveState.DRIVING, swing = MoodEngine.WINDING_SWING_DEG - 1f),
            state(),
        )
        assertEquals(Mood.SUNNY, r.mood)
    }

    @Test
    fun `야간 주행은 밤 드라이브`() {
        val r = MoodEngine.read(vehicle(drive = DriveState.DRIVING, night = true), state())
        assertEquals(Mood.NIGHT, r.mood)
    }

    @Test
    fun `낮에 전조등이 켜져 있으면 근거가 전조등이다`() {
        // 터널입니다. 밤은 아니지만 밖은 어둡습니다.
        val r = MoodEngine.read(vehicle(drive = DriveState.DRIVING, lowBeam = true), state())
        assertEquals(Mood.NIGHT, r.mood)
        assertTrue(r.reason.contains("전조등"))
    }

    @Test
    fun `밝을 때 달리면 맑은 날`() {
        assertEquals(Mood.SUNNY, MoodEngine.read(vehicle(drive = DriveState.DRIVING), state()).mood)
    }

    @Test
    fun `정차 중이면 조용한 시간`() {
        assertEquals(Mood.CALM, MoodEngine.read(vehicle(), state()).mood)
    }

    @Test
    fun `밤에 정차하면 근거가 달라진다`() {
        val r = MoodEngine.read(vehicle(night = true), state())
        assertEquals(Mood.CALM, r.mood)
        assertTrue(r.reason.contains("어두"))
    }

    @Test
    fun `모든 무드는 서로 다른 색을 쓴다`() {
        // 색이 겹치면 조명이 바뀌어도 분위기가 바뀐 줄 모릅니다.
        assertEquals(Mood.entries.size, Mood.entries.map { it.argb }.distinct().size)
    }

    @Test
    fun `무드 식별자로 되찾을 수 있다`() {
        Mood.entries.forEach { assertEquals(it, Mood.byId(it.id)) }
        assertEquals(null, Mood.byId("없는무드"))
    }
}

/**
 * 조향 변동폭.
 *
 * 각도 하나로 판정하면 주차장에서 핸들을 꺾은 것도 굽잇길이 됩니다.
 */
class SteeringSwingTest {

    @Test
    fun `한 번만 꺾으면 변동이 생기지만 유지하면 사라진다`() {
        val s = SteeringSwing(windowMs = 10_000)
        assertEquals(0f, s.add(0f, 0), 0.01f)
        assertEquals(300f, s.add(300f, 1_000), 0.01f)
        // 같은 각도로 계속 있으면, 창이 지나간 뒤에는 변동이 0 입니다.
        s.add(300f, 12_000)
        assertEquals(0f, s.add(300f, 13_000), 0.01f)
    }

    @Test
    fun `좌우로 계속 흔들리면 변동폭이 크다`() {
        val s = SteeringSwing(windowMs = 10_000)
        s.add(-60f, 0)
        s.add(40f, 2_000)
        val spread = s.add(-50f, 4_000)
        assertEquals(100f, spread, 0.01f)
        assertTrue(spread >= MoodEngine.WINDING_SWING_DEG)
    }

    @Test
    fun `창 밖의 표본은 버린다`() {
        val s = SteeringSwing(windowMs = 5_000)
        s.add(-200f, 0)
        s.add(0f, 1_000)
        // 6초 뒤에는 -200 이 창에서 빠집니다.
        assertEquals(0f, s.add(0f, 6_500), 0.01f)
    }
}
