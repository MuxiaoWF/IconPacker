package com.muxiao.iconpacker.domain

import com.muxiao.iconpacker.core.packer.engine.IconPackManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入即建立映射的两层绑定回归。
 *
 * 边界线是"确定性 vs 猜测"：L1（包内映射表）与 L2（名字等于包名）都是精确判据，
 * 可以导入时直接生效；模糊评分（标签包含、首字母、词元重叠）不在本类覆盖范围内，
 * 它必须由用户主动触发——错配比不匹配更让人困惑。
 */
class ImportBinderTest {

    private val chromeComponent = "ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}"
    private val wechatComponent = "ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}"

    private val apps = listOf(
        ImportBinder.AppItem(chromeComponent, "com.android.chrome"),
        ImportBinder.AppItem(wechatComponent, "com.tencent.mm"),
        ImportBinder.AppItem("ComponentInfo{com.android.settings/com.android.settings.Settings}", "com.android.settings"),
    )

    /**
     * 生产链路里 matchKey 由 `ZipIconPlanner.matchKeyOf` 推导，已不含图片扩展名；
     * 这里默认按同一规则构造，避免夹具与真实输入不一致（本项目已因此漏检两次）。
     */
    private fun icon(
        index: Int,
        fileName: String,
        matchKey: String = com.muxiao.iconpacker.core.packer.engine.IconMatchKeys
            .stripImageExtension(fileName),
    ) = ImportBinder.IconItem(index, fileName, matchKey)

    // ---- L1：包内映射表（权威） ----

    @Test
    fun `manifest entry binds the appfilter drawable to its slot`() {
        val bindings = ImportBinder.bind(
            manifestEntries = listOf(
                IconPackManifest.Entry(wechatComponent, "ic_0002"),
            ),
            icons = listOf(icon(0, "ic_0001.png"), icon(1, "ic_0002.png")),
            apps = apps,
        )
        assertEquals(1, bindings.size)
        assertEquals(1, bindings.single().iconIndex)
        assertEquals(wechatComponent, bindings.single().component)
        assertEquals(ImportBinder.Source.MANIFEST, bindings.single().source)
    }

    /**
     * appfilter 里写的活动类未必是本机的启动活动（可能是别名 Activity，
     * 如 Clash Meta 的 `MainActivityAlias`），因此只认包名，再换成设备上实际
     * 可启动的组件——按整串 component 相等去匹配会大面积落空。
     */
    @Test
    fun `manifest component is resolved to the installed launch component`() {
        val bindings = ImportBinder.bind(
            manifestEntries = listOf(
                IconPackManifest.Entry(
                    "ComponentInfo{com.tencent.mm/com.tencent.mm.plugin.alias.AliasActivity}",
                    "wechat",
                ),
            ),
            icons = listOf(icon(0, "wechat.png")),
            apps = apps,
        )
        assertEquals(wechatComponent, bindings.single().component)
    }

    /**
     * 未安装的应用同样建立映射（**预置**）。
     *
     * appfilter 里本来就带着完整的组件名，应用还没装也应当先把位置占好——
     * 否则"导入一套覆盖 800 个应用的图标、手机只装了 300 个"时，剩下 500 条会被
     * 整批丢掉，而它们恰恰是"以后装了新应用就能直接用上图标"的全部依据。
     */
    @Test
    fun `entries pointing at uninstalled packages are still bound as presets`() {
        val presetComponent = "ComponentInfo{com.not.installed/.Main}"
        val bindings = ImportBinder.bind(
            manifestEntries = listOf(IconPackManifest.Entry(presetComponent, "ic_0001")),
            icons = listOf(icon(0, "ic_0001.png")),
            apps = apps,
        )
        assertEquals(1, bindings.size)
        assertEquals(presetComponent, bindings.single().component)
        assertEquals(ImportBinder.Source.MANIFEST, bindings.single().source)
        assertFalse("未安装应用应标记为预置", bindings.single().installed)
    }

    /**
     * 同一批里已安装与未安装各自走对的路径：已安装换成设备真实组件（作者的组件名可能是
     * 别名 Activity），未安装保留 appfilter 原值。两者混在一起时不能互相污染。
     */
    @Test
    fun `mixed batch keeps installed and preset entries apart`() {
        val presetComponent = "ComponentInfo{com.not.installed/com.not.installed.Main}"
        val bindings = ImportBinder.bind(
            manifestEntries = listOf(
                IconPackManifest.Entry(
                    "ComponentInfo{com.tencent.mm/com.tencent.mm.plugin.alias.AliasActivity}",
                    "ic_0001",
                ),
                IconPackManifest.Entry(presetComponent, "ic_0002"),
            ),
            icons = listOf(icon(0, "ic_0001.png"), icon(1, "ic_0002.png")),
            apps = apps,
        )
        assertEquals(2, bindings.size)

        val installed = bindings.single { it.component == wechatComponent }
        assertTrue(installed.installed)
        assertEquals(0, installed.iconIndex)

        val preset = bindings.single { it.component == presetComponent }
        assertFalse(preset.installed)
        assertEquals(1, preset.iconIndex)
    }

    /** 一台已安装应用都还没有的设备上，L1 依旧能靠 appfilter 建好整套预置映射 */
    @Test
    fun `manifest entries bind even when no app is installed`() {
        val bindings = ImportBinder.bind(
            manifestEntries = listOf(
                IconPackManifest.Entry("ComponentInfo{com.tencent.mm/.Main}", "wechat"),
            ),
            icons = listOf(icon(0, "wechat.png")),
            apps = emptyList(),
        )
        assertEquals(1, bindings.size)
        assertFalse(bindings.single().installed)
    }

    /**
     * L2 仍然要求应用已安装。
     *
     * component 必须是 `包名/活动类`（Lawnchair 与 Kvaesitso 规范皆如此），
     * 未安装时无从得知类名——凭空编一个只会在产物里留下永远匹配不上的死条目。
     * 这类图不会丢：它们仍在图标池里，应用装上后由「一键自动匹配」接手。
     */
    @Test
    fun `package-name matching still requires the app to be installed`() {
        val bindings = ImportBinder.bind(
            manifestEntries = emptyList(),
            icons = listOf(icon(0, "com.not.installed.png")),
            apps = apps,
        )
        assertTrue(bindings.isEmpty())
    }

    @Test
    fun `entries whose drawable has no matching file are skipped`() {
        val bindings = ImportBinder.bind(
            manifestEntries = listOf(IconPackManifest.Entry(wechatComponent, "nonexistent")),
            icons = listOf(icon(0, "wechat.png")),
            apps = apps,
        )
        assertTrue(bindings.isEmpty())
    }

    @Test
    fun `one icon may serve several components`() {
        val bindings = ImportBinder.bind(
            manifestEntries = listOf(
                IconPackManifest.Entry("ComponentInfo{com.tencent.mm/.Main}", "shared"),
                IconPackManifest.Entry("ComponentInfo{com.android.chrome/.Main}", "shared"),
            ),
            icons = listOf(icon(0, "shared.png")),
            apps = apps,
        )
        assertEquals(2, bindings.size)
        assertEquals(setOf(0), bindings.map { it.iconIndex }.toSet())
    }

    // ---- L2：名字等于包名（确定性） ----

    @Test
    fun `file name equal to a package name binds without guessing`() {
        val bindings = ImportBinder.bind(
            manifestEntries = emptyList(),
            icons = listOf(icon(0, "com.android.chrome.png")),
            apps = apps,
        )
        assertEquals(1, bindings.size)
        assertEquals(chromeComponent, bindings.single().component)
        assertEquals(ImportBinder.Source.PACKAGE_NAME, bindings.single().source)
    }

    @Test
    fun `underscore-separated and directory-derived keys both bind`() {
        val bindings = ImportBinder.bind(
            manifestEntries = emptyList(),
            icons = listOf(
                icon(0, "com_android_chrome.png"),
                icon(1, "icon.png", matchKey = "com.tencent.mm"),
            ),
            apps = apps,
        )
        assertEquals(2, bindings.size)
        assertEquals(
            setOf(0, 1),
            bindings.map { it.iconIndex }.toSet(),
        )
    }

    @Test
    fun `unrelated names bind nothing`() {
        val bindings = ImportBinder.bind(
            manifestEntries = emptyList(),
            icons = listOf(icon(0, "IMG_20241015.jpg"), icon(1, "ic_0001.png")),
            apps = apps,
        )
        assertTrue(bindings.isEmpty())
    }

    // ---- 优先级与安全约束 ----

    @Test
    fun `manifest wins over package-name matching for the same component`() {
        val bindings = ImportBinder.bind(
            manifestEntries = listOf(IconPackManifest.Entry(wechatComponent, "com.android.chrome")),
            icons = listOf(icon(0, "com.android.chrome.png")),
            apps = apps,
        )
        val wechat = bindings.single { it.component == wechatComponent }
        assertEquals(ImportBinder.Source.MANIFEST, wechat.source)
        // 同一张图仍可被包名匹配到 chrome（1:N 是 FR-04 明确支持的）
        assertEquals(2, bindings.size)
        assertTrue(bindings.any { it.component == chromeComponent })
    }

    @Test
    fun `manual mappings are never overwritten`() {
        val bindings = ImportBinder.bind(
            manifestEntries = listOf(
                IconPackManifest.Entry("ComponentInfo{com.tencent.mm/.Main}", "wechat"),
            ),
            icons = listOf(icon(0, "wechat.png"), icon(1, "com.android.chrome.png")),
            apps = apps,
            skipComponents = setOf(wechatComponent, chromeComponent),
        )
        assertTrue(bindings.isEmpty())
    }

    @Test
    fun `empty inputs yield nothing`() {
        assertTrue(ImportBinder.bind(emptyList(), emptyList(), apps).isEmpty())
        assertTrue(ImportBinder.bind(emptyList(), listOf(icon(0, "a.png")), emptyList()).isEmpty())
    }
}
