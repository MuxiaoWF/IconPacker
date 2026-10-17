package com.muxiao.iconpacker.domain

import com.muxiao.iconpacker.core.packer.engine.IconPackManifest

/**
 * 预置映射的组件名校准（FR-04）。
 *
 * 为什么需要：导入时目标应用若尚未安装，其 component 只能沿用图标包作者的写法。
 * 而同一个应用在不同 ROM / 版本下可能使用**不同的启动 Activity**（本项目实测过
 * Clash Meta 用 `MainActivityAlias` 的情况）。应用装上后若不校准，这条映射会一直
 * 匹配不上——用户看到的现象是"图标包里有这个应用的图，桌面却没生效"，
 * 而且几乎无从排查：映射在界面上明明存在。
 *
 * 纯函数：只算出"该改哪些"，实际写库交给调用方（便于 JVM 单测）。
 */
object MappingReconciler {

    /** 一条待执行的改写 */
    sealed interface Fix {
        /** 把 [from] 的映射改挂到设备上真实的组件 [to]，槽位不变 */
        data class Rebind(val from: String, val to: String, val slot: Int) : Fix

        /** [to] 已被另一条映射占用：这条预置属重复，删除即可（不让一个组件挂两条） */
        data class Drop(val component: String) : Fix
    }

    /**
     * 算出需要校准的条目。
     *
     * 三条"不动"的界线：
     * - 包仍未安装（`byPackage` 查不到）→ 保持预置状态，等下次；
     * - 组件名已经和设备一致 → 无事可做；
     * - component 解析不出包名（畸形条目）→ 不敢乱改。
     *
     * @param mappings 当前映射（component → slot）
     * @param apps 设备上已安装的可启动应用
     */
    fun plan(
        mappings: List<Pair<String, Int>>,
        apps: List<ImportBinder.AppItem>,
    ): List<Fix> {
        if (mappings.isEmpty() || apps.isEmpty()) return emptyList()

        val componentByPackage = HashMap<String, String>(apps.size)
        for ((component, packageName) in apps) componentByPackage.putIfAbsent(
            packageName,
            component
        )
        val occupied = mappings.mapTo(HashSet()) { it.first }

        val fixes = ArrayList<Fix>()
        for ((component, slot) in mappings) {
            val packageName = IconPackManifest.packageOfComponent(component) ?: continue
            val real = componentByPackage[packageName] ?: continue
            if (real == component) continue
            fixes += if (real in occupied) {
                Fix.Drop(component)
            } else {
                Fix.Rebind(component, real, slot)
            }
        }
        return fixes
    }
}
