package dev.semicolon.playseat.car.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Pleos Connect 시스템 UI 와 같은 색을 씁니다.
 *
 * 값은 추측이 아니라 에뮬레이터(Pleos Connect v2.1.0)의 차량 설정 화면을
 * 캡처해 픽셀에서 뽑은 것입니다. 주간·야간 모두 받아 두었습니다.
 *
 * 왜 플랫폼을 그대로 따르는가
 *   헤드유닛은 앱 하나를 쓰는 기기가 아닙니다. 내비·전화·차량설정 사이를
 *   계속 오가기 때문에, 우리 앱만 다른 언어를 쓰면 "나중에 얹은 것" 으로 보입니다.
 *   색·면 나누는 방식·타이포를 맞추면 그냥 차의 기능처럼 읽힙니다.
 *
 * 관찰한 규칙
 *   - 면은 둥근 카드로 띄우지 않고, 1dp 헤어라인으로 나눈 평면 셀로 붙입니다
 *   - 셀은 [아이콘 → 이름(굵게) → 상태값(작게)] 순서로 왼쪽 정렬합니다
 *   - 색은 거의 쓰지 않습니다. 파랑은 '지금 활성' 인 값 하나에만 씁니다
 *   - 선택된 셀은 배경을 한 단계 올리고(#F0F0F0) 값 글자를 파랑으로 바꿉니다
 *   - 선택된 세그먼트·탭은 검정으로 채우고 글자를 흰색으로 뒤집습니다
 */
data class Palette(
    val bg: Color,
    val cell: Color,
    val cellActive: Color,
    val line: Color,
    val text: Color,
    val sub: Color,
    val accent: Color,
    /** 눌린 세그먼트처럼 반전시켜 쓰는 면. 주간엔 검정, 야간엔 흰색입니다. */
    val inverse: Color,
    val onInverse: Color,
)

internal val DayPalette = Palette(
    bg = Color(0xFFFFFFFF),
    cell = Color(0xFFFFFFFF),
    cellActive = Color(0xFFF0F0F0),
    line = Color(0xFFD6D6D6),
    text = Color(0xFF000000),
    sub = Color(0xFF7C7C7C),
    accent = Color(0xFF1262F5),
    inverse = Color(0xFF000000),
    onInverse = Color(0xFFFFFFFF),
)

internal val NightPalette = Palette(
    bg = Color(0xFF151515),
    cell = Color(0xFF151515),
    cellActive = Color(0xFF222222),
    line = Color(0xFF333333),
    text = Color(0xFFDDDDDD),
    sub = Color(0xFF8E8E8E),
    accent = Color(0xFF1262F5),
    inverse = Color(0xFFFFFFFF),
    onInverse = Color(0xFF111111),
)

val LocalPalette = staticCompositionLocalOf { DayPalette }

/** 지금 팔레트. `pal.text` 처럼 짧게 쓰려고 둡니다. */
val pal: Palette
    @Composable get() = LocalPalette.current

@Composable
fun PlaySeatTheme(content: @Composable () -> Unit) {
    // 차량 설정의 주간·야간 전환을 그대로 따릅니다.
    // 밤에 흰 화면이 켜지면 눈이 부셔서 운전에 방해가 됩니다.
    val night = isSystemInDarkTheme()
    val p = if (night) NightPalette else DayPalette

    val scheme = if (night) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White,
            background = p.bg, onBackground = p.text,
            surface = p.cell, onSurface = p.text,
            outline = p.line,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White,
            background = p.bg, onBackground = p.text,
            surface = p.cell, onSurface = p.text,
            outline = p.line,
        )
    }

    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
