package com.muxiao.iconpacker.domain

import com.muxiao.iconpacker.core.packer.axml.AxmlAttr
import com.muxiao.iconpacker.core.packer.axml.AxmlElement
import com.muxiao.iconpacker.core.packer.axml.AxmlWriter
import com.muxiao.iconpacker.core.packer.axml.AttrValue
import com.muxiao.iconpacker.core.packer.engine.IconPackManifest
import com.muxiao.iconpacker.core.packer.engine.ZipIconPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "导入即建立映射"的端到端链路（去掉 Android 的落盘与数据库层）。
 *
 * 走完整条链：ZIP 条目清单 → 去重与匹配键推导 → 解析包内 appfilter → 两层绑定，
 * 断言最终得到的 `组件 → 图标` 对应关系。
 *
 * appfilter 用**本产品自己的编码器**生成二进制 AXML——与真机导入时读到的格式一致
 * （第三方图标包的 appfilter 由 aapt2 产出，格式已由 `IconPackManifestTest`
 * 用真实 aapt2 基线单独覆盖）。
 */
class ImportPipelineTest {

    private val wechatComponent = "ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}"
    private val chromeComponent = "ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}"

    /** 一个标准图标包：同一批图标在多密度目录各有一份，外加一份 appfilter */
    private val zipEntries = buildList {
        for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            add(ZipIconPlanner.Entry("res/drawable-$density/wechat.png", 1000))
            add(ZipIconPlanner.Entry("res/drawable-$density/chrome.png", 1000))
        }
    }

    private fun appFilterBytes(): ByteArray = AxmlWriter().write(
        AxmlElement(
            name = "resources",
            children = listOf(
                AxmlElement(
                    name = "item",
                    attributes = listOf(
                        AxmlAttr("component", AttrValue.Str(wechatComponent)),
                        AxmlAttr("drawable", AttrValue.Str("wechat")),
                    ),
                ),
                AxmlElement(
                    name = "item",
                    attributes = listOf(
                        AxmlAttr("component", AttrValue.Str(chromeComponent)),
                        AxmlAttr("drawable", AttrValue.Str("chrome")),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a standard icon pack yields deduped icons and authoritative mappings`() {
        // 1) 规划：10 个图片条目（2 图标 × 5 密度）应合并为 2 张
        val plan = ZipIconPlanner.plan(zipEntries)
        assertEquals(2, plan.kept.size)
        assertEquals(8, plan.duplicates)
        assertEquals(listOf("chrome.png", "wechat.png"), plan.kept.map { it.fileName })
        assertEquals(
            listOf("res/drawable-xxxhdpi/chrome.png", "res/drawable-xxxhdpi/wechat.png"),
            plan.kept.map { it.path },
        )

        // 2) 解析包内 appfilter
        val parsed = IconPackManifest.parse(appFilterBytes())
        assertEquals(IconPackManifest.Format.BINARY_AXML, parsed.format)
        assertEquals(2, parsed.entries.size)

        // 3) 两层绑定：两张图各自对上自己的应用，不串位
        val apps = listOf(
            ImportBinder.AppItem(wechatComponent, "com.tencent.mm"),
            ImportBinder.AppItem(chromeComponent, "com.android.chrome"),
        )
        val bindings = ImportBinder.bind(
            manifestEntries = parsed.entries,
            icons = plan.kept.mapIndexed { index, planned ->
                ImportBinder.IconItem(index, planned.fileName, planned.matchKey)
            },
            apps = apps,
        )

        assertEquals(2, bindings.size)
        assertTrue(bindings.all { it.source == ImportBinder.Source.MANIFEST })
        val iconIndexByComponent = bindings.associate { it.component to it.iconIndex }
        val wechatIcon = plan.kept.indexOfFirst { it.fileName == "wechat.png" }
        val chromeIcon = plan.kept.indexOfFirst { it.fileName == "chrome.png" }
        assertEquals(wechatIcon, iconIndexByComponent[wechatComponent])
        assertEquals(chromeIcon, iconIndexByComponent[chromeComponent])
        assertTrue(wechatIcon != chromeIcon)
    }

    /**
     * 同一批图标但**没有** appfilter（用户自己收集的 ZIP）：退回 L2 包名匹配。
     * 文件名若恰好是包名，仍然零猜测地完成绑定。
     */
    @Test
    fun `without appfilter the package-name layer still binds`() {
        val plan = ZipIconPlanner.plan(
            listOf(
                ZipIconPlanner.Entry("com.tencent.mm/icon.png", 1000),
                ZipIconPlanner.Entry("com.android.chrome.png", 1000),
            ),
        )
        val bindings = ImportBinder.bind(
            manifestEntries = emptyList(),
            icons = plan.kept.mapIndexed { index, planned ->
                ImportBinder.IconItem(index, planned.fileName, planned.matchKey)
            },
            apps = listOf(
                ImportBinder.AppItem(wechatComponent, "com.tencent.mm"),
                ImportBinder.AppItem(chromeComponent, "com.android.chrome"),
            ),
        )
        assertEquals(2, bindings.size)
        assertTrue(bindings.all { it.source == ImportBinder.Source.PACKAGE_NAME })
    }

    /**
     * 无语义序号命名（本产品自己产物的形态）：两层都匹配不到，
     * 结果必须是"一个映射都不建"而不是乱配——乱配比不配更难排查。
     */
    @Test
    fun `meaningless sequential names bind nothing`() {
        val plan = ZipIconPlanner.plan(
            listOf("ic_0001", "ic_0002", "ic_0003").map {
                ZipIconPlanner.Entry("res/drawable-xxxhdpi-v4/$it.png", 1000)
            },
        )
        assertEquals(3, plan.kept.size)
        assertEquals(listOf("ic_0001", "ic_0002", "ic_0003"), plan.kept.map { it.matchKey })

        val bindings = ImportBinder.bind(
            manifestEntries = emptyList(),
            icons = plan.kept.mapIndexed { index, planned ->
                ImportBinder.IconItem(index, planned.fileName, planned.matchKey)
            },
            apps = listOf(ImportBinder.AppItem(wechatComponent, "com.tencent.mm")),
        )
        assertTrue(bindings.isEmpty())
    }
}
