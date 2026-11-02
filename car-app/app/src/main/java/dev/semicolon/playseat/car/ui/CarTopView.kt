package dev.semicolon.playseat.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 위에서 내려다본 차체.
 *
 * Pleos 의 차량 설정도 차를 위에서 본 그림 위에 조작을 얹습니다.
 * 좌석을 표로 늘어놓는 것보다 "어느 자리인가" 가 형태로 바로 읽힙니다.
 * 사진 같은 렌더는 만들 수 없으니 같은 굵기의 선으로만 그립니다.
 *
 * 앞이 위쪽입니다. 좌석은 이 그림 위에 따로 얹으므로 여기서는
 * 차체·보닛·유리·사이드미러·콘솔만 그립니다.
 *
 * 윤곽은 세로 위치별 반폭을 찍고 세로 접선으로 이어 만듭니다.
 * 둥근 사각형으로 그리면 휴대폰처럼 보이고, 앞뒤가 어디인지 알 수 없습니다.
 *
 * 선 굵기는 두 단입니다. 차체 윤곽만 굵게 두고 유리·미러·콘솔은
 * 한 단 흐리게 그립니다. 전부 같은 굵기면 도면처럼 납작해 보입니다.
 */
@Composable
fun CarTopView(modifier: Modifier, line: Color, glass: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 차체 윤곽
        val s = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 실내 요소. 한 단 얇고 흐리게 둬서 윤곽이 먼저 읽히게 합니다.
        val t = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val faint = line.copy(alpha = 0.55f)
        val cx = w / 2f

        // (세로 위치, 반폭) — 앞이 좁고 가운데가 가장 넓습니다.
        val profile = listOf(
            0.000f to 0.255f,
            0.035f to 0.345f,
            0.095f to 0.410f,
            0.200f to 0.448f,
            0.480f to 0.462f,
            0.760f to 0.455f,
            0.890f to 0.420f,
            0.962f to 0.345f,
            1.000f to 0.255f,
        )

        fun sideTo(path: Path, sign: Float) {
            for (i in 0 until profile.size - 1) {
                val (y0, r0) = profile[i]
                val (y1, r1) = profile[i + 1]
                val mid = (y1 - y0) / 2f
                path.cubicTo(
                    cx + sign * r0 * w, (y0 + mid) * h,
                    cx + sign * r1 * w, (y1 - mid) * h,
                    cx + sign * r1 * w, y1 * h,
                )
            }
        }

        val body = Path().apply {
            moveTo(cx + profile.first().second * w, 0f)
            sideTo(this, 1f)                                   // 오른쪽을 따라 뒤로
            lineTo(cx - profile.last().second * w, h)
            for (i in profile.size - 1 downTo 1) {             // 왼쪽을 따라 앞으로
                val (y0, r0) = profile[i]
                val (y1, r1) = profile[i - 1]
                val mid = (y0 - y1) / 2f
                cubicTo(
                    cx - r0 * w, (y0 - mid) * h,
                    cx - r1 * w, (y1 + mid) * h,
                    cx - r1 * w, y1 * h,
                )
            }
            close()
        }
        // 바퀴. 차체 밑으로 살짝 드러나는 정도만. 윤곽만 있으면 보트로도 읽힙니다.
        val tireW = 0.028f * w
        val tireH = 0.085f * h
        for (ty in listOf(0.150f, 0.740f)) {
            for (sg in listOf(-1f, 1f)) {
                drawRoundRect(
                    line.copy(alpha = 0.32f),
                    Offset(cx + sg * 0.424f * w - tireW / 2f, ty * h),
                    Size(tireW, tireH),
                    CornerRadius(tireW * 0.45f, tireW * 0.45f),
                )
            }
        }

        drawPath(body, line, style = s)

        // 보닛 끝선
        drawLine(faint, Offset(cx - 0.30f * w, 0.145f * h), Offset(cx + 0.30f * w, 0.145f * h), t.width, StrokeCap.Round)

        // 앞유리. 위가 좁은 사다리꼴이라 어디가 앞인지 바로 보입니다.
        drawLine(faint, Offset(cx - 0.30f * w, 0.165f * h), Offset(cx - 0.395f * w, 0.245f * h), t.width, StrokeCap.Round)
        drawLine(faint, Offset(cx + 0.30f * w, 0.165f * h), Offset(cx + 0.395f * w, 0.245f * h), t.width, StrokeCap.Round)

        // 실내. 좌석이 놓이는 자리입니다.
        val cabin = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = cx - 0.395f * w, top = 0.245f * h,
                    right = cx + 0.395f * w, bottom = 0.815f * h,
                    cornerRadius = CornerRadius(26.dp.toPx(), 26.dp.toPx()),
                )
            )
        }
        drawPath(cabin, glass, style = Fill)
        drawPath(cabin, faint, style = t)

        // 뒷유리
        drawLine(faint, Offset(cx - 0.395f * w, 0.815f * h), Offset(cx - 0.305f * w, 0.885f * h), t.width, StrokeCap.Round)
        drawLine(faint, Offset(cx + 0.395f * w, 0.815f * h), Offset(cx + 0.305f * w, 0.885f * h), t.width, StrokeCap.Round)
        drawLine(faint, Offset(cx - 0.305f * w, 0.885f * h), Offset(cx + 0.305f * w, 0.885f * h), t.width, StrokeCap.Round)

        // 사이드미러. 차체가 캔버스 폭을 꽉 쓰므로 미러는 바깥으로 내밀 자리가
        // 없습니다. 차체 선에 절반쯤 걸쳐 그려야 잘리지 않고 붙어 보입니다.
        val mw = 0.065f * w
        val my = 0.252f * h
        drawRoundRect(
            faint, Offset(cx - 0.490f * w, my), Size(mw, 0.034f * h),
            CornerRadius(mw * 0.45f, mw * 0.45f), style = Fill,
        )
        drawRoundRect(
            faint, Offset(cx + 0.425f * w, my), Size(mw, 0.034f * h),
            CornerRadius(mw * 0.45f, mw * 0.45f), style = Fill,
        )

        // 1열 사이 센터 콘솔.
        // 테두리만 그리면 좌석 사이에 빈 칸이 하나 더 있는 것처럼 읽힙니다.
        // 면으로 옅게 채우고 좌석보다 짧게 둡니다.
        drawRoundRect(
            line.copy(alpha = 0.16f),
            Offset(cx - 0.040f * w, 0.335f * h), Size(0.080f * w, 0.115f * h),
            CornerRadius(0.018f * w, 0.018f * w),
        )
    }
}
