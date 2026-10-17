package com.muxiao.iconpacker.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距与尺寸令牌：全项目的 Spacer / padding / 图标尺寸集中在这一处，
 * 避免散落的 4/8/12/16dp 魔法值。
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
}

/** 图标与缩略图的标准尺寸链 */
object IconSize {
    val swatch: Dp = 36.dp
    val row: Dp = 44.dp
    val thumb: Dp = 48.dp
    val grid: Dp = 72.dp
    val progressSmall: Dp = 20.dp
}
