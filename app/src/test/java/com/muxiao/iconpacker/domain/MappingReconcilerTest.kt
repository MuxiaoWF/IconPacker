package com.muxiao.iconpacker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预置映射的组件名校准。
 *
 * 这是"导入时应用没装、以后装了就能用"的最后一环：预置的组件名来自图标包作者，
 * 与设备上的真实启动 Activity 未必一致，不校准就永远匹配不上。
 */
class MappingReconcilerTest {

    private val realWechat = "ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}"
    private val realChrome = "ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}"

    private val apps = listOf(
        ImportBinder.AppItem(realWechat, "com.tencent.mm"),
        ImportBinder.AppItem(realChrome, "com.android.chrome"),
    )

    @Test
    fun `preset component is rebound to the installed one keeping its slot`() {
        val preset = "ComponentInfo{com.tencent.mm/com.tencent.mm.plugin.alias.AliasActivity}"
        val fixes = MappingReconciler.plan(listOf(preset to 3), apps)

        assertEquals(1, fixes.size)
        val rebind = fixes.single() as MappingReconciler.Fix.Rebind
        assertEquals(preset, rebind.from)
        assertEquals(realWechat, rebind.to)
        assertEquals("槽位必须原样保留，否则图标会换掉", 3, rebind.slot)
    }

    @Test
    fun `component already matching the device is left alone`() {
        assertTrue(MappingReconciler.plan(listOf(realWechat to 1), apps).isEmpty())
    }

    /** 还没装就继续等着——这正是预置映射的常态，不能被当成"错误"清掉 */
    @Test
    fun `still-uninstalled package is left untouched`() {
        assertTrue(
            MappingReconciler.plan(listOf("ComponentInfo{com.not.installed/.Main}" to 5), apps)
                .isEmpty(),
        )
    }

    /**
     * 残缺形式 `ComponentInfo{包名/}`（斜杠后无类名）必须被视为"需要校准"：
     * 部分图标包的 appfilter 以这种形式预置映射，而标准解析
     * （ComponentName.unflattenFromString）对空类名返回 null——Kvaesitso 源码
     * 即按此整条跳过；ColorOS、MIUI 等桌面同样视为无效。原样打进产物的结果是
     * "图标包没有任何可以替换的图标"（本项目实测踩过：71 条映射 69 条残缺）。
     * 应用已装时必须 Rebind 成真实启动组件。
     */
    @Test
    fun `activity-less preset is rebound to the real launcher component`() {
        val preset = "ComponentInfo{com.tencent.mm/}"
        val fixes = MappingReconciler.plan(listOf(preset to 8), apps)

        assertEquals(1, fixes.size)
        val rebind = fixes.single() as MappingReconciler.Fix.Rebind
        assertEquals(preset, rebind.from)
        assertEquals(realWechat, rebind.to)
        assertEquals(8, rebind.slot)
    }

    /** 未安装且缺类名：保持原样等校准，这是宽容型桌面（按包名匹配）还能用的兜底形态 */
    @Test
    fun `activity-less preset for an uninstalled package stays untouched`() {
        assertTrue(
            MappingReconciler.plan(listOf("ComponentInfo{com.not.installed/}" to 9), apps)
                .isEmpty(),
        )
    }

    /** 真实组件已被另一条映射占用时，这条预置是重复的，删除而非覆盖 */
    @Test
    fun `duplicate target is dropped rather than overwriting`() {
        val preset = "ComponentInfo{com.tencent.mm/.old.Alias}"
        val fixes = MappingReconciler.plan(listOf(preset to 3, realWechat to 7), apps)

        assertEquals(1, fixes.size)
        assertEquals(MappingReconciler.Fix.Drop(preset), fixes.single())
    }

    /** 解析不出包名的畸形条目不敢乱改 */
    @Test
    fun `malformed component is ignored`() {
        assertTrue(MappingReconciler.plan(listOf("not-a-component" to 1), apps).isEmpty())
    }

    @Test
    fun `empty inputs yield nothing`() {
        assertTrue(MappingReconciler.plan(emptyList(), apps).isEmpty())
        assertTrue(MappingReconciler.plan(listOf(realWechat to 1), emptyList()).isEmpty())
    }

    /** 一批里多条需要校准：装了几个新应用就修几条，未安装的原样留着 */
    @Test
    fun `multiple presets are reconciled in one pass`() {
        val fixes = MappingReconciler.plan(
            listOf(
                "ComponentInfo{com.tencent.mm/.alias.A}" to 1,
                "ComponentInfo{com.android.chrome/.old.B}" to 2,
                "ComponentInfo{com.not.installed/.C}" to 3,
            ),
            apps,
        )

        assertEquals(2, fixes.size)
        assertEquals(
            setOf(1, 2),
            fixes.filterIsInstance<MappingReconciler.Fix.Rebind>().map { it.slot }.toSet(),
        )
        assertTrue(fixes.none { it is MappingReconciler.Fix.Drop })
    }
}
