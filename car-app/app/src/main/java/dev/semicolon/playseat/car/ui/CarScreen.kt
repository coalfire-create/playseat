package dev.semicolon.playseat.car.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
                Column(Modifier.weight(1.06f)) {
                    if (state.mode == SessionMode.CHARGING_LOUNGE) { LoungeNotice(); HLine() }
                    SeatGrid(state, onDriverPassTurn, Modifier)
                    HLine()
                    TurnOrderRow(state)
                    HLine()
                    JoinRow(state, joinUrl)
                    HLine()
                    // 내용이 끝난 뒤의 여백입니다. 가운데를 비우면 끊긴 것처럼 보입니다.
                    Spacer(Modifier.weight(1f))
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
 * Pleos 의 빠른 설정이 창문·도어·충전구를 셀로 늘어놓는 방식 그대로입니다.
 * 1열 둘 / 2열 셋으로 두어 자리 배치가 그대로 읽히고, 지금 차례인 좌석만
 * 배경을 한 단계 올리고 값 글자를 파랑으로 바꿉니다 — 시스템의 '활성' 표기와 같습니다.
 */
@Composable
private fun SeatGrid(state: SessionState, onPassTurn: () -> Unit, modifier: Modifier) {
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
        HLine()
        // 셀 높이를 고정합니다. 남은 세로를 전부 나눠 가지면 큰 셀 안에서
        // 아이콘과 글자가 멀찍이 떨어져 빈 상자처럼 보입니다.
        Row(Modifier.fillMaxWidth().height(SEAT_ROW_H)) {
            SeatCell(state, SeatId.R1L, Modifier.weight(1f).fillMaxHeight())
            VLine()
            SeatCell(state, SeatId.R1R, Modifier.weight(1f).fillMaxHeight())
        }
        HLine()
        Row(Modifier.fillMaxWidth().height(SEAT_ROW_H)) {
            SeatCell(state, SeatId.R2L, Modifier.weight(1f).fillMaxHeight())
            VLine()
            SeatCell(state, SeatId.R2C, Modifier.weight(1f).fillMaxHeight())
            VLine()
            SeatCell(state, SeatId.R2R, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

private val SEAT_ROW_H = 290.dp

@Composable
private fun SeatCell(state: SessionState, seatId: String, modifier: Modifier) {
    val p = pal
    val seat: SeatDto? = state.seats.firstOrNull { it.seatId == seatId }
    val occupied = seat?.occupied == true
    val isTurn = state.turnSeatId == seatId && state.mode != SessionMode.CHARGING_LOUNGE
    val nickname = seat?.nickname

    val value = when {
        isTurn -> state.turnRemainingMs?.let { "선곡 차례 · ${it / 1000}초" } ?: "선곡 차례"
        !occupied -> "빈 좌석"
        nickname != null -> nickname
        seatId == SeatId.R1L -> "음성으로 참여"
        else -> "QR 스캔 대기"
    }

    Box(modifier.background(if (isTurn) p.cellActive else p.cell)) {
        // 아이콘은 위, 글자는 아래. Pleos 의 설정 셀이 쓰는 배치입니다.
        // 가운데로 모으면 큰 셀 안에서 내용이 둥둥 뜹니다.
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            SeatIcon(44.dp, if (occupied) p.text else p.sub)
            Column {
            Text(
                label(seatId), fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                color = if (occupied) p.text else p.sub,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                value, fontSize = 21.sp,
                color = if (isTurn) p.accent else p.sub,
                fontWeight = if (isTurn) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val picks = state.queue.count { it.requestedBySeat == seatId }
            if (picks > 0) {
                Spacer(Modifier.height(6.dp))
                Text("대기 ${picks}곡", fontSize = 19.sp, color = p.sub)
            }
            if (seat?.profile == SeatProfile.CHILD && occupied) {
                Spacer(Modifier.height(6.dp))
                Text("아동 프로필", fontSize = 19.sp, color = p.sub)
            }
            }
        }
        // 활성 표시. Pleos 가 작업표시줄에서 쓰는 밑줄과 같은 방식입니다.
        if (isTurn) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(p.accent))
        }
    }
}

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
        Spacer(Modifier.width(28.dp))
        when {
            order.isEmpty() -> Text("탑승을 기다리고 있습니다", fontSize = 21.sp, color = p.sub)
            lounge -> Text("차례 없음 — 모두 바로 추가", fontSize = 21.sp, color = p.sub)
            else -> order.forEachIndexed { i, id ->
                if (i > 0) Text("  →  ", fontSize = 19.sp, color = p.line)
                val current = state.turnSeatId == id
                Text(
                    label(id), fontSize = 21.sp,
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
            Box(
                Modifier.size(136.dp).background(p.cellActive),
                contentAlignment = Alignment.Center,
            ) { NoteIcon(56.dp, p.sub) }
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
        Text("$index", Modifier.width(44.dp), fontSize = 21.sp, color = p.sub)
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
