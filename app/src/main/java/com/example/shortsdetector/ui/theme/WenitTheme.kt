package com.example.shortsdetector.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 시각 요소의 유일한 위치. 디자인 시안이 나오면 이 파일만 바꾼다.
 * 화면 코드는 색·글꼴·모서리·간격 값을 직접 쓰지 않고 여기 토큰이나 MaterialTheme 을 쓴다.
 * 개입 팝업은 View 방식이라 res/values 의 colors.xml, dimens.xml 을 따로 쓴다.
 */
object WenitTokens {
    val SpaceXs = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 12.dp
    val SpaceL = 16.dp
    val SpaceXl = 24.dp
    val SpaceXxl = 32.dp

    val RadiusS = 8.dp
    val RadiusM = 12.dp
    val RadiusL = 20.dp

    val ButtonHeight = 52.dp
    val CharacterSize = 96.dp
    val ChartHeight = 140.dp
    val ChartBarRadius = 6.dp
    val ProgressHeight = 6.dp
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3E7FF),
    onPrimaryContainer = Color(0xFF10132B),
    secondary = Color(0xFF5F6368),
    tertiary = Color(0xFF1B8A5A),
    tertiaryContainer = Color(0xFFD5F2E3),
    onTertiaryContainer = Color(0xFF06331F),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAFAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF5F6368)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C9EFF),
    onPrimary = Color(0xFF10132B),
    primaryContainer = Color(0xFF2A3166),
    onPrimaryContainer = Color(0xFFE3E7FF),
    secondary = Color(0xFFB5B8BD),
    tertiary = Color(0xFF7FD6AC),
    tertiaryContainer = Color(0xFF12452F),
    onTertiaryContainer = Color(0xFFD5F2E3),
    background = Color(0xFF18181B),
    surface = Color(0xFF232327),
    surfaceVariant = Color(0xFF3A3A40),
    onSurfaceVariant = Color(0xFFB5B8BD)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(WenitTokens.RadiusS),
    medium = RoundedCornerShape(WenitTokens.RadiusM),
    large = RoundedCornerShape(WenitTokens.RadiusL)
)

@Composable
fun WenitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
