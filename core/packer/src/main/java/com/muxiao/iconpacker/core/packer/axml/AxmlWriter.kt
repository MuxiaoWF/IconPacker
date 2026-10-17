package com.muxiao.iconpacker.core.packer.axml

import java.io.ByteArrayOutputStream

/** AXML 元素节点（名称 + 命名空间 URI + 属性 + 子节点） */
data class AxmlElement(
    val name: String,
    val nsUri: String? = null,
    val attributes: List<AxmlAttr> = emptyList(),
    val children: List<AxmlElement> = emptyList(),
)

/** AXML 属性（名称 + 命名空间 URI + 值） */
data class AxmlAttr(
    val name: String,
    val value: AttrValue,
    val nsUri: String? = null,
)

/**
 * AXML（Android 二进制 XML）写入器。
 *
 * 产物结构与 aapt2 编译的 res/xml 资源一致：
 * ```
 * 文件头 (0x0003) → 字符串池 → [资源表] → [start-namespace × N] → 元素树 → [end-namespace × N]
 * ```
 *
 * 用途：生成 appfilter.xml 与 drawable.xml（均无 android: 命名空间属性，
 * 因此资源表 chunk 为空——仅作与 aapt2 产物对齐的占位；启动器按名称读取 appfilter，
 * 资源表为空不影响按名称解析，但 ColorOS 等严格解析器会逐 chunk 校验，故需保留空表）。
 *
 * 关键布局约束（与 aapt2 编译结果一致，否则框架原生解析器报 "Corrupt XML binary file"）：
 * - 节点 chunk 头中的 headerSize 必须为 16（chunk_header 8 + lineNumber 4 + comment 4），
 *   框架据此定位 ns/name；写错会导致名称索引越界。
 * - start 元素的 attributeStart 必须为 20，即 sizeof(ResXMLTree_attrExt)。
 *   该字段的基准是 **attrExt 结构体起点**（= chunk 起点 + headerSize），而不是 chunk 起点。
 *   误写成 36（chunk 相对偏移）会让框架把属性数组起点算到 chunk 起点 + 52，
 *   越过节点 chunk 末尾，产物被判 corrupt：
 *   `Bad XML block: node attributes use 0x24 bytes, only have 0x14 bytes`。
 *   —— 该错误曾长期存在且被 [AxmlWriterStructureTest] 的错误断言固化，
 *   导致启动器完全读不到 appfilter 映射表（表现为图标包可选中但图标不切换）。
 *
 * 清单不走本类生成，而采用模板补丁（见 [AxmlPatcher]）：模板清单由 aapt2 产出，
 * 其资源表与字符串池天然正确，只需按构建替换 label 与 versionCode。
 *
 * 线程安全：无共享状态，可多实例并发。
 */
class AxmlWriter(
    private val namespaces: List<Namespace> = emptyList(),
) {

    /** 文档级命名空间声明（前缀 → URI） */
    data class Namespace(val prefix: String, val uri: String)

    fun write(root: AxmlElement): ByteArray {
        val pool = StringPool()
        // 收集阶段：所有字符串先入池，保证池完整后才可序列化
        collect(root, pool)
        for ((prefix, uri) in namespaces) {
            pool.intern(prefix)
            pool.intern(uri)
        }

        val body = ByteArrayOutputStream(4096)
        // 字符串池必须是文件头之后的第一个 chunk（ResChunk_header type=0x0001）。
        // 缺失它会导致回读器/框架解析器拿不到任何字符串，所有名称索引越界。
        // 此时 collect 阶段已完成，发射期 intern 均为幂等查找，不会再产生新字符串。
        body.write(pool.toBytes())
        // 空资源表 chunk：与 aapt2 编译产物一致（启动器按名称读 appfilter，表为空不影响解析，
        // 但 ColorOS 等严格解析器会逐 chunk 校验，缺省更易被判为非法 AXML）。
        writeResMap(body)
        for (ns in namespaces) {
            writeNamespaceChunk(body, RES_XML_START_NAMESPACE_TYPE, ns, pool)
        }
        writeElement(body, root, pool)
        for (ns in namespaces.reversed()) {
            writeNamespaceChunk(body, RES_XML_END_NAMESPACE_TYPE, ns, pool)
        }

        // 文件头 + body，头部 size 一次回填
        val bodyBytes = body.toByteArray()
        val out = ByteArray(8 + bodyBytes.size)
        StringPool.putU16(out, 0, RES_XML_TYPE)
        StringPool.putU16(out, 2, 8)
        StringPool.putU32(out, 4, out.size)
        System.arraycopy(bodyBytes, 0, out, 8, bodyBytes.size)
        return out
    }

    private fun collect(el: AxmlElement, pool: StringPool) {
        pool.intern(el.name)
        el.nsUri?.let { pool.intern(it) }
        for (attr in el.attributes) {
            pool.intern(attr.name)
            attr.nsUri?.let { pool.intern(it) }
            when (val v = attr.value) {
                is AttrValue.Str -> pool.intern(v.value)
                is AttrValue.Ref -> v.raw?.let { pool.intern(it) }
                else -> Unit
            }
        }
        for (child in el.children) collect(child, pool)
    }

    private fun writeElement(out: ByteArrayOutputStream, el: AxmlElement, pool: StringPool) {
        val attrCount = el.attributes.size
        // start element：固定结构 = headerSize(16) + ResXMLTree_attrExt(20) = 36 字节，
        // 属性数组紧跟其后。两个偏移的基准不同，不得混用：
        //   chunk.size      —— 相对 chunk 起点，含固定结构与全部属性 = 36 + 20×attrCount
        //   attributeStart  —— 相对 attrExt 起点（chunk 起点 + headerSize）= 20
        out.writeU16(RES_XML_START_ELEMENT_TYPE)
        out.writeU16(NODE_HEADER_SIZE)                          // headerSize = 16（框架据此定位 ns/name）
        out.writeU32(ELEMENT_NODE_SIZE + ATTR_SIZE * attrCount) // size：基准为 chunk 起点
        out.writeU32(1) // lineNumber（框架仅用于错误信息）
        out.writeU32(-1) // comment
        out.writeU32(el.nsUri?.let { pool.intern(it) } ?: -1)
        out.writeU32(pool.intern(el.name))
        out.writeU16(ATTR_EXT_SIZE) // attributeStart：基准为 attrExt 起点 = 20（框架据此定位属性）
        out.writeU16(ATTR_SIZE) // attributeSize
        out.writeU16(attrCount)
        out.writeU16(0xFFFF) // idIndex：无 android:id
        out.writeU16(0xFFFF) // classIndex
        out.writeU16(0xFFFF) // styleIndex
        for (attr in el.attributes) {
            writeAttribute(out, attr, pool)
        }

        for (child in el.children) writeElement(out, child, pool)

        // end element：node(16) + ns(4) + name(4) = 24 字节，无属性
        out.writeU16(RES_XML_END_ELEMENT_TYPE)
        out.writeU16(NODE_HEADER_SIZE)
        out.writeU32(END_NODE_SIZE)
        out.writeU32(1)
        out.writeU32(-1)
        out.writeU32(el.nsUri?.let { pool.intern(it) } ?: -1)
        out.writeU32(pool.intern(el.name))
    }

    private fun writeAttribute(out: ByteArrayOutputStream, attr: AxmlAttr, pool: StringPool) {
        out.writeU32(attr.nsUri?.let { pool.intern(it) } ?: -1)
        out.writeU32(pool.intern(attr.name))
        out.writeU32(attr.value.rawIndex(pool))
        // Res_value：size(2) + res0(1) + dataType(1) + data(4) = 8 字节
        out.writeU16(8)
        out.writeU8(0)
        out.writeU8(attr.value.dataType)
        out.writeU32(attr.value.payload(pool))
    }

    private fun writeNamespaceChunk(
        out: ByteArrayOutputStream,
        type: Int,
        ns: Namespace,
        pool: StringPool,
    ) {
        out.writeU16(type)
        out.writeU16(NODE_HEADER_SIZE)
        out.writeU32(NAMESPACE_HEADER_SIZE)
        out.writeU32(1) // lineNumber
        out.writeU32(-1) // comment
        out.writeU32(pool.intern(ns.prefix))
        out.writeU32(pool.intern(ns.uri))
    }

    /** 空资源表 chunk（type 0x0180）：仅含 8 字节 chunk 头，与 aapt2 一致。 */
    private fun writeResMap(out: ByteArrayOutputStream) {
        out.writeU16(RES_XML_RESOURCE_MAP_TYPE)
        out.writeU16(8)
        out.writeU32(8)
    }

    companion object {
        const val RES_XML_TYPE = 0x0003
        const val RES_XML_START_NAMESPACE_TYPE = 0x0100
        const val RES_XML_END_NAMESPACE_TYPE = 0x0101
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val RES_XML_END_ELEMENT_TYPE = 0x0103
        const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

        const val NAMESPACE_HEADER_SIZE = 24
        /** 节点 chunk 头中的 headerSize 字段：chunk_header(8) + lineNumber(4) + comment(4) */
        const val NODE_HEADER_SIZE = 16
        /**
         * sizeof(ResXMLTree_attrExt)：ns(4) + name(4) + 6×u16 尾部字段(12)。
         *
         * 即 start 元素 [attributeStart] 字段的取值。框架以 attrExt 起点（chunk 起点 +
         * headerSize）为基准，再偏移 attributeStart 定位属性数组；写成 chunk 相对偏移
         * （36）会越界并被判 corrupt。
         */
        const val ATTR_EXT_SIZE = 20
        /** 单个属性尺寸：ns(4) + name(4) + rawValue(4) + Res_value(8) */
        const val ATTR_SIZE = 20
        /**
         * start 元素节点固定结构尺寸 = headerSize + attrExt。
         *
         * 用于计算 chunk.size（基准为 chunk 起点）；**不是** attributeStart 的取值。
         */
        const val ELEMENT_NODE_SIZE = NODE_HEADER_SIZE + ATTR_EXT_SIZE
        /** end 元素节点尺寸 */
        const val END_NODE_SIZE = 24
    }
}

// ---- 内部字节写入辅助 ----

private fun ByteArrayOutputStream.writeU8(v: Int) = write(v and 0xFF)

private fun ByteArrayOutputStream.writeU16(v: Int) {
    write(v and 0xFF)
    write((v shr 8) and 0xFF)
}

private fun ByteArrayOutputStream.writeU32(v: Int) {
    write(v and 0xFF)
    write((v shr 8) and 0xFF)
    write((v shr 16) and 0xFF)
    write((v shr 24) and 0xFF)
}
