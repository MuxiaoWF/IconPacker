package com.muxiao.iconpacker.core.packer.axml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AxmlPatcher] 测试：以 [AxmlWriter] 构造"模板清单"，
 * 验证 label 替换与 versionCode 改写的正确性与破坏隔离。
 */
class AxmlPatcherTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** 模拟 aapt2 编译出的模板清单：application 带原始字符串 label，manifest 带整数 versionCode */
    private fun templateManifest(): ByteArray {
        val doc = AxmlElement(
            name = "manifest",
            attributes = listOf(
                AxmlAttr("package", AttrValue.Str("com.muxiao.icon")),
                AxmlAttr("versionCode", AttrValue.IntVal(1), nsUri = androidNs),
                AxmlAttr("versionName", AttrValue.Str("1.0"), nsUri = androidNs),
            ),
            children = listOf(
                AxmlElement(
                    name = "application",
                    attributes = listOf(
                        AxmlAttr("label", AttrValue.Str("My Icon Pack"), nsUri = androidNs),
                        AxmlAttr("icon", AttrValue.Ref(0x7F080001, "@mipmap/ic_launcher"), nsUri = androidNs),
                    ),
                    children = listOf(
                        AxmlElement(
                            name = "activity",
                            attributes = listOf(
                                AxmlAttr("name", AttrValue.Str(".MainActivity"), nsUri = androidNs),
                                AxmlAttr("exported", AttrValue.Bool(true), nsUri = androidNs),
                            ),
                        ),
                    ),
                ),
            ),
        )
        return AxmlWriter(
            namespaces = listOf(AxmlWriter.Namespace("android", androidNs)),
        ).write(doc)
    }

    @Test
    fun `replace label keeps everything else intact`() {
        val manifest = templateManifest()
        val patched = AxmlPatcher.replaceLabel(manifest, "我的蓝色图标包")
        val reader = AxmlReader(patched)

        val app = reader.root.children.single { it.name == "application" }
        val label = app.attributes.first { it.name == "label" }
        assertEquals("我的蓝色图标包", label.rawValue)
        assertEquals(AxmlValueType.STRING, label.dataType)

        // 其余内容不受影响
        val vc = reader.root.attributes.first { it.name == "versionCode" }
        assertEquals(1, vc.data)
        assertEquals("com.muxiao.icon", reader.root.attributes.first { it.name == "package" }.rawValue)
        val activity = app.children.single()
        assertEquals(".MainActivity", activity.attributes.first { it.name == "name" }.rawValue)
        assertEquals(-1, activity.attributes.first { it.name == "exported" }.data)
    }

    @Test
    fun `label replacement is byte stable and idempotent`() {
        val manifest = templateManifest()
        val once = AxmlPatcher.replaceLabel(manifest, "Pack A")
        val twice = AxmlPatcher.replaceLabel(once, "Pack B")
        // 二次替换后只剩 Pack B，无残留
        val reader = AxmlReader(twice)
        val label = reader.root.children.single().attributes.first { it.name == "label" }
        assertEquals("Pack B", label.rawValue)
        // 原地替换，无残留
        assertEquals(0, reader.strings.count { it == "Pack A" })
        assertEquals(0, reader.strings.count { it == "My Icon Pack" })
    }

    @Test
    fun `version code patch is in place`() {
        val manifest = templateManifest()
        val before = AxmlReader(manifest)
        val patched = AxmlPatcher.setVersionCode(manifest, 7)
        // 原地改写：总长度不变
        assertEquals(manifest.size, patched.size)
        val after = AxmlReader(patched)
        assertEquals(7, after.root.attributes.first { it.name == "versionCode" }.data)
        assertEquals(before.strings, after.strings) // 字符串池完全不变
        assertEquals(
            before.root.attributes.first { it.name == "versionName" }.rawValue,
            after.root.attributes.first { it.name == "versionName" }.rawValue,
        )
    }

    @Test
    fun `label patch followed by version patch composes`() {
        var manifest = templateManifest()
        manifest = AxmlPatcher.replaceLabel(manifest, "Composed Pack")
        manifest = AxmlPatcher.setVersionCode(manifest, 100)
        val reader = AxmlReader(manifest)
        assertEquals(
            "Composed Pack",
            reader.root.children.single().attributes.first { it.name == "label" }.rawValue,
        )
        assertEquals(100, reader.root.attributes.first { it.name == "versionCode" }.data)
    }

    @Test(expected = IllegalStateException::class)
    fun `replace non string attribute fails loudly`() {
        val manifest = templateManifest()
        // versionCode 是整数属性，按字符串替换必须报错而非静默损坏
        AxmlPatcher.replaceStringAttribute(manifest, "manifest", "versionCode", "x")
    }

    @Test
    fun `missing attribute fails loudly`() {
        val manifest = templateManifest()
        val ex = runCatching {
            AxmlPatcher.replaceStringAttribute(manifest, "application", "notExist", "x")
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
    }

    @Test
    fun `patched manifest re readable by reader`() {
        val manifest = AxmlPatcher.replaceLabel(templateManifest(), "Round Trip")
        val reader = AxmlReader(manifest)
        assertEquals("Round Trip", reader.root.children.single().attributes.first { it.name == "label" }.rawValue)
        assertNull(reader.root.attributes.first { it.name == "versionCode" }.rawValue)
    }
}
