package com.muxiao.iconpacker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * 全局形状：卡片 18dp、二级容器 14dp、徽标与缩略图 10dp。
 * 圆角阶梯收敛到「大容器大圆角、小元素小圆角」，不再出现 24dp 的胶囊感卡片。
 */
val AppShapes = Shapes(
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)
