package dev.semicolon.playseat.car.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 헤드유닛 기준 테마.
 *
 * 화면은 12.8인치 2560x1440 / 160dpi 이고, 앱이 쓰는 영역은 1682x1210dp 입니다.
 * 보는 거리가 70cm 안팎이라 폰 기준 타이포(11~14sp)는 그대로 쓸 수 없습니다.
 * 본문 20sp, 카드 제목 24sp, 곡 제목 36sp 이상을 기준으로 잡았습니다.
 *
 * 야간 주행 비중이 높아 다크 고정이고, 배경은 완전한 검정 대신
 * 위에서 아래로 아주 옅게 떨어지는 그라디언트를 씁니다.
 *
 * 면은 테두리로 나누지 않고 밝기 차이로만 나눕니다. 선이 많으면
 * 흔들리는 차 안에서 시선이 분산되고, 운전 중에 훑어야 하는 화면에서는
 * "어디를 봐야 하는지" 가 흐려집니다. 테두리를 쓰는 곳은 지금 차례인
 * 좌석 하나뿐이고, 그래서 그 하나가 멀리서도 바로 보입니다.
 */

internal val Bg = Color(0xFF05070A)
internal val BgTop = Color(0xFF0D141E)
internal val Surface1 = Color(0xFF111823)
internal val Surface2 = Color(0xFF18212E)
internal val Line = Color(0xFF2A3341)
internal val LineSoft = Color(0xFF1C2430)

internal val TextHi = Color(0xFFEDF1F7)
internal val Dim = Color(0xFF8B95A7)
internal val Faint = Color(0xFF5B6575)

internal val Accent = Color(0xFF4FA8FF)
internal val TurnColor = Color(0xFFFFC061)
internal val LoungeColor = Color(0xFF45D184)
internal val CardColor = Surface1

/** 배경 그라디언트. 위쪽이 살짝 밝아 화면이 납작해 보이지 않습니다. */
internal val BgBrush = Brush.verticalGradient(listOf(BgTop, Bg))

/**
 * 좌석마다 고유색을 둡니다.
 *
 * 좌석도·대기열·지금 재생에서 같은 색을 반복해서 쓰기 때문에,
 * 글자를 읽지 않아도 "누가 고른 곡인가"가 보입니다.
 * 뒷좌석에서 앞 화면까지는 1m 가까이 떨어져 있어
 * 좌석 이름을 읽게 만드는 것보다 색으로 구분시키는 편이 빠릅니다.
 *
 * 운전석만 무채색입니다. 운전자는 선곡의 주체가 아니라는
 * 이 제품의 명제를 색으로도 말합니다.
 */
internal val SEAT_COLOR = mapOf(
    "R1L" to Color(0xFF6E7A8C),
    "R1R" to Color(0xFF4FA8FF),
    "R2L" to Color(0xFF3BD6B5),
    "R2C" to Color(0xFFC58CFF),
    "R2R" to Color(0xFFFF9B5A),
)

internal fun seatColor(seatId: String?): Color = SEAT_COLOR[seatId] ?: Dim

/** 지금 재생 중인 곡의 아트 자리. 곡을 고른 좌석의 색으로 칠합니다. */
internal fun seatBrush(c: Color) =
    Brush.linearGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.30f)))

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04121F),
    secondary = TurnColor,
    background = Bg,
    onBackground = TextHi,
    surface = Surface1,
    onSurface = TextHi,
    surfaceVariant = Surface2,
    onSurfaceVariant = Dim,
    outline = Line,
)

@Composable
fun PlaySeatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
