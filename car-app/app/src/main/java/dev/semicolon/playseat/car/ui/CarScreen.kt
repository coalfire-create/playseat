package dev.semicolon.playseat.car.ui

import androidx.compose.foundation.BorderStroke
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.semicolon.playseat.car.session.DriveAlbumMsg
import dev.semicolon.playseat.car.session.SeatDto
import dev.semicolon.playseat.car.session.SessionMode
import dev.semicolon.playseat.car.session.SessionState
import dev.semicolon.playseat.car.session.TrackDto
import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.SeatId
import dev.semicolon.playseat.car.vehicle.SeatProfile

/**
 * 차량 화면.
 *
 * 구성은 Pleos 시스템 UI(차량 설정·빠른 설정)를 따릅니다.
 * 둥근 카드를 띄우지 않고 1dp 헤어라인으로 나눈 평면 셀을 붙이며,
 * 셀마다 [선 아이콘 → 이름 → 상태값] 순으로 왼쪽 정렬합니다.
 * 색은 '지금 차례' 하나에만 씁니다. 자세한 근거는 [Palette] 참고.
 */

private val SEAT_LABEL = mapOf(
    SeatId.R1L to "운전석",
    SeatId.R1R to "조수석",
    SeatId.R2L to "2열 좌",
    SeatId.R2C to "2열 중",
    SeatId.R2R to "2열 우",
)

/** 좌석 축약 이름. 선곡 순서 띠처럼 가로가 좁은 곳에서 씁니다. */
private val SEAT_SHORT = mapOf(
    SeatId.R1L to "운전석",
    SeatId.R1R to "조수석",
    SeatId.R2L to "2열좌",
    SeatId.R2C to "2열중",
    SeatId.R2R to "2열우",
)

private fun label(seatId: String) = SEAT_LABEL[seatId] ?: seatId

private fun mmss(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
fun CarScreen(
    state: SessionState,
    joinUrl: String,
    usingRealSignals: Boolean,
    usingRealPlayback: Boolean,
    lastAlbum: DriveAlbumMsg?,
    voiceStatus: String?,
    canListen: Boolean,
    onDriverSkip: () -> Unit,
    onDriverPassTurn: () -> Unit,
    onListen: () -> Unit,
    debug: DebugActions? = null,
) {
    val p = pal
    val driving = state.driveState == DriveState.DRIVING.name

    Column(Modifier.fillMaxSize().background(p.bg)) {
        Header(state, usingRealSignals, usingRealPlayback)
        HLine()

        if (driving) {
            DrivingView(state, Modifier.weight(1f), voiceStatus, canListen, onDriverSkip, onListen)
        } else {
            // 주행 중에는 운전자 화면을 축약하고 조작 권한을 동승자에게 넘깁니다.
            // 이 분기가 플레이시트의 핵심 주장이고, AAOS 주의분산 제한의 전제이기도 합니다.
            Row(Modifier.weight(1f)) {
                // 위에서 본 차는 세로로 깁니다. 왼쪽 단을 그 폭에 맞춰 좁히면
                // 차가 단을 꽉 채우고, 남는 가로는 대기열이 가져갑니다.
                Column(Modifier.weight(0.5f)) {
                    if (state.mode == SessionMode.CHARGING_LOUNGE) { LoungeNotice(); HLine() }
                    SeatMap(state, onDriverPassTurn, Modifier.weight(1f))
                    HLine()
                    TurnOrderRow(state)
                    HLine()
                    JoinRow(state, joinUrl)
                }
                VLine()
                Column(Modifier.weight(1f)) {
                    NowPlaying(state, onDriverSkip)
                    HLine()
                    if (lastAlbum != null) { AlbumSection(lastAlbum); HLine() }
                    QueueList(state, Modifier.weight(1f))
                }
            }
        }

        debug?.let { HLine(); DebugBar(it) }
    }
}

/* ---------------- 머리말 ---------------- */

@Composable
private fun Header(state: SessionState, realSignals: Boolean, realPlayback: Boolean) {
    val p = pal
    val driving = state.driveState == DriveState.DRIVING.name

    val status = buildList {
        add(if (driving) "주행 중" else "정차 중")
        if (state.mode == SessionMode.CHARGING_LOUNGE) add("충전 라운지")
        if (state.landing) add("도착 준비")
        // 개발 중 상태 표시. 실제 신호·실제 재생으로 붙으면 사라집니다.
        if (!realSignals) add("Fake 신호")
        if (!realPlayback) add("시뮬 재생")
    }.joinToString(" · ")

    Row(
        Modifier.fillMaxWidth().height(100.dp).padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("플레이시트", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = p.text)
        Spacer(Modifier.width(22.dp))
        Text(status, fontSize = 22.sp, color = p.sub)
        Spacer(Modifier.weight(1f))
        state.etaSeconds?.let {
            Text("도착까지 ", fontSize = 20.sp, color = p.sub)
            Text("${it / 60}분", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text)
        }
    }
}

@Composable
private fun LoungeNotice() {
    val p = pal
    Column(Modifier.fillMaxWidth().background(p.cellActive).padding(28.dp)) {
        Text("충전 라운지 모드", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = p.accent)
        Spacer(Modifier.height(7.dp))
        Text("차례 없이 아무 좌석이나 바로 곡을 넣을 수 있습니다", fontSize = 20.sp, color = p.sub)
    }
}

/* ---------------- 좌석 ---------------- */

/**
 * 좌석도.
 *
 * 차를 위에서 내려다본 그림 위에 좌석을 얹습니다. Pleos 의 차량 설정도
 * 같은 방식이고, 표로 늘어놓는 것보다 "어느 자리인가" 가 형태로 바로 읽힙니다.
 * 앞이 위쪽입니다.
 *
 * 좌석 안에는 이름과 사람만 둡니다. 남은 시간 같은 숫자를 넣으면
 * 좁은 칸에 글자가 몰려 멀리서 읽을 수 없습니다. 지금 차례는
 * 테두리와 아래 띠로 알리고, 자세한 건 바로 아래 선곡 순서 줄이 말합니다.
 */
@Composable
private fun SeatMap(state: SessionState, onPassTurn: () -> Unit, modifier: Modifier) {
    val p = pal
    val lounge = state.mode == SessionMode.CHARGING_LOUNGE

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("좌석", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Spacer(Modifier.weight(1f))
            // 운전자는 헤드유닛을 길게 보면 안 되므로 자기 차례를 한 번에 넘깁니다.
            // 엔진도 운전석 차례일 때만 이 요청을 받습니다(SessionEngine.driverPassTurn).
            if (!lounge && state.turnSeatId == SeatId.R1L) {
                TextButton(onPassTurn) {
                    Text("내 차례 넘기기", fontSize = 21.sp, color = p.accent)
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 실제 차를 위에서 보면 세로로 깁니다. 가로로 늘리면 차로 안 보입니다.
            BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(CAR_ASPECT)) {
                val w = maxWidth
                val h = maxHeight
                CarTopView(Modifier.matchParentSize(), line = p.line, glass = p.glass)

                SeatSpot(state, SeatId.R1L, w * 0.135f, h * 0.255f, w * 0.29f, h * 0.215f)
                SeatSpot(state, SeatId.R1R, w * 0.575f, h * 0.255f, w * 0.29f, h * 0.215f)

                SeatSpot(state, SeatId.R2L, w * 0.115f, h * 0.555f, w * 0.245f, h * 0.215f)
                SeatSpot(state, SeatId.R2C, w * 0.3775f, h * 0.555f, w * 0.245f, h * 0.215f)
                SeatSpot(state, SeatId.R2R, w * 0.64f, h * 0.555f, w * 0.245f, h * 0.215f)
            }
        }
    }
}

/** 차 그림 위에 놓이는 좌석 하나. */
@Composable
private fun SeatSpot(
    state: SessionState,
    seatId: String,
    x: Dp, y: Dp, w: Dp, h: Dp,
) {
    val p = pal
    val seat: SeatDto? = state.seats.firstOrNull { it.seatId == seatId }
    val occupied = seat?.occupied == true
    val isTurn = state.turnSeatId == seatId && state.mode != SessionMode.CHARGING_LOUNGE
    val nickname = seat?.nickname

    Column(Modifier.offset(x, y).size(w, h)) {
        // 헤드레스트. 위에서 봐도 좌석으로 읽히게 하는 유일한 단서입니다.
        Box(
            Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.46f).height(10.dp)
                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                .background(if (isTurn) p.accent else if (occupied) p.sub else p.line)
        )
        Box(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                // 앉아 있는 좌석은 흰 면으로 띄우고, 빈 좌석은 실내 바닥 그대로 둡니다.
                // 둘 다 회색이면 타고 있는지 아닌지가 구분되지 않습니다.
                .background(
                    when {
                        isTurn -> p.accent.copy(alpha = 0.14f)
                        occupied -> p.cell
                        else -> Color.Transparent
                    }
                )
                .border(
                    width = if (isTurn) 2.dp else 1.dp,
                    color = if (isTurn) p.accent else p.line,
                    shape = RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label(seatId), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = if (occupied) p.text else p.sub, maxLines = 1,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    when {
                        !occupied -> "빈 좌석"
                        nickname != null -> nickname
                        seatId == SeatId.R1L -> "음성"
                        else -> "대기"
                    },
                    fontSize = 18.sp,
                    color = if (isTurn) p.accent else p.sub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // 남은 시간은 좌석 아래를 가로지르는 띠로. 숫자보다 길이가 빨리 읽힙니다.
            val left = state.turnRemainingMs
            if (isTurn && left != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                (left.toFloat() / state.turnDeadlineMs.coerceAtLeast(1))
                                    .coerceIn(0f, 1f)
                            )
                            .fillMaxHeight().background(p.accent)
                    )
                }
            }
        }
    }
}

/**
 * 가로/세로. 위에서 본 차는 세로로 깁니다.
 *
 * 실제 비율(1:2.4 쯤)을 그대로 쓰면 화면에서 가늘고 길어 좌우가 텅 빕니다.
 * Pleos 의 차량 렌더도 1:1.8 안팎으로 줄여 그립니다. 조금 더 눌러
 * 단을 꽉 채우되, 더 넓히면 차가 아니라 상자로 보입니다.
 */
private const val CAR_ASPECT = 0.60f

/** 선곡이 좌석을 어떤 순서로 도는지. 운전석이 맨 뒤인 것도 설계입니다. */
@Composable
private fun TurnOrderRow(state: SessionState) {
    val p = pal
    val lounge = state.mode == SessionMode.CHARGING_LOUNGE
    val order = SeatId.TURN_ORDER.filter { id -> state.seats.any { it.seatId == id && it.occupied } }

    Row(
        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("선곡 순서", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.text)
        Spacer(Modifier.width(20.dp))
        when {
            order.isEmpty() -> Text("탑승을 기다리고 있습니다", fontSize = 21.sp, color = p.sub)
            lounge -> Text("차례 없음 — 모두 바로 추가", fontSize = 21.sp, color = p.sub)
            else -> order.forEachIndexed { i, id ->
                if (i > 0) Text(" → ", fontSize = 18.sp, color = p.line)
                val current = state.turnSeatId == id
                Text(
                    SEAT_SHORT[id] ?: id, fontSize = 21.sp,
                    color = if (current) p.accent else p.sub,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/* ---------------- 참여 ---------------- */

@Composable
private fun JoinRow(state: SessionState, joinUrl: String) {
    val p = pal
    val qr = remember(joinUrl) { qrImage(joinUrl) }
    // 아직 폰을 붙이지 않은 좌석이 있을 때만 크게 보여 줍니다.
    // 다 붙고 나면 QR 은 화면에서 가장 쓸모없는 자리를 차지합니다.
    val waiting = state.seats.count { it.occupied && it.nickname == null && it.seatId != SeatId.R1L }
    val big = waiting > 0

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (qr != null) {
            // QR 은 어두운 모듈이 밝은 바탕 위에 있어야 읽힙니다. 야간에도 흰 판을 깝니다.
            Box(Modifier.background(Color.White).padding(8.dp)) {
                Image(qr, "동승자 접속 QR", Modifier.size(if (big) 146.dp else 84.dp))
            }
            Spacer(Modifier.width(26.dp))
        }
        Column {
            Text(
                if (big) "폰으로 QR을 스캔하세요" else "모두 연결되었습니다",
                fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (big) "${waiting}개 좌석 대기 중 · 최초 1회만 하면 다음부터 자동 연결"
                else "새로 타는 사람은 이 QR을 스캔하세요",
                fontSize = 20.sp, color = p.sub,
            )
        }
    }
}

/* ---------------- 지금 재생 ---------------- */

@Composable
private fun NowPlaying(state: SessionState, onSkip: () -> Unit) {
    val p = pal
    val np = state.nowPlaying

    Column(Modifier.fillMaxWidth().padding(40.dp)) {
        Text("지금 재생", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.text)
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(np?.artworkUrl, 152.dp, 60.dp)
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                if (np == null) {
                    Text("재생 중인 곡이 없습니다", fontSize = 32.sp, color = p.sub)
                    Spacer(Modifier.height(8.dp))
                    Text("좌석에서 첫 곡을 넣어 주세요", fontSize = 20.sp, color = p.sub)
                } else {
                    Text(
                        np.title, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = p.text,
                        lineHeight = 50.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(np.artist, fontSize = 24.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text("${label(np.requestedBySeat)} 선곡", fontSize = 20.sp, color = p.sub)
                }
            }
            if (np != null) {
                Spacer(Modifier.width(20.dp))
                // 운전자에게 주행 중에도 열려 있는 유일한 조작이라 정차 중에도 같은 자리에 둡니다.
                IconButton(onSkip, Modifier.size(72.dp)) { NextIcon(34.dp, p.text) }
            }
        }

        if (np != null) {
            Spacer(Modifier.height(26.dp))
            val pos = np.positionMs ?: 0
            Box(Modifier.fillMaxWidth().height(4.dp).background(p.line)) {
                Box(
                    Modifier
                        .fillMaxWidth((pos.toFloat() / np.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f))
                        .fillMaxHeight().background(p.accent)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Text("${mmss(pos)} / ${mmss(np.durationMs)}", fontSize = 20.sp, color = p.sub)
                Spacer(Modifier.weight(1f))
                Text(
                    "스킵 투표 ${state.skipVotes.size}/${state.skipThreshold}",
                    fontSize = 20.sp, color = p.sub,
                )
            }
        }
    }
}

/* ---------------- 대기열 ---------------- */

@Composable
private fun QueueList(state: SessionState, modifier: Modifier) {
    val p = pal
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("대기 중인 곡", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Spacer(Modifier.width(12.dp))
            Text("${state.queue.size}", fontSize = 22.sp, color = p.sub)
            Spacer(Modifier.weight(1f))
            // 큐 총 길이와 도착까지 남은 시간을 함께 보여 줍니다.
            // 도착 시점에 곡이 어중간하게 끊기지 않게 맞추는 것이 설계 의도입니다.
            state.queueTargetMs?.let { target ->
                Text(
                    "${state.queueTotalMs / 60000}분 / 도착 ${target / 60000}분",
                    fontSize = 19.sp, color = p.sub,
                )
            }
        }
        HLine()
        if (state.queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("차례인 좌석이 곡을 넣으면 여기에 쌓입니다", fontSize = 21.sp, color = p.sub)
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.queue.forEachIndexed { i, t ->
                    if (i > 0) HLine()
                    QueueRow(i + 1, t)
                }
            }
        }
    }
}

@Composable
private fun QueueRow(index: Int, t: TrackDto) {
    val p = pal
    Row(
        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$index", Modifier.width(40.dp), fontSize = 21.sp, color = p.sub)
        Artwork(t.artworkUrl, 60.dp, 26.dp)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontSize = 24.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(
                "${t.artist} · ${label(t.requestedBySeat)}",
                fontSize = 19.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(mmss(t.durationMs), fontSize = 20.sp, color = p.sub)
    }
}

/** 이동 1회가 끝나면 남는 기록. 재방문 동기를 만드는 장치입니다. */
@Composable
private fun AlbumSection(album: DriveAlbumMsg) {
    val p = pal
    Column(Modifier.fillMaxWidth().padding(40.dp, 28.dp)) {
        Text("드라이브 앨범", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.text)
        Spacer(Modifier.height(12.dp))
        Text(
            "${album.durationMs / 60000}분 · 함께 들은 ${album.tracks.size}곡",
            fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text,
        )
        val who = album.seats.mapNotNull { it.nickname }
        if (who.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(who.joinToString(", "), fontSize = 20.sp, color = p.sub)
        }
        Spacer(Modifier.height(8.dp))
        Text("각자 폰으로 전송되었습니다", fontSize = 19.sp, color = p.sub)
    }
}

/* ---------------- 주행 중: 운전자 화면 축약 ---------------- */

@Composable
private fun DrivingView(
    state: SessionState,
    modifier: Modifier,
    voiceStatus: String?,
    canListen: Boolean,
    onSkip: () -> Unit,
    onListen: () -> Unit,
) {
    val p = pal
    val np = state.nowPlaying

    // 화면이 1682dp 로 넓어서 그대로 왼쪽 정렬하면 오른쪽이 텅 빕니다.
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 1060.dp).padding(horizontal = 40.dp)) {
            Text("지금 재생", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Spacer(Modifier.height(26.dp))

            if (np == null) {
                Text("재생 중인 곡이 없습니다", fontSize = 48.sp, color = p.sub)
            } else {
                Text(
                    np.title, fontSize = 68.sp, fontWeight = FontWeight.Bold, color = p.text,
                    lineHeight = 76.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                Text(np.artist, fontSize = 30.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Text("${label(np.requestedBySeat)} 선곡", fontSize = 23.sp, color = p.sub)
                Spacer(Modifier.height(28.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).background(p.line)) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                ((np.positionMs ?: 0).toFloat() / np.durationMs.coerceAtLeast(1))
                                    .coerceIn(0f, 1f)
                            )
                            .fillMaxHeight().background(p.accent)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("${mmss(np.positionMs ?: 0)} / ${mmss(np.durationMs)}", fontSize = 20.sp, color = p.sub)
            }

            Spacer(Modifier.height(44.dp))
            // 주행 중 터치 타깃은 2개로 제한하고, 목록·검색·키보드는 노출하지 않습니다.
            // distractionOptimized 선언의 전제가 바로 이 제약입니다.
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Button(
                    onSkip,
                    Modifier.height(112.dp).weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.inverse, contentColor = p.onInverse,
                    ),
                ) {
                    NextIcon(30.dp, p.onInverse)
                    Spacer(Modifier.width(14.dp))
                    Text("다음 곡", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onListen,
                    Modifier.height(112.dp).weight(1f),
                    enabled = canListen,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, p.line),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = p.text),
                ) {
                    MicIcon(30.dp, if (canListen) p.text else p.sub)
                    Spacer(Modifier.width(14.dp))
                    Text(if (canListen) "말하기" else "마이크 권한 필요", fontSize = 28.sp)
                }
            }

            voiceStatus?.let {
                Spacer(Modifier.height(18.dp))
                Text(it, color = p.accent, fontSize = 22.sp)
            }

            Spacer(Modifier.height(36.dp))
            Column(Modifier.fillMaxWidth().background(p.cellActive).padding(26.dp)) {
                if (state.landing) {
                    Text(
                        "도착 5분 전 — 랜딩 구간",
                        fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = p.text,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text("곡이 끊기지 않게 마무리합니다", fontSize = 20.sp, color = p.sub)
                } else {
                    Text(
                        "주행 중에는 선곡 조작이 동승자 폰으로 넘어갑니다",
                        fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = p.text,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "운전자는 음성으로 참여하세요 — \"다음 곡\", \"내 차례 넘겨\", \"이 노래 좋아\"",
                        fontSize = 20.sp, color = p.sub,
                    )
                }
            }

            state.turnSeatId?.let {
                Spacer(Modifier.height(22.dp))
                Text(
                    state.turnRemainingMs
                        ?.let { ms -> "다음 선곡 차례 · ${label(it)} · ${ms / 1000}초" }
                        ?: "다음 선곡 차례 · ${label(it)}",
                    color = p.sub, fontSize = 21.sp,
                )
            }
        }
    }
}

/* ---------------- 공통 ---------------- */

/**
 * 앨범 커버.
 *
 * 주소는 폰이 검색 결과에서 받아 `queue.add` 로 넘겨 준 값입니다(계약 v0.4).
 * 차량은 다시 찾지 않습니다 — 검색은 허브의 일이고 차량 앱은 검색을 모릅니다.
 *
 * 큐레이션 목록 곡은 커버가 없을 수 있어서 없을 때를 항상 그립니다.
 * 자리를 비우면 목록의 줄 높이가 들쭉날쭉해집니다.
 */
@Composable
private fun Artwork(url: String?, size: Dp, iconSize: Dp) {
    val p = pal
    Box(Modifier.size(size).background(p.cellActive), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            NoteIcon(iconSize, p.sub)
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** 면을 나누는 1dp 가로선. 둥근 카드 대신 이것으로 구역을 만듭니다. */
@Composable
private fun HLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))

@Composable
private fun VLine() = Box(Modifier.fillMaxHeight().width(1.dp).background(pal.line))

/** 디버그 조작. Fake 신호원일 때만 노출됩니다. */
class DebugActions(
    val onToggleDrive: () -> Unit,
    val onToggleSeat: (String) -> Unit,
    val onToggleCharging: () -> Unit,
    val onSetEta: (Int?) -> Unit,
)

@Composable
private fun DebugBar(a: DebugActions) {
    val p = pal
    Column(Modifier.fillMaxWidth().background(p.cellActive).padding(horizontal = 32.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("신호 주입", fontSize = 18.sp, color = p.sub)
            Spacer(Modifier.width(14.dp))
            TextButton(a.onToggleDrive) { Text("주행/정차", fontSize = 19.sp, color = p.text) }
            TextButton(a.onToggleCharging) { Text("충전", fontSize = 19.sp, color = p.text) }
            SeatId.ALL.forEach { id ->
                TextButton({ a.onToggleSeat(id) }) { Text(label(id), fontSize = 19.sp, color = p.text) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("목적지 ETA", fontSize = 18.sp, color = p.sub)
            Spacer(Modifier.width(14.dp))
            TextButton({ a.onSetEta(9600) }) { Text("2시간 40분", fontSize = 19.sp, color = p.text) }
            TextButton({ a.onSetEta(480) }) { Text("8분", fontSize = 19.sp, color = p.text) }
            TextButton({ a.onSetEta(240) }) { Text("4분 (랜딩)", fontSize = 19.sp, color = p.text) }
            TextButton({ a.onSetEta(null) }) { Text("목적지 없음", fontSize = 19.sp, color = p.text) }
        }
    }
}
