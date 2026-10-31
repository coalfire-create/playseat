package dev.semicolon.playseat.car.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

// 차체 윤곽과 좌석이 같은 여백을 써야 점선이 좌석 사이 빈 틈에 정확히 놓입니다.
private val CABIN_TOP = 52.dp
private val CABIN_BOTTOM = 34.dp
private val CABIN_ROW_GAP = 26.dp

private fun mmss(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** 면 공통. 테두리 없이 밝기만으로 나눕니다. */
@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    color: Color = Surface1,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = color) {
        Column(Modifier.padding(30.dp), content = content)
    }
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
    val driving = state.driveState == DriveState.DRIVING.name

    Column(Modifier.fillMaxSize().background(BgBrush).padding(32.dp)) {
        Header(state, usingRealSignals, usingRealPlayback)
        Spacer(Modifier.height(24.dp))

        // 주행 중에는 운전자 화면을 축약하고 조작 권한을 동승자에게 넘깁니다.
        // 이 분기가 플레이시트의 핵심 주장이고, AAOS 주의분산 제한의 전제이기도 합니다.
        if (driving) {
            DrivingView(state, Modifier.weight(1f), voiceStatus, canListen, onDriverSkip, onListen)
        } else {
            ParkedView(state, joinUrl, lastAlbum, onDriverPassTurn, Modifier.weight(1f))
        }

        debug?.let { Spacer(Modifier.height(16.dp)); DebugBar(it) }
    }
}

@Composable
private fun Header(state: SessionState, realSignals: Boolean, realPlayback: Boolean) {
    val driving = state.driveState == DriveState.DRIVING.name
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("플레이시트", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextHi)
        Spacer(Modifier.width(20.dp))
        Chip(if (driving) "주행 중" else "정차 중", if (driving) TurnColor else Dim)
        if (state.mode == SessionMode.CHARGING_LOUNGE) {
            Spacer(Modifier.width(10.dp)); Chip("충전 라운지", LoungeColor)
        }
        if (state.landing) {
            Spacer(Modifier.width(10.dp)); Chip("도착 준비", TurnColor)
        }

        Spacer(Modifier.weight(1f))

        // 개발 중 상태 표시. 실제 신호·실제 재생으로 붙으면 사라집니다.
        if (!realSignals) { Chip("Fake 신호", Faint); Spacer(Modifier.width(10.dp)) }
        if (!realPlayback) { Chip("시뮬 재생", Faint); Spacer(Modifier.width(10.dp)) }

        state.etaSeconds?.let {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("도착까지 ", fontSize = 19.sp, color = Faint)
                Text("${it / 60}분", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextHi)
            }
        }
    }
}

/* ---------------- 정차 중: 전체 조작 가능 ---------------- */

@Composable
private fun ParkedView(
    state: SessionState,
    joinUrl: String,
    lastAlbum: DriveAlbumMsg?,
    onPassTurn: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1.04f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (state.mode == SessionMode.CHARGING_LOUNGE) LoungeBanner()
            CabinPanel(state, onPassTurn, Modifier.weight(1f))
            JoinPanel(state, joinUrl)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            NowPlayingPanel(state)
            if (lastAlbum != null) AlbumPanel(lastAlbum)
            QueuePanel(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LoungeBanner() {
    Banner(
        accent = LoungeColor,
        title = "충전 라운지 모드",
        body = "충전이 끝날 때까지 차례를 기다리지 않습니다. 아무 좌석이나 바로 곡을 넣을 수 있습니다.",
    )
}

/* ---------------- 캐빈 ---------------- */

/**
 * 좌석도.
 *
 * 이 화면에서 가장 큰 자리를 차지합니다. 플레이시트가 파는 것은 재생 목록이 아니라
 * "선곡권이 좌석에 있다"는 사실이고, 그것을 한눈에 보여주는 게 이 면입니다.
 * 차체 윤곽을 깔아 앞뒤를 형태로 알려주고, 좌석에는 헤드레스트를 그려
 * 사각형이 아니라 '자리'로 읽히게 합니다.
 */
@Composable
private fun CabinPanel(state: SessionState, onPassTurn: () -> Unit, modifier: Modifier) {
    val lounge = state.mode == SessionMode.CHARGING_LOUNGE

    // 차례 좌석이 은은하게 맥동합니다. 뒷좌석에서도 자기 차례를 놓치지 않게 하는 장치입니다.
    val pulse = rememberInfiniteTransition(label = "turn")
    val glow by pulse.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )

    Panel(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("좌석")
            Spacer(Modifier.weight(1f))
            // 운전자는 헤드유닛을 길게 보면 안 되므로 자기 차례를 한 번에 넘깁니다.
            // 엔진도 운전석 차례일 때만 이 요청을 받습니다(SessionEngine.driverPassTurn).
            if (!lounge && state.turnSeatId == SeatId.R1L) {
                TextButton(onPassTurn) { Text("내 차례 넘기기", fontSize = 20.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            CabinOutline(Modifier.matchParentSize(), CABIN_TOP, CABIN_BOTTOM, CABIN_ROW_GAP)
            Column(
                Modifier.fillMaxSize()
                    .padding(start = 34.dp, end = 34.dp, top = CABIN_TOP, bottom = CABIN_BOTTOM)
            ) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SeatTile(state, SeatId.R1L, glow, Modifier.weight(1f).fillMaxHeight())
                    SeatTile(state, SeatId.R1R, glow, Modifier.weight(1f).fillMaxHeight())
                }
                Spacer(Modifier.height(CABIN_ROW_GAP))
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SeatTile(state, SeatId.R2L, glow, Modifier.weight(1f).fillMaxHeight())
                    SeatTile(state, SeatId.R2C, glow, Modifier.weight(1f).fillMaxHeight())
                    SeatTile(state, SeatId.R2R, glow, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        TurnOrder(state)
    }
}

@Composable
private fun SeatTile(state: SessionState, seatId: String, glow: Float, modifier: Modifier) {
    val seat: SeatDto? = state.seats.firstOrNull { it.seatId == seatId }
    val occupied = seat?.occupied == true
    val isTurn = state.turnSeatId == seatId && state.mode != SessionMode.CHARGING_LOUNGE
    val c = seatColor(seatId)
    val nickname = seat?.nickname

    // 배경은 좌석색(정체성), 테두리는 앰버(차례라는 상태).
    // 둘을 섞으면 운전석처럼 무채색인 좌석의 차례가 보이지 않습니다.
    val bg = when {
        isTurn -> c.copy(alpha = glow)
        occupied -> c.copy(alpha = 0.10f)
        else -> Surface2.copy(alpha = 0.55f)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 헤드레스트. 이것 하나로 사각형이 '좌석'이 됩니다.
        // 좌석 윗면과 맞닿아야 하나의 형태로 읽히므로, 아래 모서리는 깎지 않고
        // 좌석 쪽 윗 모서리도 덜 깎습니다.
        Box(
            Modifier.fillMaxWidth(0.4f).height(14.dp)
                .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                .background(
                    if (occupied) c.copy(alpha = if (isTurn) 0.95f else 0.5f) else LineSoft
                )
        )
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp, topEnd = 12.dp,
                        bottomStart = 26.dp, bottomEnd = 26.dp,
                    )
                )
                .background(bg)
                .then(
                    if (isTurn) Modifier.border(
                        3.dp, TurnColor,
                        RoundedCornerShape(
                            topStart = 12.dp, topEnd = 12.dp,
                            bottomStart = 26.dp, bottomEnd = 26.dp,
                        ),
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label(seatId),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (occupied) TextHi else Faint,
                )
                Spacer(Modifier.height(8.dp))

                // 닉네임은 좌석 색으로 씁니다. 사람과 색을 묶어 두면
                // 대기열에서 색 점만 보고도 누가 넣은 곡인지 압니다.
                Text(
                    when {
                        !occupied -> "빈 좌석"
                        nickname != null -> nickname
                        else -> "참여 대기"
                    },
                    fontSize = if (nickname != null && occupied) 23.sp else 20.sp,
                    fontWeight = if (nickname != null && occupied) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        !occupied -> Faint
                        nickname != null -> c
                        else -> Dim
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (occupied && nickname == null) {
                    Spacer(Modifier.height(5.dp))
                    // 운전자는 QR 로 참여하지 않습니다. 음성이 유일한 경로입니다.
                    Text(
                        if (seatId == SeatId.R1L) "음성으로 참여" else "QR 스캔 대기",
                        fontSize = 17.sp, color = Faint,
                    )
                }

                val picks = state.queue.count { it.requestedBySeat == seatId }
                if (picks > 0) {
                    Spacer(Modifier.height(7.dp))
                    Text("대기 ${picks}곡", fontSize = 18.sp, color = Faint)
                }

                if (seat?.profile == SeatProfile.CHILD && occupied) {
                    Spacer(Modifier.height(9.dp))
                    Pill("아동 프로필", Dim)
                }
            }

            // 남은 시간은 좌석 아래쪽을 가로지르는 선으로 보여 줍니다.
            // 숫자보다 줄어드는 길이가 멀리서 빨리 읽힙니다.
            val left = state.turnRemainingMs
            if (isTurn && left != null) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(TurnColor.copy(alpha = 0.18f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(
                                    (left.toFloat() / state.turnDeadlineMs.coerceAtLeast(1))
                                        .coerceIn(0f, 1f)
                                )
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(999.dp))
                                .background(TurnColor)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 선곡이 좌석을 어떤 순서로 도는지.
 *
 * 좌석도 바로 아래에 둡니다. 이 앱이 파는 게 재생 목록이 아니라 순서라는 걸
 * 계속 말하고, 운전석이 맨 뒤에 오는 것도 설계이지 우연이 아닙니다.
 */
@Composable
private fun TurnOrder(state: SessionState) {
    val lounge = state.mode == SessionMode.CHARGING_LOUNGE
    val order = SeatId.TURN_ORDER.filter { id -> state.seats.any { it.seatId == id && it.occupied } }

    if (order.isEmpty()) {
        Text("탑승을 기다리고 있습니다", fontSize = 21.sp, color = Dim)
        return
    }
    if (lounge) {
        Text("라운지 모드 — 차례 없이 모두 추가 가능", fontSize = 21.sp, color = LoungeColor)
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("선곡 순서")
        Spacer(Modifier.width(18.dp))
        order.forEachIndexed { i, id ->
            if (i > 0) {
                Text("→", Modifier.padding(horizontal = 7.dp), fontSize = 17.sp, color = Faint)
            }
            val current = state.turnSeatId == id
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (current) TurnColor.copy(alpha = 0.18f) else Color.Transparent,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(seatColor(id)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        SEAT_SHORT[id] ?: id,
                        fontSize = 18.sp,
                        color = if (current) TurnColor else Dim,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/* ---------------- 참여 ---------------- */

@Composable
private fun JoinPanel(state: SessionState, joinUrl: String) {
    val qr = remember(joinUrl) { qrImage(joinUrl) }
    // 아직 폰을 붙이지 않은 좌석이 있을 때만 크게 보여 줍니다.
    // 다 붙고 나면 QR 은 화면에서 가장 쓸모없는 자리를 차지합니다.
    val waiting = state.seats.count { it.occupied && it.nickname == null && it.seatId != SeatId.R1L }

    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (qr != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(if (waiting > 0) 14.dp else 10.dp))
                        .background(Color.White).padding(9.dp)
                ) {
                    Image(qr, "동승자 접속 QR", Modifier.size(if (waiting > 0) 158.dp else 86.dp))
                }
                Spacer(Modifier.width(24.dp))
            }
            Column {
                Label("동승자 참여")
                Spacer(Modifier.height(10.dp))
                if (waiting > 0) {
                    Text(
                        "폰으로 QR을 스캔하세요",
                        fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = TextHi,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${waiting}개 좌석이 아직 연결되지 않았습니다", fontSize = 20.sp, color = Dim)
                    Spacer(Modifier.height(8.dp))
                    Text("최초 1회만 하면 다음부터 자동 연결됩니다", fontSize = 18.sp, color = Faint)
                } else {
                    Text(
                        "모두 연결되었습니다",
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = TextHi,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("새로 타는 사람은 이 QR을 스캔하세요", fontSize = 19.sp, color = Faint)
                }
            }
        }
    }
}

/* ---------------- 지금 재생 ---------------- */

@Composable
private fun NowPlayingPanel(state: SessionState) {
    val np = state.nowPlaying
    val c = seatColor(np?.requestedBySeat)

    Panel(Modifier.fillMaxWidth()) {
        Label("지금 재생")
        Spacer(Modifier.height(16.dp))

        if (np == null) {
            Text("재생 중인 곡이 없습니다", fontSize = 36.sp, color = Faint)
            Spacer(Modifier.height(10.dp))
            Text("좌석에서 첫 곡을 넣어 주세요", fontSize = 20.sp, color = Faint)
            return@Panel
        }

        // 앨범아트가 없는 대신 곡을 고른 좌석의 색을 왼쪽 띠로 세웁니다.
        // 정사각형 자리를 비우는 만큼 제목을 크게 쓸 수 있습니다.
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier.width(6.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp)).background(c)
            )
            Spacer(Modifier.width(22.dp))
            Column {
                Text(
                    np.title, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = TextHi,
                    lineHeight = 58.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(np.artist, fontSize = 26.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(11.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(10.dp))
                    Text("${label(np.requestedBySeat)} 선곡", fontSize = 21.sp, color = c)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        val pos = np.positionMs ?: 0
        ProgressBar(pos.toFloat() / np.durationMs.coerceAtLeast(1), c)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${mmss(pos)} / ${mmss(np.durationMs)}", fontSize = 20.sp, color = Dim)
            Spacer(Modifier.weight(1f))
            Text("스킵 투표", fontSize = 19.sp, color = Faint)
            Spacer(Modifier.width(10.dp))
            repeat(state.skipThreshold) { i ->
                Box(
                    Modifier.padding(start = 5.dp).size(13.dp).clip(CircleShape)
                        .background(if (i < state.skipVotes.size) TurnColor else Line)
                )
            }
        }
    }
}

/* ---------------- 대기열 ---------------- */

@Composable
private fun QueuePanel(state: SessionState, modifier: Modifier) {
    Panel(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("대기 중인 곡")
            Spacer(Modifier.width(12.dp))
            Text("${state.queue.size}", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Dim)
            Spacer(Modifier.weight(1f))
            // 큐 총 길이와 도착까지 남은 시간을 함께 보여 줍니다.
            // 도착 시점에 곡이 어중간하게 끊기지 않게 맞추는 것이 설계 의도입니다.
            state.queueTargetMs?.let { target ->
                val fill = (state.queueTotalMs * 100 / target.coerceAtLeast(1)).toInt()
                Text(
                    "${state.queueTotalMs / 60000}분 / 도착 ${target / 60000}분 · ${fill}%",
                    fontSize = 18.sp, color = Faint,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (state.queue.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("비어 있습니다", fontSize = 24.sp, color = Faint)
                    Spacer(Modifier.height(10.dp))
                    Text("차례인 좌석이 곡을 넣으면 여기에 쌓입니다", fontSize = 19.sp, color = Faint)
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.queue.forEachIndexed { i, t -> QueueRow(i + 1, t) }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(index: Int, t: TrackDto) {
    val c = seatColor(t.requestedBySeat)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$index", Modifier.width(34.dp),
            fontSize = 20.sp, color = Faint, fontWeight = FontWeight.Medium,
        )
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontSize = 24.sp, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                "${t.artist} · ${label(t.requestedBySeat)}",
                fontSize = 18.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(mmss(t.durationMs), fontSize = 19.sp, color = Faint)
    }
}

/** 이동 1회가 끝나면 남는 기록. 재방문 동기를 만드는 장치입니다. */
@Composable
private fun AlbumPanel(album: DriveAlbumMsg) {
    Panel(Modifier.fillMaxWidth(), color = Color(0xFF13202C)) {
        Label("드라이브 앨범")
        Spacer(Modifier.height(12.dp))
        Text(
            "${album.durationMs / 60000}분 · 함께 들은 ${album.tracks.size}곡",
            fontSize = 27.sp, fontWeight = FontWeight.Bold, color = TextHi,
        )
        val who = album.seats.mapNotNull { it.nickname }
        if (who.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(who.joinToString(", "), fontSize = 20.sp, color = Dim)
        }
        Spacer(Modifier.height(14.dp))
        album.tracks.takeLast(3).forEach { t ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(seatColor(t.requestedBySeat)))
                Spacer(Modifier.width(12.dp))
                Text(
                    "${t.title} — ${t.artist}", fontSize = 19.sp, color = Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("각자 폰으로 전송되었습니다", fontSize = 18.sp, color = Faint)
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
    val np = state.nowPlaying
    val c = seatColor(np?.requestedBySeat)

    // 화면이 1682dp 로 넓어서 그대로 왼쪽 정렬하면 오른쪽이 텅 빕니다.
    // 폭을 제한해 가운데로 모읍니다.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 1120.dp)) {
            Label("지금 재생")
            Spacer(Modifier.height(20.dp))

            if (np == null) {
                Text("재생 중인 곡이 없습니다", fontSize = 56.sp, color = Faint)
            } else {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier.width(8.dp).fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp)).background(c)
                    )
                    Spacer(Modifier.width(28.dp))
                    Column {
                        Text(
                            np.title, fontSize = 72.sp, fontWeight = FontWeight.Bold, color = TextHi,
                            lineHeight = 80.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(np.artist, fontSize = 32.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(13.dp).clip(CircleShape).background(c))
                            Spacer(Modifier.width(12.dp))
                            Text("${label(np.requestedBySeat)} 선곡", fontSize = 24.sp, color = c)
                        }
                    }
                }
                Spacer(Modifier.height(26.dp))
                ProgressBar((np.positionMs ?: 0).toFloat() / np.durationMs.coerceAtLeast(1), c)
                Spacer(Modifier.height(12.dp))
                Text("${mmss(np.positionMs ?: 0)} / ${mmss(np.durationMs)}", fontSize = 20.sp, color = Dim)
            }

            Spacer(Modifier.height(44.dp))
            // 주행 중 터치 타깃은 2개로 제한하고, 목록·검색·키보드는 노출하지 않습니다.
            // distractionOptimized 선언의 전제가 바로 이 제약입니다.
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Button(onSkip, Modifier.height(116.dp).weight(1f), shape = RoundedCornerShape(26.dp)) {
                    Text("다음 곡", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onListen,
                    Modifier.height(116.dp).weight(1f),
                    enabled = canListen,
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(2.dp, if (canListen) Accent else Line),
                ) {
                    Text(if (canListen) "말하기" else "마이크 권한 필요", fontSize = 30.sp)
                }
            }

            voiceStatus?.let {
                Spacer(Modifier.height(18.dp))
                Text(it, color = Accent, fontSize = 23.sp)
            }

            Spacer(Modifier.height(34.dp))
            if (state.landing) {
                Banner(
                    accent = TurnColor,
                    title = "도착 5분 전 — 랜딩 구간",
                    body = "곡이 끊기지 않게 마무리합니다. 템포를 낮추고 내릴 준비를 안내합니다.",
                )
            } else {
                Banner(
                    accent = TurnColor,
                    title = "주행 중에는 선곡 조작이 동승자 폰으로 넘어갑니다",
                    body = "운전자는 음성으로 참여하세요 — \"다음 곡\", \"내 차례 넘겨\", \"이 노래 좋아\"",
                )
            }

            state.turnSeatId?.let {
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(11.dp).clip(CircleShape).background(TurnColor))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        state.turnRemainingMs
                            ?.let { ms -> "다음 선곡 차례 · ${label(it)} · ${ms / 1000}초" }
                            ?: "다음 선곡 차례 · ${label(it)}",
                        color = Dim, fontSize = 22.sp,
                    )
                }
            }
        }
    }
}

/* ---------------- 공통 ---------------- */

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .clip(RoundedCornerShape(999.dp)).background(color)
        )
    }
}

/** 왼쪽에 색 막대를 둔 안내 배너. 배경색을 따로 만들지 않고 강조색의 투명도로 통일합니다. */
@Composable
private fun Banner(accent: Color, title: String, body: String) {
    // height(IntrinsicSize.Min) 이 없으면 왼쪽 색 막대의 fillMaxHeight 가
    // 남은 세로 공간을 전부 가져가 옆 면을 밀어냅니다.
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(20.dp)).background(accent.copy(alpha = 0.10f))
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(24.dp)) {
            Text(title, color = accent, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Dim, fontSize = 20.sp)
        }
    }
}

@Composable
private fun Label(text: String) = Text(
    text, fontSize = 18.sp, color = Faint,
    fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
)

/** 상태 칩. 테두리 없이 색의 투명도로만 면을 만듭니다. */
@Composable
private fun Chip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.14f)) {
        Text(
            text, Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            fontSize = 19.sp, color = color, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.20f)) {
        Text(
            text, Modifier.padding(horizontal = 13.dp, vertical = 5.dp),
            fontSize = 17.sp, color = color, fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 디버그 조작. Fake 신호원일 때만 노출됩니다. */
class DebugActions(
    val onToggleDrive: () -> Unit,
    val onToggleSeat: (String) -> Unit,
    val onToggleCharging: () -> Unit,
    val onSetEta: (Int?) -> Unit,
)

@Composable
private fun DebugBar(a: DebugActions) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Surface2.copy(alpha = 0.6f),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("신호 주입", fontSize = 17.sp, color = Faint, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(14.dp))
                TextButton(a.onToggleDrive) { Text("주행/정차", fontSize = 19.sp) }
                TextButton(a.onToggleCharging) { Text("충전", fontSize = 19.sp) }
                SeatId.ALL.forEach { id ->
                    TextButton({ a.onToggleSeat(id) }) { Text(label(id), fontSize = 19.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("목적지 ETA", fontSize = 17.sp, color = Faint, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(14.dp))
                TextButton({ a.onSetEta(9600) }) { Text("2시간 40분", fontSize = 19.sp) }
                TextButton({ a.onSetEta(480) }) { Text("8분", fontSize = 19.sp) }
                TextButton({ a.onSetEta(240) }) { Text("4분 (랜딩)", fontSize = 19.sp) }
                TextButton({ a.onSetEta(null) }) { Text("목적지 없음", fontSize = 19.sp) }
            }
        }
    }
}
