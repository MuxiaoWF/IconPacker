package com.muxiao.iconpacker.domain

import com.muxiao.iconpacker.core.packer.engine.IconMatchKeys

/**
 * 重复导入的合并规划（纯 Kotlin，可 JVM 单测）。
 *
 * 要解决的问题：同一项目第二次导入同一份（或新版本）ZIP 时，旧实现不做任何比对——
 * 60 张变 180 张里的 120 张全是新增，图标池凭空翻倍；更糟的是磁盘文件名
 * （`zip__<seq>__<名字>`）每次导入都从 0 重来，同名条目会**覆盖**旧槽位正在用的文件。
 *
 * 本类只回答一个问题：**这一批里哪些图标是去更新既有槽位，哪些必须新占一个槽位。**
 * 判定分两级，与 [ImportBinder] 的可靠度分层同源：
 *
 * - **强判据（组件）**：`图标 → 组件` 是权威事实。组件已经映射到某个槽位，就说明
 *   "这个应用在本项目里的图标就是那个槽位"，用它来更新不会张冠李戴。
 * - **弱判据（名称）**：只在**没有任何映射**的槽位之间比对匹配键。已映射的槽位
 *   绝不靠名字相认——两个不同的图标包各有一个 `icon.png` 时，按名字合并会把
 *   A 应用的图标悄悄换成 B 包的图，这比重复一条记录坏得多。
 *
 * 合并不上一律新增：误合并是静默的数据丢失（坏），多占一个槽位只是浪费（轻）。
 */
object ImportMergePlanner {

    /**
     * 既有图标池中的一项。
     *
     * @param components 映射到该槽位的组件。一个槽位可以服务多个组件
     *  （同一张图给多个应用用），也可能一个都没有（导入时没认出目标）。
     */
    data class Existing(
        val slot: Int,
        /** 落库的匹配键（[com.muxiao.iconpacker.data.IconAssetEntity.originalName]） */
        val originalName: String,
        val components: Set<String>,
    )

    /** 本批导入的一项 */
    data class Incoming(
        /** 在本批次中的下标，调用方据此回填槽位 */
        val index: Int,
        /** 本批推导出的匹配键（`ZipIconPlanner.matchKeyOf` 的结果） */
        val matchKey: String,
        /** 本批通过 appfilter / 包名匹配为该图标认领的组件（可能为空） */
        val components: Set<String>,
    )

    /**
     * @param updates 图标下标 → 被更新的既有槽位（槽位不变，映射因此天然保留）
     * @param inserts 需要新槽位的图标下标，保持批次原顺序以保证结果稳定
     */
    data class Plan(val updates: Map<Int, Int>, val inserts: List<Int>) {
        /** 本批在不新增任何槽位的前提下就能全部落定的比例（仅用于提示与测试断言） */
        val total: Int get() = updates.size + inserts.size
    }

    fun plan(existing: List<Existing>, incoming: List<Incoming>): Plan {
        if (incoming.isEmpty()) return Plan(emptyMap(), emptyList())

        // 组件 → 槽位（同一组件只可能映射到一个槽位，唯一索引保证）
        val slotByComponent = HashMap<String, Int>(existing.size * 2)
        for (asset in existing) {
            for (component in asset.components) slotByComponent.putIfAbsent(component, asset.slot)
        }
        // 无映射槽位才有资格参与名称比对（见类注释）
        val slotByIdentity = LinkedHashMap<String, Int>()
        for (asset in existing) {
            if (asset.components.isNotEmpty()) continue
            slotByIdentity.putIfAbsent(identityOf(asset.originalName), asset.slot)
        }
        // 一张图可能同时被多个组件认领；先按组件判据处理，再让未命中的走名称判据
        val updates = LinkedHashMap<Int, Int>(incoming.size)
        val claimed = HashSet<Int>()

        // 第一轮：强判据（组件）。同一个槽位只会被本批的一项认领，
        // 第二项如果指向同一槽位就落到新增——宁可多一条，也不两条记录抢同一槽位。
        for (item in incoming) {
            val slot = item.components.asSequence()
                .mapNotNull { slotByComponent[it] }
                .firstOrNull { it !in claimed }
                ?: continue
            updates[item.index] = slot
            claimed += slot
        }

        // 第二轮：弱判据（名称），只认还没被占用的无映射槽位
        for (item in incoming) {
            if (item.index in updates) continue
            val slot = slotByIdentity[identityOf(item.matchKey)] ?: continue
            if (slot in claimed) continue
            updates[item.index] = slot
            claimed += slot
        }

        val inserts = incoming.map { it.index }.filter { it !in updates }
        return Plan(updates, inserts)
    }

    /**
     * 名称判据的归一化口径：去图片扩展名 + 统一小写去标点。
     *
     * 必须与 [ImportBinder] 里 `iconByBareName` 的规则一致，否则会出现
     * "能自动绑定、却认不出该更新哪个槽位"的错位（同一事实两个来源，本工程的老坑）。
     */
    fun identityOf(matchKey: String): String =
        IconMatchKeys.normalize(IconMatchKeys.stripImageExtension(matchKey))
}
