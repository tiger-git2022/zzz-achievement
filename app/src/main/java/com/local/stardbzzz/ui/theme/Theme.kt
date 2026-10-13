package com.local.stardbzzz.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF7C5800),
    onPrimary = Color.White,
    secondary = Color(0xFF28615E),
    tertiary = Color(0xFF87516A),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFEDE1CF),
    background = Color(0xFFFCF8F1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8C16D),
    onPrimary = Color(0xFF3F2D00),
    secondary = Color(0xFF83D5CF),
    tertiary = Color(0xFFF1B7D0),
    surface = Color(0xFF16130E),
    surfaceVariant = Color(0xFF514537),
    background = Color(0xFF11100D),
)

@Composable
fun ZZZAchievementTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
