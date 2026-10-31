package dev.semicolon.playseat.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 선 아이콘.
 *
 * Pleos 시스템 UI 는 셀마다 얇은 단색 선 아이콘을 답니다(창문·도어·시트·충전구).
 * 글자만 있는 셀은 그 화면들 사이에서 혼자 비어 보이기 때문에 같은 굵기로 맞춥니다.
 * 24dp 기준 2dp, 끝은 둥글게 — 캡처해서 재 본 값입니다.
 *
 * 아이콘 묶음을 따로 들이지 않고 직접 그립니다. 필요한 게 네 개뿐이고,
 * 그중 '위에서 본 좌석' 은 어느 묶음에도 없습니다.
 */
private const val BASE = 24f

private fun DrawScope.strokeOf(size: Dp) = Stroke(
    width = 2.dp.toPx() * (size.toPx() / BASE.dp.toPx()).coerceAtLeast(0.6f),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

/**
 * 좌석. 옆에서 본 모습입니다.
 *
 * 위에서 내려다본 모양으로 먼저 그려 봤는데 항아리처럼 보였습니다.
 * 등받이와 방석이 ㄴ 자로 꺾인 옆모습이 훨씬 빨리 '좌석' 으로 읽힙니다.
 * Pleos 설정의 '시트' 아이콘도 옆모습입니다.
 */
@Composable
fun SeatIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / BASE
        val s = strokeOf(size)
        // 등받이와 방석을 떼어 놓습니다. 한 덩어리로 이으면 꺾쇠처럼 보입니다.
        // 등받이가 좁고 길면 마이크처럼 보여서, 넓게 잡고 뒤로 살짝 눕힙니다.
        rotate(degrees = -9f, pivot = Offset(9 * u, 15 * u)) {
            drawRoundRectStroke(5 * u, 3.5f * u, 8f * u, 11.5f * u, 3f * u, color, s)
        }
        drawRoundRectStroke(4.5f * u, 15.5f * u, 15.5f * u, 5f * u, 2.4f * u, color, s)
    }
}

/** 다음 곡. */
@Composable
fun NextIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / BASE
        val s = strokeOf(size)
        val tri = Path().apply {
            moveTo(6 * u, 5 * u); lineTo(15 * u, 12 * u); lineTo(6 * u, 19 * u); close()
        }
        drawPath(tri, color, style = s)
        drawLine(color, Offset(18 * u, 5 * u), Offset(18 * u, 19 * u), s.width, StrokeCap.Round)
    }
}

/** 재생 중인 곡이 없을 때의 자리 표시. */
@Composable
fun NoteIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / BASE
        val s = strokeOf(size)
        drawLine(color, Offset(9 * u, 18 * u), Offset(9 * u, 5 * u), s.width, StrokeCap.Round)
        drawLine(color, Offset(9 * u, 5 * u), Offset(18 * u, 7 * u), s.width, StrokeCap.Round)
        drawLine(color, Offset(18 * u, 7 * u), Offset(18 * u, 15 * u), s.width, StrokeCap.Round)
        drawCircle(color, 2.6f * u, Offset(6.4f * u, 18 * u), style = s)
        drawCircle(color, 2.6f * u, Offset(15.4f * u, 15 * u), style = s)
    }
}

/** 말하기. */
@Composable
fun MicIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / BASE
        val s = strokeOf(size)
        drawRoundRectStroke(9 * u, 3 * u, 6 * u, 11 * u, 3 * u, color, s)
        val arc = Path().apply {
            moveTo(5.5f * u, 11.5f * u)
            quadraticBezierTo(12 * u, 21 * u, 18.5f * u, 11.5f * u)
        }
        drawPath(arc, color, style = s)
        drawLine(color, Offset(12 * u, 18 * u), Offset(12 * u, 21 * u), s.width, StrokeCap.Round)
    }
}

private fun DrawScope.drawRoundRectStroke(
    x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, s: Stroke,
) = drawRoundRect(
    color = color,
    topLeft = Offset(x, y),
    size = Size(w, h),
    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
    style = s,
)

