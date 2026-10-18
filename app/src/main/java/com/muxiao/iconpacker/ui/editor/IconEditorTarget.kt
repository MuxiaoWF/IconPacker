package com.muxiao.iconpacker.ui.editor

/**
 * 编辑器的处理对象。
 *
 * 两种情况在**交互上完全一致**（同一套裁剪/外形/背景/边框/缩放与预览），
 * 差别只在"从哪读、往哪写"：
 * - [Slot]：图标库里的一条图标，源图与编辑产物都由仓库管理（不进此层判断）；
 * - [PackIcon]：图标包自身的图标。它在新建时还没有项目 id、编辑时又要求即时落库，
 *   所以编辑器只负责把合成结果写到 [PackIcon.outputPath]，由调用方决定怎么用。
 *
 * 之所以把"写哪儿"抽出来，而不是给 VM 加一堆 `if (packId == 0L)`：
 * 后者会让同一个类里塞两种生命周期，测试与后续维护都会分叉。
 */
sealed interface IconEditorTarget {

    /** 图标库槽位（从映射工作台进入的常规编辑） */
    data class Slot(val packId: Long, val slot: Int) : IconEditorTarget

    /** 图标包图标的一次性编辑会话：结果只写文件，不改数据库 */
    data class PackIcon(val sourcePath: String, val outputPath: String) : IconEditorTarget
}
