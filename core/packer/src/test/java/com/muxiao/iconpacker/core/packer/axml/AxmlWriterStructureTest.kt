package com.muxiao.iconpacker.core.packer.axml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 [AxmlWriter] 产出的二进制 AXML 与 aapt2 编译结构一致的关键字段。
 *
 * 历史教训（改动前务必读完）：
 * 1. 最初 headerSize 写成 36、attributeStart 写成 20。headerSize 错会让框架定位 ns/name
 *    越界，桌面报 "Corrupt XML binary file" 并闪退；
 * 2. 修复时把两个字段**一起**改成了 headerSize=16、attributeStart=36，还把 36 写成断言
 *    固化在本文件里。事实上 attributeStart 的基准是 attrExt 起点（chunk 起点 + headerSize），
 *    正确值一直是 20；36 会让解析器把属性数组起点算到 chunk 起点 + 52 而越界，
 *    appfilter.xml / drawable.xml 被判 corrupt → 图标包可选中但图标不切换。
 * 3. 漏检原因：[AxmlReader] 硬编码了与写入端相同的偏移，往返测试自洽即可通过。
 *
 * 因此本文件只负责锁住"字段不许再被改错"，至于"约定本身是否符合 AOSP"，
 * 由 [AxmlAapt2ParityTest] 以真实 aapt2 产物为基线兜底。
 */
class AxmlWriterStructureTest {

    private fun appfilterWithAttributes(): ByteArray {
        val doc = AxmlElement(
            name = "resources",
            children = listOf(
                AxmlElement(
                    name = "item",
                    attributes = listOf(
                        AxmlAttr("component", AttrValue.Str("ComponentInfo{a/.B}")),
                        AxmlAttr("drawable", AttrValue.Str("ic_0001")),
                    ),
                ),
                AxmlElement(
                    name = "item",
                    attributes = listOf(
                        AxmlAttr("component", AttrValue.Str("ComponentInfo{c/.D}")),
                        AxmlAttr("drawable", AttrValue.Str("ic_0002")),
                    ),
                ),
            ),
        )
        return AxmlWriter().write(doc)
    }

    /** 逐 chunk 遍历，返回按出现顺序的 (type, headerSize, size, offset) 列表（仅顶层 XML 内） */
    private fun walkChunks(bytes: ByteArray): List<Chunk> {
        val type = AxmlReader.readU16(bytes, 0)
        assertEquals(AxmlWriter.RES_XML_TYPE, type)
        val total = AxmlReader.readU32(bytes, 4)
        val out = ArrayList<Chunk>()
        var off = 8
        while (off + 8 <= total) {
            val cType = AxmlReader.readU16(bytes, off)
            val cHsize = AxmlReader.readU16(bytes, off + 2)
            val cSize = AxmlReader.readU32(bytes, off + 4)
            assertTrue("chunk size 非法：type=0x${cType.toString(16)} size=$cSize at $off", cSize >= 8)
            out.add(Chunk(cType, cHsize, cSize, off))
            off += cSize
        }
        assertEquals("XML 根声明尺寸与实际不符", total, off)
        return out
    }

    data class Chunk(val type: Int, val headerSize: Int, val size: Int, val offset: Int)

    @Test
    fun `root and chunk ordering match aapt2`() {
        val bytes = appfilterWithAttributes()
        val chunks = walkChunks(bytes)
        // 元素在二进制 XML 中是"兄弟 chunk"而非嵌套：根 resources 包裹两个 item，
        // 顶层顺序为 字符串池 → 空资源表 → START(resources) → START(item1) →
        // END(item1) → START(item2) → END(item2) → END(resources)。
        assertEquals(
            listOf(
                StringPool.RES_STRING_POOL_TYPE,
                AxmlWriter.RES_XML_RESOURCE_MAP_TYPE,
                AxmlWriter.RES_XML_START_ELEMENT_TYPE,
                AxmlWriter.RES_XML_START_ELEMENT_TYPE,
                AxmlWriter.RES_XML_END_ELEMENT_TYPE,
                AxmlWriter.RES_XML_START_ELEMENT_TYPE,
                AxmlWriter.RES_XML_END_ELEMENT_TYPE,
                AxmlWriter.RES_XML_END_ELEMENT_TYPE,
            ),
            chunks.map { it.type },
        )
    }

    @Test
    fun `string pool chunk has correct header`() {
        val bytes = appfilterWithAttributes()
        val sp = walkChunks(bytes).first { it.type == StringPool.RES_STRING_POOL_TYPE }
        assertEquals(28, sp.headerSize) // ResStringPool_header 固定 28
    }

    @Test
    fun `resource map is emitted and empty`() {
        val bytes = appfilterWithAttributes()
        val rm = walkChunks(bytes).first { it.type == AxmlWriter.RES_XML_RESOURCE_MAP_TYPE }
        assertEquals(8, rm.size) // 仅 chunk 头，无 id 条目
    }

    @Test
    fun `start element headerSize and attributeStart match aapt2`() {
        val bytes = appfilterWithAttributes()
        // 目标是有属性的 <item> 节点（attrCount=2）；根 <resources> 自身无属性。
        val start = walkChunks(bytes).first {
            it.type == AxmlWriter.RES_XML_START_ELEMENT_TYPE &&
                AxmlReader.readU16(bytes, it.offset + 28) == 2
        }
        // 关键回归点：headerSize 必须为 16（框架据其定位 ns/name）
        assertEquals(16, start.headerSize)
        // attributeStart 的基准是 attrExt 起点，必须为 sizeof(ResXMLTree_attrExt) = 20
        val attrStart = AxmlReader.readU16(bytes, start.offset + 24)
        val attrCount = AxmlReader.readU16(bytes, start.offset + 28)
        assertEquals(AxmlWriter.ATTR_EXT_SIZE, attrStart)
        assertEquals(20, attrStart)
        assertEquals(2, attrCount)
        // size 的基准是 chunk 起点：36（固定节点） + 20 × 2（属性）
        assertEquals(AxmlWriter.ELEMENT_NODE_SIZE + AxmlWriter.ATTR_SIZE * 2, start.size)
    }

    @Test
    fun `end element headerSize matches aapt2`() {
        val bytes = appfilterWithAttributes()
        val end = walkChunks(bytes).first { it.type == AxmlWriter.RES_XML_END_ELEMENT_TYPE }
        assertEquals(16, end.headerSize)
        assertEquals(24, end.size)
    }

    @Test
    fun `attributes are readable via AxmlReader round trip`() {
        val bytes = appfilterWithAttributes()
        val reader = AxmlReader(bytes)
        assertEquals("resources", reader.root.name)
        assertEquals(2, reader.root.children.size)
        val first = reader.root.children[0]
        assertEquals("item", first.name)
        assertEquals(2, first.attributes.size)
        assertEquals("component", first.attributes[0].name)
        assertEquals("ComponentInfo{a/.B}", first.attributes[0].rawValue)
        assertEquals("drawable", first.attributes[1].name)
        assertEquals("ic_0001", first.attributes[1].rawValue)
    }
}
