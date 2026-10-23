package com.muxiao.iconpacker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "同一个包名被两张图标占用"的提示判定。
 *
 * 重点在**不误报**：一个总在报警的提示等于没有提示，用户会学会无视它。
 */
class MappingConflictTest {

    private val wechatComponent = "ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}"

    private val slots = mapOf(wechatComponent to 7)
    private val names = mapOf(7 to "com.tencent.mm")

    @Test
    fun `assigning a package that another icon already serves is a conflict`() {
        val hit = MappingConflict.find(
            component = wechatComponent,
            slotByComponent = slots,
            iconNameBySlot = names,
            assigningSlot = 12,
            editingComponent = null,
        )
        assertEquals(MappingConflict.Hit(7, "com.tencent.mm"), hit)
    }

    @Test
    fun `a package with no mapping is not a conflict`() {
        assertNull(
            MappingConflict.find(
                component = "ComponentInfo{com.android.chrome/com.android.chrome.Main}",
                slotByComponent = slots,
                iconNameBySlot = names,
                assigningSlot = 12,
                editingComponent = null,
            ),
        )
    }

    /** 重复指派同一张图：槽位没变，没有任何东西被顶掉 */
    @Test
    fun `re-assigning the same icon is not a conflict`() {
        assertNull(
            MappingConflict.find(
                component = wechatComponent,
                slotByComponent = slots,
                iconNameBySlot = names,
                assigningSlot = 7,
                editingComponent = null,
            ),
        )
    }

    /** 编辑既有映射但目标没换（只改了包名写法）：不该吓唬用户 */
    @Test
    fun `editing a mapping without changing its target is not a conflict`() {
        assertNull(
            MappingConflict.find(
                component = wechatComponent,
                slotByComponent = slots,
                iconNameBySlot = names,
                assigningSlot = null,
                editingComponent = wechatComponent,
            ),
        )
    }

    /** 从应用行发起（还没选图标）同样要提示：包名已经被别的图占着了 */
    @Test
    fun `adding a target without an icon still reports the conflict`() {
        val hit = MappingConflict.find(
            component = wechatComponent,
            slotByComponent = slots,
            iconNameBySlot = names,
            assigningSlot = null,
            editingComponent = null,
        )
        assertEquals(7, hit?.slot)
    }

    /** 图标记录缺失时仍要报出冲突，只是名字为 null（调用方走通用文案） */
    @Test
    fun `a missing icon record keeps the hit but drops the name`() {
        val hit = MappingConflict.find(
            component = wechatComponent,
            slotByComponent = slots,
            iconNameBySlot = emptyMap(),
            assigningSlot = 12,
            editingComponent = null,
        )
        assertEquals(MappingConflict.Hit(7, null), hit)
    }
}
