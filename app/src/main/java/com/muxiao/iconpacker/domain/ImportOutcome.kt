package com.muxiao.iconpacker.domain

/**
 * 一次导入的结果分项（FR-03）。
 *
 * 两条导入通道（照片选择器、ZIP）共用同一个类型：ZIP 通道会填满全部字段，
 * 照片选择器通道没有包内映射表与多密度副本，对应字段保持 0/null。
 *
 * 之所以要分项而不是只报一个总数：用户需要知道"为什么只建立了 9 个映射"，
 * 以及"我包里那 20 张图去哪了"。静默丢弃是这一块最容易积累困惑的地方。
 *
 * @param imported 实际入池的图标数
 * @param manifestMapped 经包内 appfilter（权威映射表）自动建立的映射数
 * @param presetMapped **[manifestMapped] 中目标应用尚未安装的条数**（预置映射）：
 *  映射已经替这些应用占好位，等它们装上就能直接用上图标。
 *  它不是独立来源，而是 manifestMapped 的子集，故不计入 [autoMapped] 总数。
 * @param packageMapped 经包名精确匹配自动建立的映射数
 * @param skipped 被跳过的非图片条目数（FR-03 验收标准 1 要求提示数量）
 * @param duplicates 因多密度重复被合并掉的条目数
 * @param manifestPath 包内 appfilter 的路径；null = 未发现映射表
 * @param truncated 因图标池槽位上限（1024）未能导入的条目数
 */
data class ImportOutcome(
    val imported: Int = 0,
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
}
