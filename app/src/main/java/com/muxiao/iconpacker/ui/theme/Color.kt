package com.muxiao.iconpacker.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 品牌色：与模板应用图标（#1565C0）保持一致。
 *
 * 界面配色由品牌蓝全量派生（见 [LightColors] / [DarkColors]），
 * 不启用动态取色——用户在做图标包，界面底色不干扰预览里的图标色彩。
 */
val BrandBlue = Color(0xFF1565C0)
val BrandBlueDark = Color(0xFF9FC9FF)

internal val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E3),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF0288D1),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCE5FF),
    onTertiaryContainer = Color(0xFF001E33),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FB),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC3C6CF),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFFA7C8FF),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceBright = Color(0xFFF8F9FB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F7),
    surfaceContainer = Color(0xFFECEDF1),
    surfaceContainerHigh = Color(0xFFE7E8EC),
    surfaceContainerHighest = Color(0xFFE1E2E6),
)

internal val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF00325C),
    primaryContainer = Color(0xFF255DA5),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFF8FCEFF),
    onTertiary = Color(0xFF003252),
    tertiaryContainer = Color(0xFF0E7BC0),
    onTertiaryContainer = Color(0xFFCCE5FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101418),
    onBackground = Color(0xFFDFE2EB),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFDFE2EB),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFDFE2EB),
    inverseOnSurface = Color(0xFF2D3035),
    inversePrimary = Color(0xFF1565C0),
    surfaceDim = Color(0xFF101418),
    surfaceBright = Color(0xFF363A40),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF26292E),
    surfaceContainerHighest = Color(0xFF31343A),
)
