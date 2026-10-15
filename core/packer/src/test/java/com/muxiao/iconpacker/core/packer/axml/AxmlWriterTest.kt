package com.muxiao.iconpacker.core.packer.axml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AxmlWriter] 对拍测试：写入 → [AxmlReader] 回读 → 断言语义等价，
 * 并对关键字节布局做金丝雀断言，防止格式回归。
 *
 * 注意：回读器与写入器同源，本测试保证的是内部一致性；
 * 与 aapt2 产物的字节级对拍在 Sprint 0 出口执行（见任务 T2/T5）。
 */
class AxmlWriterTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun appfilterDoc(items: List<Pair<String, String>>): AxmlElement =
        AxmlElement(
            name = "resources",
            children = items.map { (component, drawable) ->
                AxmlElement(
                    name = "item",
                    attributes = listOf(
                        AxmlAttr("component", AttrValue.Str(component)),
                        AxmlAttr("drawable", AttrValue.Str(drawable)),
                    ),
                )
            },
        )

    @Test
    fun `appfilter round trip preserves structure`() {
        val doc = appfilterDoc(
            listOf(
                "ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}" to "ic_0001",
                "ComponentInfo{com.android.settings/.Settings}" to "ic_0002",
            ),
        )
        val bytes = AxmlWriter().write(doc)
        val reader = AxmlReader(bytes)

        assertEquals("resources", reader.root.name)
        assertNull(reader.root.nsUri)
        assertEquals(2, reader.root.children.size)

        val first = reader.root.children[0]
        assertEquals("item", first.name)
        assertEquals(2, first.attributes.size)

        val component = first.attributes[0]
        assertEquals("component", component.name)
        assertEquals(
            "ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}",
            component.rawValue,
        )
        assertEquals(AxmlValueType.STRING, component.dataType)
        // STRING 属性的 data 与 rawValue 指向同一字符串池索引
        assertEquals(reader.strings[component.data], component.rawValue)

        val second = reader.root.children[1]
        assertEquals("ic_0002", second.attributes[1].rawValue)
    }

    @Test
    fun `namespaced document keeps namespace uris`() {
        val doc = AxmlElement(
            name = "manifest",
            nsUri = androidNs,
            attributes = listOf(
                AxmlAttr("versionCode", AttrValue.IntVal(42), nsUri = androidNs),
                AxmlAttr("versionName", AttrValue.Str("1.0"), nsUri = androidNs),
            ),
            children = listOf(
                AxmlElement(
                    name = "application",
                    nsUri = androidNs,
                    attributes = listOf(
                        AxmlAttr("label", AttrValue.Str("My Icon Pack"), nsUri = androidNs),
                        AxmlAttr("icon", AttrValue.Ref(0x7F080001, "@mipmap/ic_launcher"), nsUri = androidNs),
                    ),
                ),
            ),
        )
        val bytes = AxmlWriter(
            namespaces = listOf(AxmlWriter.Namespace("android", androidNs)),
        ).write(doc)
        val reader = AxmlReader(bytes)

        assertEquals(androidNs, reader.root.nsUri)
        val vc = reader.root.attributes.first { it.name == "versionCode" }
        assertEquals(AxmlValueType.INT_DEC, vc.dataType)
        assertEquals(42, vc.data)
        assertNull(vc.rawValue)

        val app = reader.root.children.single()
        assertEquals("application", app.name)
        assertEquals(androidNs, app.nsUri)
        val label = app.attributes.first { it.name == "label" }
        assertEquals("My Icon Pack", label.rawValue)
        val icon = app.attributes.first { it.name == "icon" }
        assertEquals(AxmlValueType.REFERENCE, icon.dataType)
        assertEquals(0x7F080001, icon.data)
        assertEquals("@mipmap/ic_launcher", icon.rawValue)
    }

    @Test
    fun `string pool dedups repeated strings`() {
        val doc = appfilterDoc(
            List(50) { "ComponentInfo{com.example/.Main}" to "ic_0001" },
        )
        val bytes = AxmlWriter().write(doc)
        val reader = AxmlReader(bytes)
        // "resources"/"item"/"component"/"drawable"/1 个 component 值/1 个 drawable 值
        assertEquals(6, reader.strings.size)
        assertEquals(50, reader.root.children.size)
    }

    @Test
    fun `large document round trips`() {
        val items = (1..1000).map { "ComponentInfo{com.example.app$it/.Main}" to "ic_${it.toString().padStart(4, '0')}" }
        val bytes = AxmlWriter().write(appfilterDoc(items))
        val reader = AxmlReader(bytes)
        assertEquals(1000, reader.root.children.size)
        assertEquals("ic_1000", reader.root.children.last().attributes[1].rawValue)
    }

    @Test
    fun `byte layout matches axml spec`() {
        val doc = appfilterDoc(listOf("ComponentInfo{a/.B}" to "ic_0001"))
        val bytes = AxmlWriter().write(doc)

        // 文件头：type=0x0003, headerSize=8, size=总长
        assertEquals(0x0003, AxmlReader.readU16(bytes, 0))
        assertEquals(8, AxmlReader.readU16(bytes, 2))
        assertEquals(bytes.size, AxmlReader.readU32(bytes, 4))

        // 字符串池 chunk：type=0x0001, headerSize=28
        assertEquals(0x0001, AxmlReader.readU16(bytes, 8))
        assertEquals(28, AxmlReader.readU16(bytes, 10))

        // 跳过字符串池 chunk，再跳过空资源表 chunk（type 0x0180，size 8），
        // 其后第一个 start element 是根元素 <resources>（0 属性，chunk 尺寸恒为 36）；
        // 再跳过它才是带 2 属性的 <item>。
        var off = 8 + AxmlReader.readU32(bytes, 12)        // 越过字符串池
        assertEquals(0x0180, AxmlReader.readU16(bytes, off)) // 空资源表
        off += AxmlReader.readU32(bytes, off + 4)           // 越过资源表
        assertEquals(0x0102, AxmlReader.readU16(bytes, off)) // 根 <resources>
        off += AxmlReader.readU32(bytes, off + 4)

        val startType = AxmlReader.readU16(bytes, off)
        assertEquals(0x0102, startType)
        assertEquals(16, AxmlReader.readU16(bytes, off + 2))   // headerSize 必须为 16
        assertEquals(36 + 20 * 2, AxmlReader.readU32(bytes, off + 4))
        assertEquals(20, AxmlReader.readU16(bytes, off + 24)) // attributeStart 必须为 20（基准是 attrExt 起点）
        assertEquals(20, AxmlReader.readU16(bytes, off + 26)) // attributeSize
        assertEquals(2, AxmlReader.readU16(bytes, off + 28))   // attributeCount
        assertEquals(0xFFFF, AxmlReader.readU16(bytes, off + 30)) // idIndex
        assertEquals(0xFFFF, AxmlReader.readU16(bytes, off + 32)) // classIndex
        assertEquals(0xFFFF, AxmlReader.readU16(bytes, off + 34)) // styleIndex

        // start element 尺寸自洽：chunk 尺寸 = 36 + 20*attrCount
        val chunkSize = AxmlReader.readU32(bytes, off + 4)
        assertTrue(chunkSize == 36 + 40)
    }

    @Test
    fun `bool value encodes android convention`() {
        val doc = AxmlElement(
            name = "activity",
            attributes = listOf(AxmlAttr("exported", AttrValue.Bool(true))),
        )
        val reader = AxmlReader(AxmlWriter().write(doc))
        val exported = reader.root.attributes.single()
        assertEquals(AxmlValueType.INT_BOOLEAN, exported.dataType)
        assertEquals(-1, exported.data) // 0xFFFFFFFF = true
    }
}
