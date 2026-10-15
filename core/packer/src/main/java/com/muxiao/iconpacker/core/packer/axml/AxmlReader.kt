package com.muxiao.iconpacker.core.packer.axml

import java.io.ByteArrayOutputStream

/**
 * AXML 回读器：把二进制 XML 解析为元素树。
 *
 * 两个用途：
 * 1. 单测中作为 [AxmlWriter] 的往返 oracle（写入 → 回读 → 断言）；
 * 2. 生产中被 [AxmlPatcher] 用于定位模板清单中的属性偏移。
 *
 * 注意：它是**自洽性**验证器，不是规范验证器——读写两端共用同一套偏移约定，
 * 因此写入端整体偏了它也会跟着偏（attributeStart=36 的 bug 就这样逃逸了数月）。
 * 任何涉及二进制布局的改动，都必须同时过 [AxmlAapt2ParityTest] 的 aapt2 产物对拍。
 *
 * 仅实现本产品用到的子集：文件头 + 字符串池 + 命名空间 chunk + 起止元素 chunk。
 * CDATA 文本节点在 appfilter / drawable / 清单中均不出现，未实现。
 */
class AxmlReader(data: ByteArray) {

    /** 回读出的属性 */
    data class Attr(
        val nsUri: String?,
        val name: String,
        val rawValue: String?,
        val dataType: Int,
        val data: Int,
    )

    /** 回读出的元素 */
    data class Element(
        val name: String,
        val nsUri: String?,
        val attributes: List<Attr>,
        val children: List<Element>,
    )

    /** 字符串池内容（索引即字符串池索引） */
    val strings: List<String>

    /** 资源表中的资源 ID 列表（无资源表时为空） */
    val resourceIds: List<Int>

    /** 文档根元素 */
    val root: Element

    init {
        require(data.size >= 8) { "数据过小，不是合法 AXML" }
        val type = readU16(data, 0)
        require(type == AxmlWriter.RES_XML_TYPE) { "不是 AXML 文件头：0x${type.toString(16)}" }
        val totalSize = readU32(data, 4)
        require(totalSize <= data.size) { "文件头声明尺寸 $totalSize 超过实际 ${data.size}" }

        var pool: List<String> = emptyList()
        var resourceIds: List<Int> = emptyList()
        var rootNode: MutableElement? = null
        val stack = ArrayList<MutableElement>()

        var off = 8
        while (off + 8 <= totalSize) {
            val chunkType = readU16(data, off)
            val chunkSize = readU32(data, off + 4)
            require(chunkSize >= 8 && off + chunkSize <= totalSize) {
                "非法 chunk：type=0x${chunkType.toString(16)} size=$chunkSize at $off"
            }
            when (chunkType) {
                StringPool.RES_STRING_POOL_TYPE -> pool = readStringPool(data, off)
                RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = (chunkSize - 8) / 4
                    resourceIds = (0 until count).map { readU32(data, off + 8 + 4 * it) }
                }
                AxmlWriter.RES_XML_START_ELEMENT_TYPE -> {
                    val el = readStartElement(data, off, pool)
                    // 用可变节点建树：[Element] 是不可变 data class，若在栈上做
                    // copy 传播，深层嵌套（manifest→application→activity）时子节点
                    // 更新无法一路传到根，会静默丢失整棵子树。故先建可变树再整体转换。
                    val node = MutableElement(el.name, el.nsUri, el.attributes)
                    if (stack.isEmpty()) {
                        rootNode = node
                    } else {
                        stack.last().children.add(node)
                    }
                    stack.add(node)
                }
                AxmlWriter.RES_XML_END_ELEMENT_TYPE -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                // start/end namespace chunk：命名空间 URI 已内联在属性的 ns 字段中，无需处理
                else -> Unit
            }
            off += chunkSize
        }

        this.strings = pool
        this.resourceIds = resourceIds
        this.root = requireNotNull(rootNode) { "未找到根元素" }.toImmutable()
    }

    /** 建树用的可变节点（[Element] 为不可变 data class，直接构建会丢子树） */
    private class MutableElement(
        val name: String,
        val nsUri: String?,
        val attributes: List<Attr>,
    ) {
        val children = ArrayList<MutableElement>()

        fun toImmutable(): Element =
            Element(name, nsUri, attributes, children.map { it.toImmutable() })
    }

    /**
     * 字符串池解析统一收敛到 [StringPool.decode]。
     *
     * 历史上这里和 [AxmlPatcher] 各有一份"只读一个长度前缀"的 UTF-8 实现，两者都漏了
     * AOSP 的第二个前缀（UTF-8 字节数），导致 aapt2 产物的字符串整体偏位。
     * 重复实现是这类 bug 能同时在两处存活的原因，故只保留一份。
     */
    private fun readStringPool(data: ByteArray, off: Int): List<String> = StringPool.decode(data, off)

    private fun readStartElement(data: ByteArray, off: Int, pool: List<String>): Element {
        val headerSize = readU16(data, off + 2)
        val chunkSize = readU32(data, off + 4)
        val nsIdx = readU32(data, off + 16)
        val nameIdx = readU32(data, off + 20)
        // attributeStart 的基准是 attrExt 起点（chunk 起点 + headerSize），不是 chunk 起点。
        // 这里必须按字段取值计算：硬编码 off + 36 会在写入端写错时"同步错"，
        // 让往返测试永远通过（正是 attributeStart=36 那个 bug 长期逃逸的原因）。
        val attrStart = readU16(data, off + 24)
        val attrSize = readU16(data, off + 26)
        val attrCount = readU16(data, off + 28)
        val name = pool.getOrNull(nameIdx) ?: error("元素名索引越界：$nameIdx")
        val ns = pool.getOrNull(nsIdx)
        val attrs = ArrayList<Attr>(attrCount)
        var attrOff = off + headerSize + attrStart
        require(attrSize >= 20) { "attributeSize 非法：$attrSize（至少 20）" }
        require(attrOff + attrCount * attrSize <= off + chunkSize) {
            "属性数组越界：起点 $attrOff + ${attrCount}×$attrSize 超出节点 chunk 末尾 ${off + chunkSize}"
        }
        repeat(attrCount) {
            val aNs = readU32(data, attrOff)
            val aName = readU32(data, attrOff + 4)
            val aRaw = readU32(data, attrOff + 8)
            val aType = data[attrOff + 15].toInt() and 0xFF
            val aData = readU32(data, attrOff + 16)
            attrs.add(
                Attr(
                    nsUri = pool.getOrNull(aNs),
                    name = pool.getOrNull(aName) ?: error("属性名索引越界：$aName"),
                    rawValue = pool.getOrNull(aRaw),
                    dataType = aType,
                    data = aData,
                ),
            )
            attrOff += attrSize
        }
        return Element(name, ns, attrs, emptyList())
    }

    companion object {
        const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

        fun readU16(data: ByteArray, off: Int): Int =
            (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

        fun readU32(data: ByteArray, off: Int): Int =
            (data[off].toInt() and 0xFF) or
                ((data[off + 1].toInt() and 0xFF) shl 8) or
                ((data[off + 2].toInt() and 0xFF) shl 16) or
                ((data[off + 3].toInt() and 0xFF) shl 24)

        /** 便捷写入辅助（供补丁器重组用） */
        fun writeU16(buf: ByteArray, off: Int, v: Int) = StringPool.putU16(buf, off, v)

        fun writeU32(buf: ByteArray, off: Int, v: Int) = StringPool.putU32(buf, off, v)

        fun concat(vararg parts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (p in parts) out.write(p)
            return out.toByteArray()
        }
    }
}
