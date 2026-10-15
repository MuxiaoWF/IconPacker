package com.muxiao.iconpacker.core.packer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * appfilter 解析的回归防线。
 *
 * 二进制分支刻意复用 `AxmlAapt2ParityTest` 用过的 aapt2 基线：
 * 第三方图标包（以及本产品自己的产物）里的 appfilter 就是 aapt2 编译出来的二进制 AXML，
 * 用真实产物当基线才不至于"自产自销"——本工程已因自造夹具漏检两次。
 */
class IconPackManifestTest {

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/axml/$name")?.use { it.readBytes() }
            ?: error("缺少基线文件 src/test/resources/axml/$name")

    private val plainAppFilter = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <item component="ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}" drawable="chrome"/>
            <item component="com.tencent.mm" drawable="wechat"/>
            <item drawable="only-drawable-is-ignored"/>
            <version>1</version>
        </resources>
    """.trimIndent()

    // ---- 格式识别 ----

    @Test
    fun `binary axml is detected by the root chunk type`() {
        assertTrue(IconPackManifest.isBinaryAxml(resource("appfilter.aapt2.xml")))
        assertFalse(IconPackManifest.isBinaryAxml(plainAppFilter.toByteArray()))
        assertFalse(IconPackManifest.isBinaryAxml(ByteArray(0)))
    }

    // ---- 二进制 AXML 分支（aapt2 产物） ----

    @Test
    fun `aapt2-compiled appfilter is parsed`() {
        val parsed = IconPackManifest.parse(resource("appfilter.aapt2.xml"))
        assertEquals(IconPackManifest.Format.BINARY_AXML, parsed.format)
        assertEquals(2, parsed.entries.size)
        assertEquals("ComponentInfo{a/.B}", parsed.entries[0].component)
        assertEquals("ic_0001", parsed.entries[0].drawable)
        assertEquals("ComponentInfo{c/.D}", parsed.entries[1].component)
        assertEquals("ic_0002", parsed.entries[1].drawable)
    }

    @Test
    fun `utf8 multibyte appfilter is parsed without mangling`() {
        val parsed = IconPackManifest.parse(resource("utf8-multibyte.aapt2.xml"))
        val entry = parsed.entries.single()
        assertEquals("图标_1", entry.drawable)
        // 中文不能当包名——否则中文应用名会被误识别为包名
        assertNull(IconPackManifest.packageOfComponent(entry.component))
    }

    // ---- 明文 XML 分支 ----

    @Test
    fun `plain xml appfilter is parsed and valueless items are ignored`() {
        val parsed = IconPackManifest.parse(plainAppFilter.toByteArray())
        assertEquals(IconPackManifest.Format.PLAIN_XML, parsed.format)
        assertEquals(
            listOf("chrome", "wechat"),
            parsed.entries.map { it.drawable },
        )
    }

    @Test
    fun `garbage input throws instead of silently yielding nothing`() {
        try {
            IconPackManifest.parse("definitely not xml".toByteArray())
            fail("应当抛出异常，由调用方 runCatching 兜底")
        } catch (expected: Exception) {
            assertTrue(expected is java.io.IOException || expected is org.xml.sax.SAXException)
        }
    }

    // ---- 包名提取 ----

    @Test
    fun `package name is extracted from every common component spelling`() {
        assertEquals(
            "com.android.chrome",
            IconPackManifest.packageOfComponent(
                "ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}",
            ),
        )
        assertEquals("com.tencent.mm", IconPackManifest.packageOfComponent("ComponentInfo{com.tencent.mm}"))
        assertEquals("com.tencent.mm", IconPackManifest.packageOfComponent("com.tencent.mm"))
        assertEquals("com.tencent.mm", IconPackManifest.packageOfComponent("com.tencent.mm/.MainActivity"))
        assertEquals(
            "com.tencent.mm",
            IconPackManifest.packageOfComponent("{com.tencent.mm/com.tencent.mm.ui.LauncherUI}"),
        )
    }

    @Test
    fun `non package values yield null`() {
        assertNull(IconPackManifest.packageOfComponent(""))
        assertNull(IconPackManifest.packageOfComponent("图标_1"))
        assertNull(IconPackManifest.packageOfComponent("NotAPackage"))
        assertNull(IconPackManifest.packageOfComponent("1.invalid.leading.digit"))
    }
}
