package com.muxiao.iconpacker.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 项目自带的少量图标。
 *
 * 只依赖 material-icons-core（约 40 枚），筛选漏斗（FilterList / FilterAlt）不在其中。
 * 为了一个图标引入 material-icons-extended（数千枚、几 MB）不划算，
 * 且本项目只在 Release 下走 R8，Debug 包会白背这份体积，故手绘。
 */
object AppIcons {

    /** 筛选漏斗：顶边长 16，收口到中央，下方带手柄 */
    val FilterFunnel: ImageVector by lazy {
        ImageVector.Builder(
            name = "FilterFunnel",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 5f)
                lineTo(20f, 5f)
                // 左右两条斜边收到中轴，构成漏斗主体
                lineTo(13.6f, 12.4f)
                lineTo(13.6f, 18.6f)
                // 手柄：向下收窄的一段，视觉上"过滤后落下的东西"
                lineTo(10.4f, 20.5f)
                lineTo(10.4f, 12.4f)
                close()
            }
        }.build()
    }
}
