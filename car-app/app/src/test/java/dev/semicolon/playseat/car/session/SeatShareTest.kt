package dev.semicolon.playseat.car.session

import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.SeatId
import dev.semicolon.playseat.car.vehicle.SeatSignal
import dev.semicolon.playseat.car.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 좌석 지분.
 *
 * 차례 규칙을 없앤 뒤 "한 사람이 독점하면 어떻게 되나요" 에 답하는 장치입니다.
 * 막지 않고 보여 주기만 하기 때문에, 보여 주는 숫자가 틀리면 장치 전체가
 * 의미를 잃습니다. 특히 '넣은 곡' 과 '흐른 시간' 을 섞으면 큐를 채운 사람이
 * 아니라 큐를 막은 사람이 가장 많이 들려준 것으로 보입니다.
 */
class SeatShareTest {

    private fun engine() = SessionEngine("test", now = { 0L })

    private fun snapshot(occupied: Set<String>, drive: DriveState = DriveState.PARKED) =
        VehicleSnapshot(
            seats = SeatId.ALL.map { SeatSignal(it, it in occupied) },
            driveState = drive,
        )

    private fun add(e: SessionEngine, seatId: String, title: String, durationMs: Int = 100_000) =
        e.onRequest(
            PhoneRequest(
                type = ReqType.QUEUE_ADD, seatId = seatId, title = title,
                artist = "a", durationMs = durationMs,
            )
        )

    /** 지금 곡을 [playedMs] 만큼 흘린 뒤 다음 곡으로 넘깁니다. */
    private fun play(e: SessionEngine, playedMs: Int) {
        e.updatePosition(playedMs)
        e.trackFinished()
    }

    private fun share(e: SessionEngine, seatId: String) =
        e.state.value.shares.firstOrNull { it.seatId == seatId }

    @Test
    fun `아무것도 흐르지 않았으면 지분은 비어 있다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R)))
        add(e, SeatId.R1R, "a")

        // 큐에 넣는 것만으로는 지분이 생기지 않습니다. 아직 아무도 듣지 않았습니다.
        assertTrue(e.state.value.shares.isEmpty())
        assertNull(e.state.value.shareNote)
    }

    @Test
    fun `넣자마자 스킵된 곡은 지분으로 세지 않는다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        add(e, SeatId.R1R, "끝까지 들은 곡")
        add(e, SeatId.R2L, "바로 넘긴 곡")

        play(e, 100_000)  // R1R: 전부 흐름
        play(e, 0)        // R2L: 0초에 스킵

        assertEquals(1, e.state.value.shares.size)
        assertEquals(100, share(e, SeatId.R1R)?.percent)
        assertNull(share(e, SeatId.R2L))
    }

    @Test
    fun `스킵된 곡은 흐른 만큼만 센다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        add(e, SeatId.R1R, "끝까지")
        add(e, SeatId.R2L, "중간에 스킵")

        play(e, 100_000)
        play(e, 25_000)

        assertEquals(100_000L, share(e, SeatId.R1R)?.playedMs)
        assertEquals(25_000L, share(e, SeatId.R2L)?.playedMs)
        assertEquals(80, share(e, SeatId.R1R)?.percent)
        assertEquals(20, share(e, SeatId.R2L)?.percent)
    }

    @Test
    fun `퍼센트 합은 100 이다`() {
        // 화면에 깔린 띠는 가로를 꽉 채웁니다. 합이 99 나 101 이면 '지분' 이
        // 아니라 '대충 센 것' 이 됩니다. 셋이 똑같이 들려준 경우가 가장 어렵습니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1L, SeatId.R1R, SeatId.R2L)))
        add(e, SeatId.R1R, "a")
        add(e, SeatId.R2L, "b")
        add(e, SeatId.R1L, "c")

        repeat(3) { play(e, 10_000) }

        val shares = e.state.value.shares
        assertEquals(3, shares.size)
        assertEquals(100, shares.sumOf { it.percent })
    }

    @Test
    fun `한 좌석이 60퍼센트 이상이고 세 곡 이상이면 한 문장을 단다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        add(e, SeatId.R1R, "a")
        add(e, SeatId.R1R, "b")
        add(e, SeatId.R1R, "c")
        add(e, SeatId.R2L, "d")

        repeat(4) { play(e, 100_000) }

        assertEquals(75, share(e, SeatId.R1R)?.percent)
        assertEquals(3, share(e, SeatId.R1R)?.tracks)
        assertEquals("조수석이 이번 구간의 75%를 골랐습니다", e.state.value.shareNote)
    }

    @Test
    fun `두 곡까지는 문장을 달지 않는다`() {
        // 두 곡으로는 '구간을 가져갔다' 고 말할 수 없습니다. 그냥 먼저 넣은 사람입니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R)))
        add(e, SeatId.R1R, "a")
        add(e, SeatId.R1R, "b")

        repeat(2) { play(e, 100_000) }

        assertEquals(100, share(e, SeatId.R1R)?.percent)
        assertNull(e.state.value.shareNote)
    }

    @Test
    fun `지분이 비슷하면 문장을 달지 않는다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        add(e, SeatId.R1R, "a")
        add(e, SeatId.R2L, "b")
        add(e, SeatId.R1R, "c")
        add(e, SeatId.R2L, "d")
        add(e, SeatId.R1R, "e")
        add(e, SeatId.R2L, "f")

        repeat(6) { play(e, 100_000) }

        assertEquals(50, share(e, SeatId.R1R)?.percent)
        assertNull(e.state.value.shareNote)
    }

    @Test
    fun `받침이 없는 좌석 이름에도 조사가 맞는다`() {
        // "2열 가운데이" 가 되면 그 한 글자 때문에 화면 전체가 번역기처럼 읽힙니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R2C)))
        add(e, SeatId.R2C, "a")
        add(e, SeatId.R2C, "b")
        add(e, SeatId.R2C, "c")

        repeat(3) { play(e, 100_000) }

        assertEquals("2열 가운데가 이번 구간의 100%를 골랐습니다", e.state.value.shareNote)
    }

    @Test
    fun `주행 구간이 새로 시작하면 지분은 0부터 다시 센다`() {
        // 어제 많이 들려준 사람이 오늘 비켜야 할 이유는 없습니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R)))
        add(e, SeatId.R1R, "a")
        play(e, 100_000)
        assertTrue(e.state.value.shares.isNotEmpty())

        e.onVehicle(snapshot(setOf(SeatId.R1R), drive = DriveState.DRIVING))
        assertTrue(e.state.value.shares.isEmpty())
        assertNull(e.state.value.shareNote)
    }
}
