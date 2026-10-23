package com.muxiao.iconpacker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复导入的合并判定（"更新而非重复添加"）。
 *
 * 场景主线：第一次导入 60 张，第二次导入同一图标包的新版本 120 张（原 60 + 新 60）——
 * 期望 60 条更新、60 条新增、池子只从 60 长到 120，而不是 180。
 *
 * 两条判据的边界是本类的重点：组件（强）优先，名称（弱）只在不涉及任何映射时生效。
 */
class ImportMergePlannerTest {

    private fun componentOf(id: Int) = "ComponentInfo{com.app$id/com.app$id.Main}"

    private fun existing(count: Int, mapped: Boolean = true) = (1..count).map { slot ->
        ImportMergePlanner.Existing(
            slot = slot,
            originalName = "ic_${slot.toString().padStart(4, '0')}",
            components = if (mapped) setOf(componentOf(slot)) else emptySet(),
        )
    }

    @Test
    fun `re-importing a superset updates the overlap and inserts only the new icons`() {
        val plan = ImportMergePlanner.plan(
            existing = existing(60),
            incoming = (0 until 120).map { index ->
                // 前 60 项对应已入池的槽位 1..60；后 60 项是这一版新增的应用
                val id = index + 1
                ImportMergePlanner.Incoming(
                    index = index,
                    matchKey = "ic_${id.toString().padStart(4, '0')}",
                    components = setOf(componentOf(id)),
                )
            },
        )

        assertEquals(60, plan.updates.size)
        assertEquals(120, plan.total)
        assertEquals(listOf(1, 60), listOf(plan.updates[0], plan.updates[59]))
        assertEquals((60 until 120).toList(), plan.inserts)
        // 关键断言：被占用的既有槽位是 60 个，而不是 60 + 120 = 180 条记录
        assertEquals(60, plan.updates.values.toSet().size)
    }

    @Test
    fun `an identical re-import updates every icon and inserts nothing`() {
        val plan = ImportMergePlanner.plan(
            existing = existing(60),
            incoming = (0 until 60).map { index ->
                ImportMergePlanner.Incoming(index, "ic_${(index + 1).toString().padStart(4, '0')}", setOf(componentOf(index + 1)))
            },
        )
        assertEquals(60, plan.updates.size)
        assertTrue(plan.inserts.isEmpty())
    }

    /**
     * 已映射的槽位绝不按名字相认：两个不同的图标包各有一个 `icon.png` 时，
     * 按名字合并会把 A 应用的图标悄悄换成 B 包的图。
     */
    @Test
    fun `a mapped slot is never stolen by a same-named icon from another app`() {
        val plan = ImportMergePlanner.plan(
            existing = listOf(ImportMergePlanner.Existing(1, "icon", setOf(componentOf(1)))),
            incoming = listOf(ImportMergePlanner.Incoming(0, "icon", setOf(componentOf(2)))),
        )
        assertTrue(plan.updates.isEmpty())
        assertEquals(listOf(0), plan.inserts)
    }

    /** 无映射槽位（用户自己收集的散图）允许按名称更新：这是"同名文件重导"的兜底 */
    @Test
    fun `an unmapped slot can be matched by name`() {
        val plan = ImportMergePlanner.plan(
            existing = listOf(ImportMergePlanner.Existing(7, "com.tencent.mm", emptySet())),
            incoming = listOf(ImportMergePlanner.Incoming(0, "com.tencent.mm", emptySet())),
        )
        assertEquals(mapOf(0 to 7), plan.updates)
        assertTrue(plan.inserts.isEmpty())
    }

    /**
     * 旧包没有 appfilter（靠名字入池、没有映射），新版本带上了 appfilter：
     * 组件判据落空，但名称仍能相认，于是走更新而不是复制一份。
     */
    @Test
    fun `name matching still applies when the new pack knows the component`() {
        val plan = ImportMergePlanner.plan(
            existing = listOf(ImportMergePlanner.Existing(3, "wechat", emptySet())),
            incoming = listOf(ImportMergePlanner.Incoming(0, "wechat", setOf(componentOf(9)))),
        )
        assertEquals(mapOf(0 to 3), plan.updates)
    }

    /** 同一批次里出现两个同名图标：只更新一个槽位，另一个新增——不能两条记录抢同一槽位 */
    @Test
    fun `duplicate identities inside one batch claim a slot only once`() {
        val plan = ImportMergePlanner.plan(
            existing = listOf(ImportMergePlanner.Existing(1, "wechat", emptySet())),
            incoming = listOf(
                ImportMergePlanner.Incoming(0, "wechat", emptySet()),
                ImportMergePlanner.Incoming(1, "wechat", emptySet()),
            ),
        )
        assertEquals(mapOf(0 to 1), plan.updates)
        assertEquals(listOf(1), plan.inserts)
    }

    @Test
    fun `an empty pool inserts everything in batch order`() {
        val plan = ImportMergePlanner.plan(
            existing = emptyList(),
            incoming = (0 until 5).map { ImportMergePlanner.Incoming(it, "icon$it", emptySet()) },
        )
        assertTrue(plan.updates.isEmpty())
        assertEquals(listOf(0, 1, 2, 3, 4), plan.inserts)
    }

    @Test
    fun `identity ignores extension case and punctuation`() {
        assertEquals(
            ImportMergePlanner.identityOf("Chrome.PNG"),
            ImportMergePlanner.identityOf("chrome"),
        )
        assertEquals(
            ImportMergePlanner.identityOf("com.tencent.mm"),
            ImportMergePlanner.identityOf("com_tencent_mm.webp"),
        )
    }
}
