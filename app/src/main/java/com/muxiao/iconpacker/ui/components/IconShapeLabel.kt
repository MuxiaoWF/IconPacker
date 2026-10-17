package com.muxiao.iconpacker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.core.packer.engine.IconShape

/**
 * IconShape 的界面标签；此前在编辑器与"统一形状"对话框各写了一份 when，
 * 收口到一处避免文案漂移。
 */
@Composable
fun IconShape.iconShapeLabel(): String = when (this) {
    IconShape.ORIGINAL -> stringResource(R.string.shape_original)
    IconShape.CIRCLE -> stringResource(R.string.shape_circle)
    IconShape.ROUNDED_RECT -> stringResource(R.string.shape_rounded)
    IconShape.SQUIRCLE -> stringResource(R.string.shape_squircle)
}
