package com.muxiao.iconpacker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * IconPacker 主题：品牌蓝全量派生配色（Light/Dark），固定品牌观感。
 *
 * 不启用动态取色——用户在做图标包，界面底色不干扰预览里的图标色彩。
 */
@Composable
fun IconPackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
