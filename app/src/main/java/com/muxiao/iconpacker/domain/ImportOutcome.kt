package com.muxiao.iconpacker.domain

/**
 * 一次导入的结果分项（FR-03）。
 *
 * 两条导入通道（照片选择器、ZIP）共用同一个类型：ZIP 通道会填满全部字段，
 * 照片选择器通道没有包内映射表与多密度副本，对应字段保持 0/null。
 *
 * 之所以要分项而不是只报一个总数：用户需要知道"为什么只建立了 9 个映射"、
 * "我包里那 20 张图去哪了"，以及"我更新了 60 张，为什么池子里没多出 60 个"。
 * 静默丢弃与静默合并，是这一块最容易积累困惑的地方。
 *
 * @param imported 本次**新增**入池的图标数（不含更新）
 * @param updated 更新到既有槽位的图标数：池内已有同一目标（组件）的图标时，
 *  原图被本批的新文件替换、槽位不变——映射与打包结果原地生效，
 *  不会多出一条记录（见 [ImportMergePlanner]）
 * @param unchanged 与池中已有文件**字节完全一致**的条目数：既不入池也不更新，
 *  本批解出来的副本会被丢弃。重复导入同一份 ZIP 时这个数字等于整包数量
 * @param editedReset [updated] 中被清空自定义编辑的条数：原图换了，基于旧原图
 *  合成的编辑产物已不再对应，继续保留会让"更新了却看不到变化"
 * @param manifestMapped 经包内 appfilter（权威映射表）自动建立的映射数
 * @param presetMapped **[manifestMapped] 中目标应用尚未安装的条数**（预置映射）：
 *  映射已经替这些应用占好位，等它们装上就能直接用上图标。
 *  它不是独立来源，而是 manifestMapped 的子集，故不计入 [autoMapped] 总数。
 * @param packageMapped 经包名精确匹配自动建立的映射数
 * @param skipped 被跳过的非图片条目数（FR-03 验收标准 1 要求提示数量）
 * @param duplicates 因多密度重复被合并掉的条目数（单次 ZIP 内部）
 * @param manifestPath 包内 appfilter 的路径；null = 未发现映射表
 * @param truncated 因图标池槽位上限（1024）未能导入的条目数。只统计新增——
 *  更新不占新槽位，因此池满时更新仍然照常生效
 */
data class ImportOutcome(
    val imported: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val editedReset: Int = 0,
    val manifestMapped: Int = 0,
    val presetMapped: Int = 0,
    val packageMapped: Int = 0,
    val skipped: Int = 0,
    val duplicates: Int = 0,
    val manifestPath: String? = null,
    val truncated: Int = 0,
) {
    /** 自动建立映射总数 */
    val autoMapped: Int get() = manifestMapped + packageMapped

    /** 本次是否真的改动了图标池（新增或更新）。false = 全部未变化 / 全部被跳过 */
    val changed: Boolean get() = imported > 0 || updated > 0
}
