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
import androidx.compose.ui.unit.TextUnit
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

private fun label(seatId: String) = SEAT_LABEL[seatId] ?: seatId

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

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)
    ) {
        Header(state, usingRealSignals, usingRealPlayback)
        Spacer(Modifier.height(14.dp))

        // 주행 중에는 운전자 화면을 축약하고 조작 권한을 동승자에게 넘깁니다.
        // 이 분기가 플레이시트의 핵심 주장입니다.
        if (driving) {
            DrivingView(state, Modifier.weight(1f), voiceStatus, canListen, onDriverSkip, onListen)
        } else {
            ParkedView(state, joinUrl, lastAlbum, Modifier.weight(1f))
        }

        debug?.let { Spacer(Modifier.height(12.dp)); DebugBar(it) }
    }
}

@Composable
private fun Header(state: SessionState, realSignals: Boolean, realPlayback: Boolean) {
    val driving = state.driveState == DriveState.DRIVING.name
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("플레이시트", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Chip(if (driving) "주행 중" else "정차 중", if (driving) TurnColor else Dim)
        if (state.mode == SessionMode.CHARGING_LOUNGE) {
            Spacer(Modifier.width(6.dp)); Chip("충전 라운지", Color(0xFF49D17F))
        }
        if (state.landing) {
            Spacer(Modifier.width(6.dp)); Chip("도착 준비", TurnColor)
        }
        Spacer(Modifier.width(6.dp))
        if (!realSignals) Chip("Fake 신호", Dim)
        if (!realPlayback) { Spacer(Modifier.width(6.dp)); Chip("시뮬 재생", Dim) }
        Spacer(Modifier.weight(1f))
        state.etaSeconds?.let { Text("도착까지 ${it / 60}분 ${it % 60}초", color = Dim, fontSize = 14.sp) }
    }
}

/* ---------------- 정차 중: 전체 조작 가능 ---------------- */

@Composable
private fun ParkedView(
    state: SessionState,
    joinUrl: String,
    lastAlbum: DriveAlbumMsg?,
    modifier: Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.mode == SessionMode.CHARGING_LOUNGE) LoungeBanner()
            SeatMap(state)
            JoinCard(joinUrl)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NowPlayingCard(state)
            if (lastAlbum != null) AlbumCard(lastAlbum)
            QueueCard(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LoungeBanner() {
    Banner(
        bg = Color(0xFF11291D), border = Color(0xFF2F6B47), accent = Color(0xFF49D17F),
        title = "충전 라운지 모드",
        body = "충전이 끝날 때까지 차례를 기다리지 않습니다. 아무 좌석이나 바로 곡을 넣을 수 있습니다.",
    )
}

@Composable
private fun SeatMap(state: SessionState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Label("좌석")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                SeatBox(state, SeatId.R1L, Modifier.weight(1f))
                SeatBox(state, SeatId.R1R, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                SeatBox(state, SeatId.R2L, Modifier.weight(1f))
                SeatBox(state, SeatId.R2C, Modifier.weight(1f))
                SeatBox(state, SeatId.R2R, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            val lounge = state.mode == SessionMode.CHARGING_LOUNGE
            Text(
                when {
                    lounge -> "라운지 모드 — 차례 없이 모두 추가 가능"
                    state.turnSeatId != null -> "다음 선곡 차례 · ${label(state.turnSeatId)}"
                    else -> "탑승을 기다리고 있습니다"
                },
                color = if (!lounge && state.turnSeatId != null) TurnColor else Dim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SeatBox(state: SessionState, seatId: String, modifier: Modifier) {
    val seat: SeatDto? = state.seats.firstOrNull { it.seatId == seatId }
    val isTurn = state.turnSeatId == seatId && state.mode != SessionMode.CHARGING_LOUNGE
    val occupied = seat?.occupied == true

    Surface(
        modifier.height(78.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (occupied) Color(0xFF111620) else Color(0xFF0F131A),
        border = BorderStroke(1.dp, if (isTurn) TurnColor else Line),
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            Arrangement.Center,
            Alignment.CenterHorizontally,
        ) {
            Text(
                label(seatId), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (occupied) MaterialTheme.colorScheme.onSurface else Dim,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    !occupied -> "빈 좌석"
                    seat?.nickname != null -> seat.nickname
                    else -> "참여 대기"
                },
                fontSize = 11.sp, color = Dim,
            )
            if (seat?.profile == SeatProfile.CHILD && occupied) {
                Text("아동 프로필", fontSize = 10.sp, color = Dim)
            }
        }
    }
}

@Composable
private fun JoinCard(joinUrl: String) {
    val qr = remember(joinUrl) { qrImage(joinUrl) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (qr != null) {
                Image(qr, "동승자 접속 QR", Modifier.size(100.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(14.dp))
            }
            Column {
                Label("동승자 참여")
                Spacer(Modifier.height(6.dp))
                Text("폰으로 QR을 스캔하세요", fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("최초 1회만 하면 다음부터 자동 연결됩니다", fontSize = 12.sp, color = Dim)
                Spacer(Modifier.height(4.dp))
                Text(joinUrl, fontSize = 11.sp, color = Dim)
            }
        }
    }
}

@Composable
private fun NowPlayingCard(state: SessionState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Label("지금 재생")
            Spacer(Modifier.height(8.dp))
            TrackText(state.nowPlaying, 18.sp)
            state.nowPlaying?.let { np ->
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (np.positionMs ?: 0).toFloat() / np.durationMs.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("스킵 투표 ${state.skipVotes.size}/${state.skipThreshold}", fontSize = 12.sp, color = Dim)
            }
        }
    }
}

@Composable
private fun QueueCard(state: SessionState, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Label("대기 중인 곡 ${state.queue.size}")
            Spacer(Modifier.height(4.dp))
            // 큐 총 길이와 도착까지 남은 시간을 함께 보여 줍니다.
            // 도착 시점에 곡이 어중간하게 끊기지 않게 맞추는 것이 설계 의도입니다.
            state.queueTargetMs?.let { target ->
                val fill = (state.queueTotalMs * 100 / target.coerceAtLeast(1)).toInt()
                Text(
                    "큐 ${state.queueTotalMs / 60000}분 / 도착까지 ${target / 60000}분 (${fill}%)",
                    fontSize = 11.sp, color = Dim,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (state.queue.isEmpty()) {
                Text("비어 있습니다", color = Dim, fontSize = 13.sp)
            } else {
                state.queue.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, fontSize = 14.sp)
                            Text(t.artist, fontSize = 11.sp, color = Dim)
                        }
                        Chip(label(t.requestedBySeat), Dim)
                    }
                }
            }
        }
    }
}

/** 이동 1회가 끝나면 남는 기록. 재방문 동기를 만드는 장치입니다. */
@Composable
private fun AlbumCard(album: DriveAlbumMsg) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14202B)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Label("드라이브 앨범")
            Spacer(Modifier.height(6.dp))
            Text(
                "${album.durationMs / 60000}분 · 함께 들은 ${album.tracks.size}곡",
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            val who = album.seats.mapNotNull { it.nickname }
            if (who.isNotEmpty()) {
                Text(who.joinToString(", "), fontSize = 12.sp, color = Dim)
                Spacer(Modifier.height(6.dp))
            }
            album.tracks.takeLast(4).forEach { t ->
                Text("· ${t.title} — ${t.artist}", fontSize = 12.sp, color = Dim)
            }
            Spacer(Modifier.height(6.dp))
            Text("각자 폰으로 전송되었습니다", fontSize = 11.sp, color = Dim)
        }
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        Label("지금 재생")
        Spacer(Modifier.height(10.dp))
        TrackText(state.nowPlaying, 34.sp)

        Spacer(Modifier.height(10.dp))
        Text(
            state.nowPlaying?.let { "${label(it.requestedBySeat)} 선곡" } ?: "",
            fontSize = 15.sp, color = Dim,
        )

        Spacer(Modifier.height(26.dp))
        // 주행 중 터치 타깃은 2개로 제한하고, 목록·검색·키보드는 노출하지 않습니다.
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(onSkip, Modifier.height(74.dp).weight(1f)) {
                Text("다음 곡", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onListen, Modifier.height(74.dp).weight(1f), enabled = canListen) {
                Text(if (canListen) "말하기" else "마이크 권한 필요", fontSize = 19.sp)
            }
        }

        voiceStatus?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
        }

        Spacer(Modifier.height(22.dp))
        if (state.landing) {
            Banner(
                bg = Color(0xFF2A2112), border = Color(0xFF5A4A1E), accent = TurnColor,
                title = "도착 5분 전 — 랜딩 구간",
                body = "곡이 끊기지 않게 마무리합니다. 템포를 낮추고 내릴 준비를 안내합니다.",
            )
        } else {
            Banner(
                bg = Color(0xFF2A2112), border = Color(0xFF5A4A1E), accent = TurnColor,
                title = "주행 중에는 선곡 조작이 동승자 폰으로 넘어갑니다",
                body = "운전자는 음성으로 참여하세요 — \"다음 곡\", \"내 차례 넘겨\", \"이 노래 좋아\"",
            )
        }

        state.turnSeatId?.let {
            Spacer(Modifier.height(14.dp))
            Text("다음 선곡 차례 · ${label(it)}", color = Dim, fontSize = 14.sp)
        }
    }
}

/* ---------------- 공통 ---------------- */

@Composable
private fun Banner(bg: Color, border: Color, accent: Color, title: String, body: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = bg, border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = Dim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TrackText(track: TrackDto?, size: TextUnit) {
    if (track == null) {
        Text("재생 중인 곡이 없습니다", fontSize = size, color = Dim)
    } else {
        Text(track.title, fontSize = size, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(track.artist, fontSize = size * 0.5f, color = Dim)
    }
}

@Composable
private fun Label(text: String) =
    Text(text, fontSize = 11.sp, color = Dim, fontWeight = FontWeight.SemiBold)

@Composable
private fun Chip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = Color.Transparent, border = BorderStroke(1.dp, Line)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), fontSize = 11.sp, color = color)
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
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF14202B))) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("신호 주입", fontSize = 11.sp, color = Dim, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                TextButton(a.onToggleDrive) { Text("주행/정차", fontSize = 13.sp) }
                TextButton(a.onToggleCharging) { Text("충전", fontSize = 13.sp) }
                SeatId.ALL.forEach { id ->
                    TextButton({ a.onToggleSeat(id) }) { Text(label(id), fontSize = 13.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("목적지 ETA", fontSize = 11.sp, color = Dim, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                TextButton({ a.onSetEta(9600) }) { Text("2시간 40분", fontSize = 13.sp) }
                TextButton({ a.onSetEta(480) }) { Text("8분", fontSize = 13.sp) }
                TextButton({ a.onSetEta(240) }) { Text("4분 (랜딩)", fontSize = 13.sp) }
                TextButton({ a.onSetEta(null) }) { Text("목적지 없음", fontSize = 13.sp) }
            }
        }
    }
}
