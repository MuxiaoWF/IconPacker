package com.muxiao.iconpacker.ui.theme

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

/** 品牌蓝：与模板应用图标（#1565C0）保持一致 */
private val BrandBlue = Color(0xFF1565C0)
private val BrandBlueDark = Color(0xFF90CAF9)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFF0288D1),
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    secondary = Color(0xFF4DB6AC),
    tertiary = Color(0xFF4FC3F7),
)

/**
 * IconPacker 主题：Material 3 + 动态取色（Android 12+）。
 *
 * 动态取色与产品定位契合——用户做图标包，界面本身跟随壁纸取色；
 * 低版本回退品牌蓝固定配色。
 */
@Composable
fun IconPackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
