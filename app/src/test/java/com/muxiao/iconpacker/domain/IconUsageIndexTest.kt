package com.muxiao.iconpacker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标库反查索引回归（FR-04 反查）。
 *
 * 守住的核心事实：**目标未安装的预置映射同样是"被用上"**。
 * 真机实测：导入一套 appfilter 覆盖 71 个应用、本机一个都没装的图标包后，
 * 图标库 66 张图标全部显示"未使用"——而这些图标在库里都有一条映射。
 * 索引把预置映射排除在外，就是直接制造这个假象。
 */
class IconUsageIndexTest {

    private val wechatDevice = "ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}"
    private val installed = listOf(
        IconUsageIndex.InstalledApp(wechatDevice, "com.tencent.mm", "微信"),
        IconUsageIndex.InstalledApp(
            "ComponentInfo{com.android.settings/com.android.settings.Settings}",
            "com.android.settings",
            "设置",
        ),
    )

    /** 目标未安装的预置映射必须出现在反查结果里，否则图标会被标成"未使用" */
    @Test
    fun `preset mapping to an uninstalled app still counts as used`() {
        val index = IconUsageIndex.build(
            mappings = listOf("ComponentInfo{com.qihoo360.mobilesafe/}" to 1),
            installed = installed,
        )
        val entry = index.getValue(1).single()
        assertFalse("未安装的目标不应被标成已安装", entry.installed)
        assertNull("取不到可读名称，交给调用方用包名兜底", entry.label)
        assertEquals("com.qihoo360.mobilesafe", entry.packageName)
        assertEquals("ComponentInfo{com.qihoo360.mobilesafe/}", entry.component)
    }

    /** 已安装的目标带可读名称，并按包名认（组件名可能是作者写的别名 Activity） */
    @Test
    fun `installed app is resolved by package name and carries its label`() {
        val aliasActivity = "ComponentInfo{com.tencent.mm/com.tencent.mm.ui.MainActivityAlias}"
        val entry = IconUsageIndex.build(
            mappings = listOf(aliasActivity to 8),
            installed = installed,
        ).getValue(8).single()
        assertTrue(entry.installed)
        assertEquals("微信", entry.label)
        assertEquals("com.tencent.mm", entry.packageName)
    }

    /** 同一张图标可被多个目标使用（FR-04 验收 4），已安装与预置混排各按各的状态 */
    @Test
    fun `one slot fans out to several targets with per-target status`() {
        val index = IconUsageIndex.build(
            mappings = listOf(
                wechatDevice to 3,
                "ComponentInfo{com.not.installed/.Main}" to 3,
            ),
            installed = installed,
        )
        val targets = index.getValue(3)
        assertEquals(2, targets.size)
        assertTrue(targets[0].installed)
        assertFalse(targets[1].installed)
    }

    /** 没有映射的图标不进索引——调用方据此显示"未使用"，这条界线不能被模糊掉 */
    @Test
    fun `empty mappings produce an empty index`() {
        assertTrue(IconUsageIndex.build(emptyList(), installed).isEmpty())
    }

    /** 畸形 component（解析不出包名）也不该丢，退回原串展示，让用户能看到并改掉它 */
    @Test
    fun `malformed component falls back to the raw string`() {
        val entry = IconUsageIndex.build(
            mappings = listOf("not-a-component" to 5),
            installed = installed,
        ).getValue(5).single()
        assertFalse(entry.installed)
        assertEquals("not-a-component", entry.packageName)
    }
}
