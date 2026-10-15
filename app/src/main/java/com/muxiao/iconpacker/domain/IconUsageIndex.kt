package com.muxiao.iconpacker.domain

import com.muxiao.iconpacker.core.packer.engine.IconPackManifest

/**
 * 图标库反查索引：槽位 → 使用该图标的映射目标（FR-04 反查）。
 *
 * 与映射列表（**只列本机已安装的可启动应用**，FR-04 验收 11）的关键差异：
 * 这里**必须包含目标尚未安装的预置映射**。两者问的不是同一件事——
 * 映射列表回答"这台设备上哪些应用该换图标"，图标库回答"这张图有没有被用上"。
 * 预置映射就是"已被用上"，只不过要等应用装上来才在桌面上生效。
 *
 * 排除它们会出现一个很具体的错误现象：导入一套覆盖数百应用的图标包后，
 * 图标库里绝大多数图标被标成"未使用"——而这些图标其实都在 appfilter 里有主，
 * 用户读到的是"导入失败/映射丢了"，与事实相反（真机实测：66 张图标全部显示"未使用"，
 * 而库里实际有 71 条映射）。
 *
 * 纯函数，可 JVM 单测。
 */
object IconUsageIndex {

    /**
     * 一个映射目标。
     *
     * @param label 已安装应用的可读名称；null = 目标未安装（调用方用包名兜底显示）
     * @param installed 目标应用当前是否已安装
     */
    data class Entry(
        val component: String,
        val packageName: String,
        val label: String?,
        val installed: Boolean,
    )

    /** 本机已安装的可启动应用（[label] 为可读名称） */
    data class InstalledApp(val component: String, val packageName: String, val label: String)

    /**
     * @param mappings 当前映射（component → slot），保持插入顺序
     * @param installed 本机已安装的可启动应用
     * @return slot → 目标列表；没有映射的槽位不出现在结果里
     */
    fun build(
        mappings: List<Pair<String, Int>>,
        installed: List<InstalledApp> = emptyList(),
    ): Map<Int, List<Entry>> {
        if (mappings.isEmpty()) return emptyMap()

        val installedByComponent = installed.associateBy { it.component }
        val installedByPackage = installed.associateBy { it.packageName }

        val result = LinkedHashMap<Int, MutableList<Entry>>()
        val seen = HashSet<String>()
        for ((component, slot) in mappings) {
            // 映射表本身有 (packId, component) 唯一约束，这层去重只为对畸形输入免疫
            if (!seen.add(component)) continue
            val packageName = IconPackManifest.packageOfComponent(component) ?: component
            // 组件先匹配，其次按包名认：组件名可能尚未校准（预置映射沿用作者写法），
            // 但包名一定对得上；两条都落空才是真的没装。
            val app = installedByComponent[component] ?: installedByPackage[packageName]
            result.getOrPut(slot) { ArrayList() } += Entry(
                component = component,
                packageName = app?.packageName ?: packageName,
                label = app?.label,
                installed = app != null,
            )
        }
        return result
    }
}
