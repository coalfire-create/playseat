package dev.semicolon.playseat.car.session

import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.SeatId
import dev.semicolon.playseat.car.vehicle.SeatSignal
import dev.semicolon.playseat.car.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 탑승 코드.
 *
 * 착석 센서와 코드를 같이 봅니다 — 앉아 있고, 차 안 화면을 볼 수 있어야
 * 참여합니다. 둘 중 하나만 보면 각각 다른 방식으로 뚫립니다: 센서만 보면
 * 주차장에서 찍어 둔 QR 사진으로 남의 차에 들어오고, 코드만 보면 창밖에서
 * 화면을 들여다본 사람이 들어옵니다.
 */
class JoinCodeTest {

    private fun engine() = SessionEngine("test", now = { 0L })

    private fun snapshot(occupied: Set<String>) = VehicleSnapshot(
        seats = SeatId.ALL.map { SeatSignal(it, it in occupied) },
        driveState = DriveState.PARKED,
    )

    private fun claim(e: SessionEngine, seatId: String, code: String?) = e.onRequest(
        PhoneRequest(
            type = ReqType.SEAT_CLAIM, deviceId = "device", seatId = seatId,
            nickname = "탑승자", joinCode = code,
        )
    )

    private fun connected(e: SessionEngine, seatId: String) =
        e.state.value.seats.first { it.seatId == seatId }.connected

    @Test
    fun `코드가 맞으면 좌석을 잡는다`() {
        val e = engine()
        e.setJoinCode("4821")
        e.onVehicle(snapshot(setOf(SeatId.R2L)))

        assertNull(claim(e, SeatId.R2L, "4821"))
        assertTrue(connected(e, SeatId.R2L))
    }

    @Test
    fun `코드가 다르면 거절한다`() {
        // QR 사진만 가진 폰이 여기서 멈춥니다. 코드는 차 안 화면에만 있습니다.
        val e = engine()
        e.setJoinCode("4821")
        e.onVehicle(snapshot(setOf(SeatId.R2L)))

        assertEquals(RejectReason.BAD_JOIN_CODE, claim(e, SeatId.R2L, "1111")?.reason)
        assertFalse(connected(e, SeatId.R2L))
    }

    @Test
    fun `코드를 보내지 않으면 거절한다`() {
        val e = engine()
        e.setJoinCode("4821")
        e.onVehicle(snapshot(setOf(SeatId.R2L)))

        assertEquals(RejectReason.BAD_JOIN_CODE, claim(e, SeatId.R2L, null)?.reason)
        assertFalse(connected(e, SeatId.R2L))
    }

    @Test
    fun `코드를 심지 않은 세션은 그대로 통과시킨다`() {
        // 단위 테스트와 Fake 신호 모드가 이 경로로 돕니다. 코드가 없는 세션에서
        // 코드를 요구하면 개발 중에는 아무도 좌석을 잡을 수 없습니다.
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R2L)))

        assertNull(claim(e, SeatId.R2L, null))
        assertTrue(connected(e, SeatId.R2L))
    }

    @Test
    fun `코드가 맞아도 앉아 있지 않으면 거절한다`() {
        // 창밖에서 화면을 본 사람이 코드를 알아도, 앉지 않으면 들어오지 못합니다.
        val e = engine()
        e.setJoinCode("4821")
        e.onVehicle(snapshot(emptySet()))

        assertEquals(RejectReason.SEAT_TAKEN, claim(e, SeatId.R2L, "4821")?.reason)
        assertFalse(connected(e, SeatId.R2L))
    }

    @Test
    fun `코드는 상태에 실려 차량 화면으로 간다`() {
        val e = engine()
        e.setJoinCode("4821")
        assertEquals("4821", e.state.value.joinCode)
    }
}
