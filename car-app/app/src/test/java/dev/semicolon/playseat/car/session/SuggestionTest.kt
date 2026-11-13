package dev.semicolon.playseat.car.session

import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.SeatId
import dev.semicolon.playseat.car.vehicle.SeatSignal
import dev.semicolon.playseat.car.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "아무도 음악을 안 넣으면 차가 넣는다" 는 기능은 잘못 돌면 가장 불쾌합니다.
 * 멋대로 음악이 켜지기 때문입니다. 끼어들지 **않아야** 하는 경우를 먼저 고정합니다.
 */
class SuggestionTest {

    private class Clock(var ms: Long = 0L)

    private fun engine(clock: Clock) = SessionEngine("test", now = { clock.ms })

    private fun aboard(engine: SessionEngine, seats: Set<String> = setOf(SeatId.R1L, SeatId.R2L)) {
        engine.onVehicle(
            VehicleSnapshot(
                seats = SeatId.ALL.map { SeatSignal(it, occupied = it in seats) },
                driveState = DriveState.PARKED,
            )
        )
    }

    private fun connect(engine: SessionEngine, seatId: String, device: String) {
        engine.onRequest(
            PhoneRequest(type = ReqType.SEAT_CLAIM, deviceId = device, seatId = seatId, nickname = "n")
        )
    }

    private fun track(title: String) = TrackDto(
        itemId = "x", title = title, artist = "a", durationMs = 180_000, requestedBySeat = "AUTO",
    )

    /** 추천을 요구할 때까지 시계를 밀면서 tick 합니다. */
    private fun run(engine: SessionEngine, clock: Clock, ms: Long, step: Long = 1000) {
        var elapsed = 0L
        while (elapsed < ms) {
            clock.ms += step
            elapsed += step
            engine.tick()
        }
    }

    @Test
    fun `아무도 안 넣으면 추천을 부른다`() {
        val clock = Clock()
        val e = engine(clock)
        var asked: String? = null
        e.onNeedSuggestions = { mood, _, _ -> asked = mood }
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("RAIN", "비 오는 날", "와이퍼가 움직이고 있어요")

        run(e, clock, SessionState.SUGGEST_AFTER_MS + 2000L)
        assertEquals("RAIN", asked)
    }

    @Test
    fun `아무도 폰을 연결하지 않았으면 부르지 않는다`() {
        // 들을 사람이 없는데 음악이 켜지는 것만큼 당황스러운 것도 없습니다.
        val clock = Clock()
        val e = engine(clock)
        var asked = false
        e.onNeedSuggestions = { _, _, _ -> asked = true }
        aboard(e)
        e.onMood("CALM", "조용한 시간", "정차 중이에요")

        run(e, clock, SessionState.SUGGEST_AFTER_MS + 5000L)
        assertFalse(asked)
    }

    @Test
    fun `분위기를 아직 모르면 부르지 않는다`() {
        val clock = Clock()
        val e = engine(clock)
        var asked = false
        e.onNeedSuggestions = { _, _, _ -> asked = true }
        aboard(e); connect(e, SeatId.R2L, "d1")

        run(e, clock, SessionState.SUGGEST_AFTER_MS + 5000L)
        assertFalse(asked)
    }

    @Test
    fun `곡이 이미 있으면 부르지 않는다`() {
        val clock = Clock()
        val e = engine(clock)
        var asked = false
        e.onNeedSuggestions = { _, _, _ -> asked = true }
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("CALM", "조용한 시간", "정차 중이에요")
        e.onRequest(
            PhoneRequest(
                type = ReqType.QUEUE_ADD, seatId = SeatId.R2L,
                title = "곡", artist = "가수", durationMs = 180_000,
            )
        )

        run(e, clock, SessionState.SUGGEST_AFTER_MS + 5000L)
        assertFalse(asked)
    }

    @Test
    fun `추천이 오면 카운트다운이 돈다`() {
        val clock = Clock()
        val e = engine(clock)
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("RAIN", "비 오는 날", "와이퍼")
        e.onSuggestions(listOf(track("비 오는 날")))

        assertEquals(1, e.state.value.suggestions.size)
        assertNotNull(e.state.value.suggestAutoAddMs)

        clock.ms += 5000; e.tick()
        val left = e.state.value.suggestAutoAddMs!!
        assertTrue(left in 14_000..15_000)
    }

    @Test
    fun `카운트다운이 끝나면 큐에 담고 바로 재생한다`() {
        val clock = Clock()
        val e = engine(clock)
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("RAIN", "비 오는 날", "와이퍼")
        e.onSuggestions(listOf(track("비 오는 날"), track("밤편지")))

        run(e, clock, SessionState.SUGGEST_AUTO_ADD_MS + 2000L)

        val s = e.state.value
        assertTrue(s.suggestions.isEmpty())
        assertNull(s.suggestAutoAddMs)
        assertEquals("비 오는 날", s.nowPlaying?.title)
        assertEquals(1, s.queue.size)
        // 누가 고른 곡인지 자리에 '차가 골랐다' 가 남습니다.
        assertEquals(AutoPick.SEAT, s.nowPlaying?.requestedBySeat)
    }

    @Test
    fun `담긴 곡은 서로 다른 itemId 를 갖는다`() {
        // 같은 id 면 대기열에서 한 곡을 지울 때 두 곡이 같이 사라집니다.
        val clock = Clock()
        val e = engine(clock)
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("CALM", "조용한 시간", "정차")
        e.onSuggestions(listOf(track("A"), track("B"), track("C")))
        e.onRequest(PhoneRequest(type = ReqType.MOOD_ACCEPT, seatId = SeatId.R2L))

        val ids = (listOfNotNull(e.state.value.nowPlaying) + e.state.value.queue).map { it.itemId }
        assertEquals(3, ids.size)
        assertEquals(3, ids.distinct().size)
    }

    @Test
    fun `누구든 지금 담을 수 있다`() {
        val clock = Clock()
        val e = engine(clock)
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("CALM", "조용한 시간", "정차")
        e.onSuggestions(listOf(track("A")))

        // 자기 차례가 아니어도 됩니다. 선곡권을 더 갖는 것이 아니라
        // 차가 권한 것을 받아들이는 것뿐이기 때문입니다.
        val reject = e.onRequest(PhoneRequest(type = ReqType.MOOD_ACCEPT, seatId = SeatId.R2R))
        assertNull(reject)
        assertEquals("A", e.state.value.nowPlaying?.title)
    }

    @Test
    fun `괜찮다고 하면 한동안 다시 권하지 않는다`() {
        val clock = Clock()
        val e = engine(clock)
        var asks = 0
        e.onNeedSuggestions = { _, _, _ -> asks++ }
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("CALM", "조용한 시간", "정차")
        e.onSuggestions(listOf(track("A")))

        e.onRequest(PhoneRequest(type = ReqType.MOOD_DISMISS, seatId = SeatId.R2L))
        assertTrue(e.state.value.suggestions.isEmpty())

        run(e, clock, SessionState.SUGGEST_AFTER_MS * 3L)
        assertEquals(0, asks)
    }

    @Test
    fun `분위기가 바뀌면 이전 추천은 버린다`() {
        val clock = Clock()
        val e = engine(clock)
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("CALM", "조용한 시간", "정차")
        e.onSuggestions(listOf(track("A")))
        assertEquals(1, e.state.value.suggestions.size)

        e.onMood("RAIN", "비 오는 날", "와이퍼가 움직이고 있어요")
        assertTrue(e.state.value.suggestions.isEmpty())
    }

    @Test
    fun `같은 분위기가 다시 들어와도 추천은 유지된다`() {
        val clock = Clock()
        val e = engine(clock)
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("RAIN", "비 오는 날", "와이퍼가 움직이고 있어요")
        e.onSuggestions(listOf(track("A")))
        e.onMood("RAIN", "비 오는 날", "와이퍼가 움직이고 있어요")
        assertEquals(1, e.state.value.suggestions.size)
    }

    @Test
    fun `그 사이에 사람이 곡을 넣었으면 추천을 띄우지 않는다`() {
        val clock = Clock()
        val e = engine(clock)
        aboard(e); connect(e, SeatId.R2L, "d1")
        e.onMood("CALM", "조용한 시간", "정차")
        e.onRequest(
            PhoneRequest(
                type = ReqType.QUEUE_ADD, seatId = SeatId.R2L,
                title = "사람이 고른 곡", artist = "가수", durationMs = 180_000,
            )
        )
        e.onSuggestions(listOf(track("차가 고른 곡")))

        assertTrue(e.state.value.suggestions.isEmpty())
        assertEquals("사람이 고른 곡", e.state.value.nowPlaying?.title)
    }
}
