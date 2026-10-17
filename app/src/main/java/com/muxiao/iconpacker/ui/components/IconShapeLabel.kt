package com.muxiao.iconpacker.ui.components

import com.muxiao.iconpacker.core.packer.engine.IconShape

/**
 * IconShape 的界面中文标签；此前在编辑器与"统一形状"对话框各写了一份 when，
 * 收口到一处避免文案漂移。
 */
fun IconShape.iconShapeLabel(): String = when (this) {
    IconShape.ORIGINAL -> "原图"
    IconShape.CIRCLE -> "圆形"
    IconShape.ROUNDED_RECT -> "圆角"
    IconShape.SQUIRCLE -> "Squircle"
}
