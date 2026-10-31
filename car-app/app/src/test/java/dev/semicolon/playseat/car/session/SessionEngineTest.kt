package dev.semicolon.playseat.car.session

import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.SeatId
import dev.semicolon.playseat.car.vehicle.SeatSignal
import dev.semicolon.playseat.car.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 좌석 턴 규칙은 플레이시트의 핵심이고 에뮬레이터 없이 검증할 수 있어야 합니다.
 * 차량 신호를 직접 주입해 판정만 검사합니다.
 */
class SessionEngineTest {

    private fun engine() = SessionEngine("test", now = { 0L })

    /** 제한시간 검사용. clock[0] 을 밀어 시간을 흐르게 합니다. */
    private class Clock(var ms: Long = 0L)

    private fun engineWith(clock: Clock) = SessionEngine("test", now = { clock.ms })

    private fun snapshot(
        occupied: Set<String>,
        drive: DriveState = DriveState.PARKED,
        eta: Int? = null,
        charging: Boolean = false,
    ) = VehicleSnapshot(
        seats = SeatId.ALL.map { SeatSignal(it, it in occupied) },
        driveState = drive,
        etaSeconds = eta,
        charging = charging,
    )

    private fun claim(e: SessionEngine, seatId: String, device: String) =
        e.onRequest(PhoneRequest(type = ReqType.SEAT_CLAIM, deviceId = device, seatId = seatId, nickname = device))

    private fun add(e: SessionEngine, seatId: String, title: String, durationMs: Int = 200_000) =
        e.onRequest(PhoneRequest(type = ReqType.QUEUE_ADD, seatId = seatId, title = title, artist = "a", durationMs = durationMs))

    @Test
    fun `착석한 좌석에만 턴이 배정된다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1L, SeatId.R2L)))
        val turn = e.state.value.turnSeatId
        assertNotNull(turn)
        assertTrue(turn in listOf(SeatId.R1L, SeatId.R2L))
    }

    @Test
    fun `아무도 타지 않으면 턴이 없다`() {
        val e = engine()
        e.onVehicle(snapshot(emptySet()))
        assertNull(e.state.value.turnSeatId)
    }

    @Test
    fun `짐만 실어 착석 신호가 없으면 좌석 슬롯이 생기지 않는다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1L)))
        assertEquals(1, e.state.value.seats.count { it.occupied })
    }

    @Test
    fun `자기 차례가 아니면 곡 추가가 거절된다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        val turn = e.state.value.turnSeatId!!
        val other = listOf(SeatId.R1R, SeatId.R2L).first { it != turn }

        val reject = add(e, other, "남의 차례 곡")
        assertEquals(RejectReason.NOT_YOUR_TURN, reject?.reason)
        assertTrue(e.state.value.queue.none { it.title == "남의 차례 곡" })
    }

    @Test
    fun `곡을 추가하면 턴이 다음 좌석으로 넘어간다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        val first = e.state.value.turnSeatId!!
        assertNull(add(e, first, "첫 곡"))
        assertTrue(e.state.value.turnSeatId != first)
    }

    @Test
    fun `첫 곡은 바로 재생되고 두 번째부터 큐에 쌓인다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        val a = e.state.value.turnSeatId!!
        add(e, a, "곡1")
        assertEquals("곡1", e.state.value.nowPlaying?.title)
        assertEquals(0, e.state.value.queue.size)

        val b = e.state.value.turnSeatId!!
        add(e, b, "곡2")
        assertEquals("곡1", e.state.value.nowPlaying?.title)
        assertEquals(listOf("곡2"), e.state.value.queue.map { it.title })
    }

    @Test
    fun `한 좌석은 대기 곡을 두 개까지만 넣을 수 있다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R))) // 혼자 타면 턴이 계속 자기 차례
        val seat = e.state.value.turnSeatId!!
        add(e, seat, "곡1") // 바로 재생
        add(e, seat, "곡2") // 대기 1
        add(e, seat, "곡3") // 대기 2
        val reject = add(e, seat, "곡4")
        assertEquals(RejectReason.PROFILE_BLOCKED, reject?.reason)
        assertEquals(2, e.state.value.queue.size)
    }

    @Test
    fun `아동 좌석은 아주 긴 곡을 넣지 못한다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R2C)))
        val seat = e.state.value.turnSeatId!!
        assertEquals(SeatId.R2C, seat)
        val reject = add(e, seat, "너무 긴 곡", durationMs = 500_000)
        assertEquals(RejectReason.PROFILE_BLOCKED, reject?.reason)
    }

    @Test
    fun `주행 중 운전자의 스킵 투표는 막히고 음성 경로만 열린다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1L, SeatId.R1R), drive = DriveState.DRIVING))
        add(e, e.state.value.turnSeatId!!, "곡1")

        val reject = e.onRequest(PhoneRequest(type = ReqType.VOTE_SKIP, seatId = SeatId.R1L))
        assertEquals(RejectReason.DRIVING_RESTRICTED, reject?.reason)

        // 음성 경로는 허용됩니다
        e.driverSkip()
        assertNull(e.state.value.nowPlaying)
    }

    @Test
    fun `같은 좌석이 스킵을 중복 투표할 수 없다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L, SeatId.R2R)))
        add(e, e.state.value.turnSeatId!!, "곡1")
        assertNull(e.onRequest(PhoneRequest(type = ReqType.VOTE_SKIP, seatId = SeatId.R1R)))
        val reject = e.onRequest(PhoneRequest(type = ReqType.VOTE_SKIP, seatId = SeatId.R1R))
        assertEquals(RejectReason.DUPLICATE_VOTE, reject?.reason)
    }

    @Test
    fun `과반이 스킵하면 다음 곡으로 넘어간다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        val a = e.state.value.turnSeatId!!
        add(e, a, "곡1")
        val b = e.state.value.turnSeatId!!
        add(e, b, "곡2")
        assertEquals("곡1", e.state.value.nowPlaying?.title)

        e.onRequest(PhoneRequest(type = ReqType.VOTE_SKIP, seatId = SeatId.R1R))
        e.onRequest(PhoneRequest(type = ReqType.VOTE_SKIP, seatId = SeatId.R2L))
        assertEquals("곡2", e.state.value.nowPlaying?.title)
        assertTrue(e.state.value.skipVotes.isEmpty())
    }

    @Test
    fun `하차하면 좌석 참여정보와 미재생 곡이 정리된다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        claim(e, SeatId.R2L, "dev_b")
        val a = e.state.value.turnSeatId!!
        add(e, a, "곡1")
        val b = e.state.value.turnSeatId!!
        add(e, b, "곡2")

        // R2L 하차
        e.onVehicle(snapshot(setOf(SeatId.R1R)))
        val r2l = e.state.value.seats.first { it.seatId == SeatId.R2L }
        assertEquals(false, r2l.occupied)
        assertNull(r2l.deviceId)
        assertTrue(e.state.value.queue.none { it.requestedBySeat == SeatId.R2L })
    }

    @Test
    fun `이미 다른 기기가 쓰는 좌석은 가져갈 수 없다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R2L)))
        assertNull(claim(e, SeatId.R2L, "dev_a"))
        val reject = claim(e, SeatId.R2L, "dev_b")
        assertEquals(RejectReason.SEAT_TAKEN, reject?.reason)
        assertEquals("dev_a", e.state.value.seats.first { it.seatId == SeatId.R2L }.deviceId)
    }

    @Test
    fun `빈 좌석은 참여할 수 없다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R)))
        val reject = claim(e, SeatId.R2R, "dev_x")
        assertEquals(RejectReason.SEAT_TAKEN, reject?.reason)
    }

    @Test
    fun `잔여 ETA 가 큐 목표 길이로 환산된다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1L), eta = 600))
        assertEquals(600, e.state.value.etaSeconds)
        assertEquals(600_000L, e.state.value.queueTargetMs)
    }

    @Test
    fun `스킵 기준은 최소 두 표를 요구한다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1L)))
        assertEquals(2, e.state.value.skipThreshold)
        e.onVehicle(snapshot(setOf(SeatId.R1L, SeatId.R1R, SeatId.R2L, SeatId.R2C, SeatId.R2R)))
        assertEquals(3, e.state.value.skipThreshold)
    }

    /* ---------------- v0.2: 라운지 모드 · 랜딩 · 드라이브 앨범 ---------------- */

    @Test
    fun `충전 중 정차면 라운지 모드가 켜진다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), charging = true))
        assertEquals(SessionMode.CHARGING_LOUNGE, e.state.value.mode)
    }

    @Test
    fun `충전 중이라도 주행이면 라운지 모드가 아니다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), drive = DriveState.DRIVING, charging = true))
        assertEquals(SessionMode.NORMAL, e.state.value.mode)
    }

    @Test
    fun `라운지 모드에서는 차례가 아닌 좌석도 곡을 넣을 수 있다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L), charging = true))
        val turn = e.state.value.turnSeatId
        val other = listOf(SeatId.R1R, SeatId.R2L).first { it != turn }
        assertNull(add(e, other, "라운지 곡"))
        val all = listOfNotNull(e.state.value.nowPlaying) + e.state.value.queue
        assertTrue(all.any { it.title == "라운지 곡" })
    }

    @Test
    fun `라운지 모드에서는 곡을 넣어도 차례가 넘어가지 않는다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L), charging = true))
        val before = e.state.value.turnSeatId
        add(e, SeatId.R2L, "곡")
        assertEquals(before, e.state.value.turnSeatId)
    }

    @Test
    fun `라운지 모드에서도 빈 좌석은 곡을 넣을 수 없다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R), charging = true))
        val reject = add(e, SeatId.R2R, "곡")
        assertEquals(RejectReason.SEAT_TAKEN, reject?.reason)
    }

    @Test
    fun `주행 중 도착 5분 이내면 랜딩 구간이다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1L), drive = DriveState.DRIVING, eta = 600))
        assertEquals(false, e.state.value.landing)
        e.onVehicle(snapshot(setOf(SeatId.R1L), drive = DriveState.DRIVING, eta = 240))
        assertEquals(true, e.state.value.landing)
    }

    @Test
    fun `정차 중에는 랜딩 구간이 되지 않는다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1L), drive = DriveState.PARKED, eta = 60))
        assertEquals(false, e.state.value.landing)
    }

    @Test
    fun `큐 총 길이가 집계된다`() {
        val e = engine()
        e.onVehicle(snapshot(setOf(SeatId.R1R)))
        val seat = e.state.value.turnSeatId!!
        add(e, seat, "곡1", durationMs = 200_000) // 바로 재생되므로 큐에서 빠짐
        add(e, seat, "곡2", durationMs = 180_000)
        add(e, seat, "곡3", durationMs = 120_000)
        assertEquals(300_000L, e.state.value.queueTotalMs)
    }

    @Test
    fun `주행이 끝나면 함께 들은 곡으로 드라이브 앨범이 나온다`() {
        val e = engine()
        var album: DriveAlbumMsg? = null
        e.onDriveAlbum = { album = it }

        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L), drive = DriveState.DRIVING))
        val a = e.state.value.turnSeatId!!
        add(e, a, "곡1")
        val b = e.state.value.turnSeatId!!
        add(e, b, "곡2")
        e.trackFinished() // 곡1 끝 → 곡2 재생
        e.trackFinished() // 곡2 끝

        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L), drive = DriveState.PARKED))

        assertNotNull(album)
        assertEquals(listOf("곡1", "곡2"), album!!.tracks.map { it.title })
    }

    @Test
    fun `들은 곡이 없는 주행은 앨범을 남기지 않는다`() {
        val e = engine()
        var album: DriveAlbumMsg? = null
        e.onDriveAlbum = { album = it }

        e.onVehicle(snapshot(setOf(SeatId.R1L), drive = DriveState.DRIVING))
        e.onVehicle(snapshot(setOf(SeatId.R1L), drive = DriveState.PARKED))

        assertNull(album)
    }

    @Test
    fun `앨범은 주행마다 초기화된다`() {
        val e = engine()
        val albums = mutableListOf<DriveAlbumMsg>()
        e.onDriveAlbum = { albums += it }

        // 1회차
        e.onVehicle(snapshot(setOf(SeatId.R1R), drive = DriveState.DRIVING))
        add(e, SeatId.R1R, "1회차곡")
        e.trackFinished()
        e.onVehicle(snapshot(setOf(SeatId.R1R), drive = DriveState.PARKED))

        // 2회차
        e.onVehicle(snapshot(setOf(SeatId.R1R), drive = DriveState.DRIVING))
        add(e, SeatId.R1R, "2회차곡")
        e.trackFinished()
        e.onVehicle(snapshot(setOf(SeatId.R1R), drive = DriveState.PARKED))

        assertEquals(2, albums.size)
        assertEquals(listOf("1회차곡"), albums[0].tracks.map { it.title })
        assertEquals(listOf("2회차곡"), albums[1].tracks.map { it.title })
    }

    // ---------- 차례 제한시간 ----------

    @Test
    fun `차례가 제한시간을 넘기면 다음 좌석으로 넘어간다`() {
        val clock = Clock()
        val e = engineWith(clock)
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L, SeatId.R2R)))
        claim(e, SeatId.R1R, "dev_a")
        val first = e.state.value.turnSeatId!!

        clock.ms += SessionState.TURN_DEADLINE_MS.toLong()
        e.tick()

        assertTrue(e.state.value.turnSeatId != first)
    }

    @Test
    fun `제한시간 전에는 차례가 그대로다`() {
        val clock = Clock()
        val e = engineWith(clock)
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        claim(e, SeatId.R1R, "dev_a")
        val first = e.state.value.turnSeatId!!

        clock.ms += SessionState.TURN_DEADLINE_MS - 1000L
        e.tick()

        assertEquals(first, e.state.value.turnSeatId)
    }

    @Test
    fun `곡을 넣으면 제한시간이 처음부터 다시 센다`() {
        val clock = Clock()
        val e = engineWith(clock)
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        claim(e, SeatId.R1R, "dev_a")
        claim(e, SeatId.R2L, "dev_b")

        clock.ms += 20_000
        add(e, e.state.value.turnSeatId!!, "곡1")
        val afterAdd = e.state.value.turnSeatId!!

        // 직전 차례에서 20초가 지났더라도 새 차례는 다시 30초를 받습니다
        clock.ms += 20_000
        e.tick()
        assertEquals(afterAdd, e.state.value.turnSeatId)
    }

    @Test
    fun `운전석 차례도 제한시간이 지나면 넘어간다`() {
        val clock = Clock()
        val e = engineWith(clock)
        e.onVehicle(snapshot(setOf(SeatId.R1L, SeatId.R1R)))
        claim(e, SeatId.R1R, "dev_a")

        // 운전석까지 순서를 돌립니다
        var guard = 0
        while (e.state.value.turnSeatId != SeatId.R1L && guard++ < 10) {
            clock.ms += SessionState.TURN_DEADLINE_MS.toLong()
            e.tick()
        }
        assertEquals(SeatId.R1L, e.state.value.turnSeatId)

        // 운전자는 음성으로도 곡을 추가할 수 없습니다. 여기서 멈추면 안 됩니다.
        clock.ms += SessionState.TURN_DEADLINE_MS.toLong()
        e.tick()
        assertEquals(SeatId.R1R, e.state.value.turnSeatId)
    }

    @Test
    fun `아무도 폰을 연결하지 않았으면 제한시간이 돌지 않는다`() {
        val clock = Clock()
        val e = engineWith(clock)
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        val first = e.state.value.turnSeatId!!

        clock.ms += SessionState.TURN_DEADLINE_MS * 5L
        e.tick()

        // 넘겨 봐야 아무도 쓸 수 없고, 차례 안내 음성만 반복됩니다
        assertEquals(first, e.state.value.turnSeatId)
        assertNull(e.state.value.turnRemainingMs)
    }

    @Test
    fun `라운지 모드에서는 제한시간이 돌지 않는다`() {
        val clock = Clock()
        val e = engineWith(clock)
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L), charging = true))
        claim(e, SeatId.R1R, "dev_a")
        val first = e.state.value.turnSeatId

        clock.ms += SessionState.TURN_DEADLINE_MS * 3L
        e.tick()

        assertEquals(first, e.state.value.turnSeatId)
        assertNull(e.state.value.turnRemainingMs)
    }

    @Test
    fun `혼자 타고 있으면 제한시간이 돌지 않는다`() {
        val clock = Clock()
        val e = engineWith(clock)
        e.onVehicle(snapshot(setOf(SeatId.R2L)))
        claim(e, SeatId.R2L, "dev_a")

        clock.ms += SessionState.TURN_DEADLINE_MS * 3L
        e.tick()

        assertEquals(SeatId.R2L, e.state.value.turnSeatId)
        assertNull(e.state.value.turnRemainingMs)
    }

    @Test
    fun `남은 시간이 상태에 실려 나간다`() {
        val clock = Clock()
        val e = engineWith(clock)
        e.onVehicle(snapshot(setOf(SeatId.R1R, SeatId.R2L)))
        claim(e, SeatId.R1R, "dev_a")

        clock.ms += 10_000
        e.tick()

        assertEquals(SessionState.TURN_DEADLINE_MS - 10_000, e.state.value.turnRemainingMs)
    }
}
