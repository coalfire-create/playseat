package dev.semicolon.playseat.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 좌석 뒤에 깔리는 차체 윤곽.
 *
 * 좌석을 격자로만 늘어놓으면 표가 됩니다. 이 화면이 말하려는 건
 * "자리마다 권한이 있다" 는 것이라, 어디가 앞이고 어디가 뒤인지가
 * 글자가 아니라 형태로 읽혀야 합니다.
 *
 * 위에서 내려다본 차체입니다. 앞쪽을 좁혀 방향을 주고, 윈드실드를
 * 한 줄 그어 운전석 쪽을 가리킵니다. 선은 아주 흐리게 둡니다 —
 * 주인공은 좌석이지 차 그림이 아닙니다.
 *
 * 1열과 2열을 가르는 점선은 좌석 사이 빈 틈에 정확히 놓여야 합니다.
 * 그래서 좌석을 놓는 쪽이 쓰는 여백([topPad]·[bottomPad]·[rowGap])을
 * 그대로 받아 위치를 계산합니다. 눈대중으로 비율을 넣으면
 * 창 크기가 바뀔 때마다 헤드레스트를 가로지릅니다.
 */
@Composable
fun CabinOutline(
    modifier: Modifier,
    topPad: Dp,
    bottomPad: Dp,
    rowGap: Dp,
    color: Color = LineSoft,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val taper = w * 0.065f          // 앞쪽이 좁아집니다
        val shoulder = h * 0.11f        // 좁은 앞에서 최대 폭까지 벌어지는 높이
        val rear = 56.dp.toPx()         // 뒤쪽 모서리

        val body = Path().apply {
            moveTo(taper, 0f)
            lineTo(w - taper, 0f)
            quadraticBezierTo(w, 0f, w, shoulder)
            lineTo(w, h - rear)
            quadraticBezierTo(w, h, w - rear, h)
            lineTo(rear, h)
            quadraticBezierTo(0f, h, 0f, h - rear)
            lineTo(0f, shoulder)
            quadraticBezierTo(0f, 0f, taper, 0f)
            close()
        }
        drawPath(body, color, style = Stroke(width = 2.dp.toPx()))

        // 윈드실드. 앞유리가 바깥으로 볼록해서 위로 살짝 휘게 둡니다.
        val wsY = topPad.toPx() * 0.42f
        val windshield = Path().apply {
            moveTo(taper * 1.8f, wsY)
            quadraticBezierTo(w / 2f, wsY - h * 0.028f, w - taper * 1.8f, wsY)
        }
        drawPath(windshield, color, style = Stroke(width = 2.dp.toPx()))

        // 1열과 2열 사이. 좌석 배치와 같은 식으로 계산해 빈 틈 한가운데에 둡니다.
        val gap = rowGap.toPx()
        val inner = h - topPad.toPx() - bottomPad.toPx()
        val mid = topPad.toPx() + (inner - gap) / 2f + gap / 2f
        drawLine(
            color = color,
            start = Offset(w * 0.05f, mid),
            end = Offset(w * 0.95f, mid),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 10.dp.toPx())),
        )
    }
}
