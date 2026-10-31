package dev.semicolon.playseat.car.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4EA8FF)
private val Warn = Color(0xFFFFB84E)
private val Bg = Color(0xFF0E1116)
private val Card = Color(0xFF171C24)

internal val Line = Color(0xFF273041)
internal val Dim = Color(0xFF8B96A8)
internal val TurnColor = Warn
internal val CardColor = Card

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF06121F),
    secondary = Warn,
    background = Bg,
    onBackground = Color(0xFFE8ECF3),
    surface = Card,
    onSurface = Color(0xFFE8ECF3),
    surfaceVariant = Color(0xFF1F2733),
    onSurfaceVariant = Dim,
)

@Composable
fun PlaySeatTheme(content: @Composable () -> Unit) {
    // 차량 디스플레이는 야간 주행 비중이 높아 다크를 기본으로 둡니다.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
