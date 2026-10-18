package com.muxiao.iconpacker.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距令牌：4pt 基数阶梯，供组内节奏使用。
 *
 * 使用口径（新代码一律按此选档，不再随手写 dp 字面量）：
 * - [xs] 图标与文字、徽标内边距
 * - [sm] 同一行内元素之间、徽标之间
 * - [md] 表单字段之间、卡片内子块之间
 * - [lg] 卡片内边距、页面左右留白下限
 * - [xl] 卡片与卡片之间
 * - [xxl] 区块（section）之间
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/**
 * 版式令牌：页面级留白、卡片内边距、节奏间隔。
 *
 * 与 [Spacing] 的分工：[Spacing] 描述"元素之间"，[Layout] 描述"版式骨架"。
 * 页面左右取 20dp 而非 16dp——360dp 宽屏下两侧各多 4dp，卡片两侧不再贴边，
 * 滚动手势区也更安全。
 */
object Layout {
    /** 页面左右留白 */
    val pageHorizontal: Dp = 20.dp
    /** 页面首块内容距顶栏的距离 */
    val pageTop: Dp = 12.dp
    /** 列表底部留白（给 FAB / 底栏让位） */
    val pageBottom: Dp = 96.dp
    /** 卡片内边距 */
    val cardPadding: Dp = 16.dp
    /** 卡片与卡片之间的间隔 */
    val cardGap: Dp = 12.dp
    /** 区块与区块之间的间隔 */
    val sectionGap: Dp = 24.dp
    /** 列表行最小高度（可触控） */
    val rowMinHeight: Dp = 56.dp
    /** 最小可触控尺寸（M3 无障碍下限） */
    val touchTarget: Dp = 48.dp
    /** 发丝线（分隔线 / 描边） */
    val hairline: Dp = 1.dp
}

/**
 * 圆角令牌。卡片 18dp、行内小块 12dp、徽标胶囊；
 * 比旧版（8/12/16/24）整体更收敛——圆角过大在 360dp 屏上会削弱"工具感"。
 */
object Radius {
    val xs: Dp = 6.dp
    val sm: Dp = 10.dp
    val md: Dp = 14.dp
    val lg: Dp = 18.dp
    val xl: Dp = 26.dp
}

/** 图标与缩略图的标准尺寸链 */
object IconSize {
    /** 色块视觉直径（触控容器另取 48dp） */
    val swatch: Dp = 36.dp
    /** 列表行缩略图 */
    val row: Dp = 44.dp
    /** 应用行缩略图 */
    val appRow: Dp = 40.dp
    /** 项目卡片缩略图 */
    val thumb: Dp = 52.dp
    /** 进度圈（小） */
    val progressSmall: Dp = 20.dp
    /** 行内语义图标 */
    val inline: Dp = 18.dp
    /** 顶栏图标开关的选中底色直径（顶栏触控区仍是 48dp，这只是视觉底） */
    val actionChip: Dp = 32.dp
    /** 顶栏图标开关内的字形 */
    val actionGlyph: Dp = 20.dp
}

/**
 * 动效时长（毫秒）：状态切换用 [FAST]，页面级转场用 [NORMAL]。
 */
object Motion {
    const val FAST = 150
    const val NORMAL = 260
}
