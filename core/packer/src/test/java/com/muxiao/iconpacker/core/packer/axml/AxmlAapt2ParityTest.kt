package com.muxiao.iconpacker.core.packer.axml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 以真实 aapt2 产物为基线的结构对拍测试。
 *
 * 为什么需要它：[AxmlReader] 是**自洽性**验证器——读写两端共用同一套偏移约定，
 * 写入端整体偏了它也跟着偏，往返测试依然全绿。`attributeStart` 被写成 36 的 bug
 * 就是这样逃逸的：本地单测通过，装到设备上却被框架判为
 * `Bad XML block: node attributes use 0x24 bytes, only have 0x14 bytes`，
 * appfilter.xml / drawable.xml 读不出来 → 图标包可选中但图标不切换。
 *
 * 因此这里引入一个**外部基线** `src/test/resources/axml/appfilter.aapt2.xml`，
 * 内容与本测试构造的文档完全一致，由本机 aapt2 34.0.0 编译得到：
 * ```
 * aapt2 compile --dir res -o compiled.zip
 * aapt2 link -o fixture.apk -I <android-34/android.jar> \
 *     --manifest AndroidManifest.xml compiled.zip
 * # 取出 fixture.apk 中的 res/xml/appfilter.xml
 * ```
 *
 * 字符串池不参与逐字节比对：aapt2 输出 UTF-8 池，本实现输出 UTF-16LE 池，
 * 因此只比 chunk 的 type / headerSize；元素 chunk 的 size 与属性布局字段
 * 只取决于属性个数与固定结构，与池编码无关，必须逐项相等。
 */
class AxmlAapt2ParityTest {

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/axml/$name")?.use { it.readBytes() }
            ?: error("缺少 aapt2 基线文件 src/test/resources/axml/$name")

    /** aapt2 基线（只读对照，不参与构建） */
    private fun baseline(): ByteArray = resource("appfilter.aapt2.xml")

    /** 与基线等价的文档，由被测写入端生成 */
    private fun writerOutput(): ByteArray = AxmlWriter().write(
        AxmlElement(
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
        ),
    )

    private data class Chunk(
        val type: Int,
        val headerSize: Int,
        val size: Int,
        val attrStart: Int = -1,
        val attrSize: Int = -1,
        val attrCount: Int = -1,
    ) {
        val isStart: Boolean get() = type == AxmlWriter.RES_XML_START_ELEMENT_TYPE
        val isEnd: Boolean get() = type == AxmlWriter.RES_XML_END_ELEMENT_TYPE
        /** 元素 chunk 的尺寸与字符串池编码无关，可直接比对 */
        val isElement: Boolean get() = isStart || isEnd
    }

    private fun walk(bytes: ByteArray): List<Chunk> {
        assertEquals(AxmlWriter.RES_XML_TYPE, AxmlReader.readU16(bytes, 0))
        val total = AxmlReader.readU32(bytes, 4)
        val out = ArrayList<Chunk>()
        var off = 8
        while (off + 8 <= total) {
            val type = AxmlReader.readU16(bytes, off)
            val headerSize = AxmlReader.readU16(bytes, off + 2)
            val size = AxmlReader.readU32(bytes, off + 4)
            assertTrue("chunk size 非法：type=0x${type.toString(16)} size=$size at $off", size >= 8)
            out += if (type == AxmlWriter.RES_XML_START_ELEMENT_TYPE) {
                Chunk(
                    type = type,
                    headerSize = headerSize,
                    size = size,
                    attrStart = AxmlReader.readU16(bytes, off + 24),
                    attrSize = AxmlReader.readU16(bytes, off + 26),
                    attrCount = AxmlReader.readU16(bytes, off + 28),
                )
            } else {
                Chunk(type, headerSize, size)
            }
            off += size
        }
        assertEquals("XML 根声明尺寸与实际不符", total, off)
        return out
    }

    /** 递归展开元素树，用于语义级对拍 */
    private fun shape(element: AxmlReader.Element): String = buildString {
        append(element.name)
        append('(')
        append(element.attributes.joinToString(",") { "${it.name}=${it.rawValue}" })
        append(')')
        if (element.children.isNotEmpty()) {
            append(element.children.joinToString(",", "[", "]") { shape(it) })
        }
    }

    @Test
    fun `chunk sequence and headers match aapt2`() {
        val base = walk(baseline())
        val mine = walk(writerOutput())
        assertEquals("chunk 数量不一致", base.size, mine.size)
        base.indices.forEach { i ->
            assertEquals("chunk[$i] type 不一致", base[i].type, mine[i].type)
            // 字符串池 chunk 的 size 因池编码不同而不同，只比 headerSize
            assertEquals("chunk[$i] headerSize 不一致", base[i].headerSize, mine[i].headerSize)
        }
        assertEquals(
            "元素 chunk 序列（type,size）不一致",
            base.filter { it.isElement }.map { it.type to it.size },
            mine.filter { it.isElement }.map { it.type to it.size },
        )
    }

    @Test
    fun `start element layout fields match aapt2`() {
        val base = walk(baseline()).first { it.isStart && it.attrCount == 2 }
        val mine = walk(writerOutput()).first { it.isStart && it.attrCount == 2 }

        // 先把基线事实钉死：这就是本次 bug 的判据
        assertEquals(16, base.headerSize)
        assertEquals(AxmlWriter.ATTR_EXT_SIZE, base.attrStart)
        assertEquals(20, base.attrStart)
        assertEquals(20, base.attrSize)

        // 写入端必须与之一致
        assertEquals(base.headerSize, mine.headerSize)
        assertEquals(base.attrStart, mine.attrStart)
        assertEquals(base.attrSize, mine.attrSize)
        assertEquals(base.attrCount, mine.attrCount)
        assertEquals(base.size, mine.size)
    }

    @Test
    fun `end element layout matches aapt2`() {
        val base = walk(baseline()).first { it.isEnd }
        val mine = walk(writerOutput()).first { it.isEnd }
        assertEquals(base, mine)
        assertEquals(16, base.headerSize)
        assertEquals(24, base.size)
    }

    @Test
    fun `reader parses the aapt2 baseline`() {
        val root = AxmlReader(baseline()).root
        assertEquals("resources", root.name)
        assertEquals(2, root.children.size)
        val first = root.children[0]
        assertEquals("item", first.name)
        assertEquals(listOf("component", "drawable"), first.attributes.map { it.name })
        assertEquals("ComponentInfo{a/.B}", first.attributes[0].rawValue)
        assertEquals("ic_0001", first.attributes[1].rawValue)
    }

    @Test
    fun `reader yields identical trees for baseline and writer output`() {
        assertEquals(shape(AxmlReader(baseline()).root), shape(AxmlReader(writerOutput()).root))
    }

    /**
     * UTF-8 池的两个长度前缀必须都被正确消费。
     *
     * 基线里 "ComponentInfo{中文.应用/.主活动}" 的 UTF-16 字符数是 25、UTF-8 字节数是 39 ——
     * 两者不同。若实现只跳过一个前缀、或用了第一个前缀当数据长度，这里必然读出乱码或截断；
     * 纯 ASCII 基线（两个长度相等）反而检不出这种错法，故单独用这份含中文的产物锁住。
     */
    @Test
    fun `utf8 pool with multibyte characters uses the second length prefix`() {
        val root = AxmlReader(resource("utf8-multibyte.aapt2.xml")).root
        assertEquals("resources", root.name)
        val item = root.children.single()
        assertEquals("item", item.name)
        assertEquals("ComponentInfo{中文.应用/.主活动}", item.attributes[0].rawValue)
        assertEquals("图标_1", item.attributes[1].rawValue)
    }
}
