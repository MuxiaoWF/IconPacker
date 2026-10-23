package com.muxiao.iconpacker.domain

/**
 * "同一个包名被两张图标占用"的判定（纯 Kotlin，可 JVM 单测）。
 *
 * 数据层用唯一索引 `(packId, component)` 保证一个包名只挂一条映射，所以这里的冲突
 * 不是"存了两条"，而是**这次指派会把另一张图标顶下来**。那件事必须让用户在
 * 确认之前看见——写入之后再说，用户往往已经以为自己"给两张图都填好了"。
 *
 * 三种情况刻意不算冲突（误报比不报更烦人，也会让人怀疑提示的准确性）：
 * 1. 该包名还没有任何映射；
 * 2. 该包名现在用的就是这次要指派的同一张图（重复点同一张，等于没变）；
 * 3. 正在编辑的映射本来就指向这个包名（没换目标，只是改了写法）。
 */
object MappingConflict {

    /**
     * 一次冲突命中。
     *
     * @param slot 该包名当前占用的槽位
     * @param iconName 占用者名称；图标记录缺失时为 null，调用方用通用文案兜底
     */
    data class Hit(val slot: Int, val iconName: String?)

    /**
     * @param component 解析后的目标组件（已安装应用应传设备上的真实启动组件）
     * @param slotByComponent 现有映射，**含目标未安装的预置映射**——预置映射同样占着
     *   这个包名，装不上不代表它不挡路
     * @param iconNameBySlot 槽位 → 显示名
     * @param assigningSlot 本次要指派到的槽位；null = 还没选图标（从应用行发起）
     * @param editingComponent 正在编辑的那条映射的组件；null = 新增映射
     */
    fun find(
        component: String,
        slotByComponent: Map<String, Int>,
        iconNameBySlot: Map<Int, String>,
        assigningSlot: Int?,
        editingComponent: String?,
    ): Hit? {
        val slot = slotByComponent[component] ?: return null
        if (slot == assigningSlot) return null
        if (component == editingComponent) return null
        return Hit(slot, iconNameBySlot[slot])
    }
}
