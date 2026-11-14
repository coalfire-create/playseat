package dev.semicolon.playseat.car.session

import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.SeatId
import dev.semicolon.playseat.car.vehicle.SeatSignal
import dev.semicolon.playseat.car.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 도착 맞춤 편성.
 *
 * 이 기능이 플레이시트가 차 안에 있어야 하는 이유여서, 틀리면 안 되는 자리가
 * 분명합니다. 목적지가 없을 때 조용한지, 곡을 잃지 않는지, 맞출 수 없을 때
 * 상태를 건드리지 않는지입니다. 폰이 할 수 없는 계산을 차가 한다고 주장하는
 * 기능이 폰보다 거칠게 틀리면 주장 전체가 무너집니다.
 */
class ArrivalFitTest {

    private fun engine() = SessionEngine("test", now = { 0L })

    private fun snapshot(
        occupied: Set<String>,
        drive: DriveState = DriveState.PARKED,
        eta: Int? = null,
    ) = VehicleSnapshot(
        seats = SeatId.ALL.map { SeatSignal(it, it in occupied) },
        driveState = drive,
        etaSeconds = eta,
    )

    private fun add(
        e: SessionEngine,
        seatId: String,
        title: String,
        durationMs: Int,
        playableMs: Int? = null,
    ) = e.onRequest(
        PhoneRequest(
            type = ReqType.QUEUE_ADD, seatId = seatId, title = title, artist = "a",
            durationMs = durationMs, playableMs = playableMs,
        )
    )

    /** 지금 재생 + 큐에 있는 곡 이름 전부. 순서를 바꾼 뒤 집합이 같은지 볼 때 씁니다. */
    private fun allTitles(s: SessionState) =
        (listOfNotNull(s.nowPlaying) + s.queue).map { it.title }.sorted()

    // ---------- 목적지가 없을 때 ----------

    @Test
    fun `ETA 가 없으면 도착 맞춤 네 필드가 모두 빈다`() {
        // 내비를 켜지 않은 주행에서는 이 기능이 없는 것처럼 조용해야 합니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), eta = null))
        add(e, SeatId.R1R, "a", 200_000)
        add(e, SeatId.R1R, "b", 200_000)

        val s = e.state.value
        assertNull(s.arrivalSlackMs)
        assertNull(s.cutIndex)
        assertNull(s.fitPlan)
        assertFalse(s.canFitArrival)
    }

    @Test
    fun `목적지가 있어도 음악이 없으면 조용하다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), eta = 600))
        assertNull(e.state.value.fitPlan)
        assertNull(e.state.value.arrivalSlackMs)
    }

    // ---------- 남는 시간 ----------

    @Test
    fun `전부 도착 전에 끝나면 남는 시간을 분 초로 읽어 준다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), eta = 300))
        add(e, SeatId.R1R, "지금", 100_000)
        add(e, SeatId.R1R, "다음", 50_000)
        add(e, SeatId.R1R, "그다음", 50_000)

        val s = e.state.value
        assertEquals(100_000L, s.arrivalSlackMs)
        assertNull(s.cutIndex)
        assertEquals("도착까지 2곡 · 1분 40초 남습니다", s.fitPlan)
        assertFalse(s.canFitArrival)
    }

    // ---------- 걸치는 곡 ----------

    @Test
    fun `도착이 곡 중간에 떨어지면 cutIndex 와 음수 slack 이 잡힌다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), eta = 300))
        add(e, SeatId.R1R, "지금", 120_000)   // 0 ~ 120초
        add(e, SeatId.R1R, "두번째", 120_000) // 120 ~ 240초
        add(e, SeatId.R1R, "세번째", 120_000) // 240 ~ 360초, 300초에 걸칩니다

        val s = e.state.value
        assertEquals(1, s.cutIndex)
        assertEquals(-60_000L, s.arrivalSlackMs)
        assertEquals("마지막 곡이 1분 잘립니다", s.fitPlan)
    }

    @Test
    fun `지금 곡이 도착선에 걸치면 큐 위치는 비어 있다`() {
        // 순서를 바꿔도 손댈 수 없는 경우입니다. 이미 흐르고 있으니까요.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), eta = 30))
        add(e, SeatId.R1R, "지금", 200_000)

        val s = e.state.value
        assertNull(s.cutIndex)
        assertEquals(-170_000L, s.arrivalSlackMs)
        assertEquals("지금 곡이 2분 50초 잘립니다", s.fitPlan)
        assertFalse(s.canFitArrival)
    }

    // ---------- 미리듣기 음원 ----------

    @Test
    fun `길이는 원래 곡이 아니라 실제로 흐르는 시간으로 센다`() {
        // 미리듣기 음원은 3분짜리 곡이라도 30초만 흐릅니다. durationMs 로 세면
        // 큐가 실제보다 여섯 배 길다고 나오고 도착 맞춤이 전부 틀립니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), eta = 60))
        add(e, SeatId.R1R, "지금", durationMs = 200_000, playableMs = 30_000)
        add(e, SeatId.R1R, "다음", durationMs = 200_000, playableMs = 30_000)

        assertEquals(30_000L, e.state.value.queueTotalMs)
        // 30초 + 30초 = 60초. 도착과 함께 끝납니다.
        assertEquals(0L, e.state.value.arrivalSlackMs)
        assertEquals("마지막 곡이 도착과 함께 끝납니다", e.state.value.fitPlan)
    }

    // ---------- queue.fit ----------

    @Test
    fun `도착에 맞출 조합이 있으면 canFitArrival 이 켜진다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L), eta = 300))
        add(e, SeatId.R1R, "지금", 120_000)
        add(e, SeatId.R1R, "두번째", 120_000)
        add(e, SeatId.R1R, "긴곡", 120_000)
        add(e, SeatId.R2L, "짧은곡", 50_000)

        assertEquals(1, e.state.value.cutIndex)
        assertTrue(e.state.value.canFitArrival)
    }

    @Test
    fun `막 시작한 긴 곡은 잘린 곡이 아니라 다음 구간의 첫 곡이다`() {
        // 4분짜리 곡이 20초 흐른 뒤 도착했다고 "4분 잘렸습니다" 라고 말하면,
        // 듣던 사람은 그 곡을 들었다고 생각조차 하지 않는데 손해를 본 것처럼
        // 읽힙니다. 경계를 곡 길이에 비례해 두는 이유입니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), eta = 150))
        add(e, SeatId.R1R, "지금", 120_000)
        add(e, SeatId.R1R, "다음 구간 첫 곡", 260_000)   // 도착 30초 전에 시작

        val st = e.state.value
        assertEquals(0, st.cutIndex)
        assertEquals("마지막 곡이 도착과 함께 끝납니다", st.fitPlan)
        // 손댈 것이 없으므로 권하지 않습니다.
        assertFalse(st.canFitArrival)
    }

    @Test
    fun `queue fit 은 곡을 잃지 않고 순서만 바꾼다`() {
        // 남의 선곡을 차가 지우면 그건 다른 앱입니다. 밀린 곡은 큐에 남습니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L), eta = 300))
        add(e, SeatId.R1R, "지금", 120_000)
        add(e, SeatId.R1R, "두번째", 120_000)
        add(e, SeatId.R1R, "긴곡", 120_000)
        add(e, SeatId.R2L, "짧은곡", 50_000)

        val before = allTitles(e.state.value)
        assertNull(e.onRequest(PhoneRequest(type = ReqType.QUEUE_FIT)))

        val s = e.state.value
        assertEquals(before, allTitles(s))
        assertEquals(listOf("두번째", "짧은곡", "긴곡"), s.queue.map { it.title })
        assertEquals("마지막 곡이 도착과 함께 끝납니다", s.fitPlan)
        assertFalse(s.canFitArrival)
    }

    @Test
    fun `맞출 조합이 없으면 NO_FIT 으로 거절하고 상태를 그대로 둔다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), eta = 300))
        add(e, SeatId.R1R, "지금", 120_000)
        add(e, SeatId.R1R, "두번째", 120_000)
        add(e, SeatId.R1R, "세번째", 120_000)

        val before = e.state.value
        assertFalse(before.canFitArrival)

        val rejected = e.onRequest(PhoneRequest(type = ReqType.QUEUE_FIT))
        assertEquals(RejectReason.NO_FIT, rejected?.reason)
        // 거절은 상태를 건드리지 않습니다 — 같은 객체여야 합니다.
        assertSame(before, e.state.value)
    }

    @Test
    fun `목적지가 없으면 queue fit 도 거절한다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), eta = null))
        add(e, SeatId.R1R, "지금", 120_000)
        add(e, SeatId.R1R, "두번째", 120_000)

        assertEquals(
            RejectReason.NO_FIT,
            e.onRequest(PhoneRequest(type = ReqType.QUEUE_FIT))?.reason,
        )
    }

    @Test
    fun `같은 틈을 채우는 곡이 여럿이면 앞자리가 없는 좌석의 곡을 당긴다`() {
        // 늘 '가장 잘 맞는 곡' 만 고르면 짧은 곡을 넣은 한 좌석만 매번
        // 앞으로 당겨집니다. 순서를 바꾸는 기능이 좌석 지분을 기울이면 안 됩니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L), eta = 300))
        add(e, SeatId.R1R, "지금", 120_000)
        add(e, SeatId.R1R, "앞자리", 120_000)   // 도착 전에 이미 자리를 잡은 R1R 의 곡
        add(e, SeatId.R1R, "걸친곡", 120_000)
        add(e, SeatId.R1R, "R1R 짧은곡", 48_000)
        add(e, SeatId.R2L, "R2L 짧은곡", 50_000)

        // 둘 다 60초 틈에 들어가고 차이는 2초뿐입니다. 그러면 좌석으로 가릅니다.
        e.onRequest(PhoneRequest(type = ReqType.QUEUE_FIT))
        assertEquals("R2L 짧은곡", e.state.value.queue[1].title)
    }
}
