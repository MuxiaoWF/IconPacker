package com.muxiao.iconpacker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * IconPacker 主题：品牌蓝全量派生配色（Light/Dark），固定品牌观感。
 *
 * 不启用动态取色——用户在做图标包，界面底色不干扰预览里的图标色彩。
 *
 * 除 Material 的 colorScheme 外，另注入一层版式语义色 [AppPalette]
 * （页面 / 卡片 / 分隔线 / 状态色），供卡片与徽标直接取用：
 * colorScheme 的 surfaceContainer* 阶梯表达不了"哪些是内容卡片"，
 * 各页面各猜一层就会出现浅色下"整屏灰块"的观感。
 */
@Composable
fun IconPackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
