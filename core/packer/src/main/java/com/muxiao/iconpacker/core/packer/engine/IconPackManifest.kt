package com.muxiao.iconpacker.core.packer.engine

import com.muxiao.iconpacker.core.packer.axml.AxmlReader
import com.muxiao.iconpacker.core.packer.axml.AxmlWriter
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 解析导入 ZIP 内的 `appfilter.xml`（图标包规范的权威映射表）。
 *
 * 为什么需要它：标准图标包（含本产品自己的产物）把"组件 → 图标"的对应关系
 * 写在 `res/xml/appfilter.xml` 里，这是**零猜测**的一一对应。此前 ZIP 导入只按
 * 文件名匹配，遇到 `ic_0001` 这类无语义命名就完全无从下手——实测把本产品的
 * 产物 APK 当 ZIP 导入，1025 张图标全部解析成 `ic_0001…ic_1025`。
 *
 * 两种存储格式都要支持：
 * - **二进制 AXML**：APK 内资源与从 APK 解出的包（自研 [AxmlReader] 已由
 *  `AxmlAapt2ParityTest` 证明可读 aapt2 产物，含 UTF-8 串池与中文多字节串）；
 * - **明文 XML**：第三方以 ZIP 形式分发的图标包（非 APK 形态）。
 */
object IconPackManifest {

    /** 一条 `component → drawable` 映射 */
    data class Entry(val component: String, val drawable: String)

    enum class Format { BINARY_AXML, PLAIN_XML }

    data class Parsed(val entries: List<Entry>, val format: Format)

    private const val COMPONENT_INFO = "ComponentInfo"

    /** 是否为二进制 AXML（根 chunk 类型 0x0003） */
    fun isBinaryAxml(bytes: ByteArray): Boolean =
        bytes.size >= 2 && AxmlReader.readU16(bytes, 0) == AxmlWriter.RES_XML_TYPE

    /**
     * 解析 appfilter。
     *
     * 只取同时具备 `component` 与 `drawable` 两个属性的 `<item>`；其余节点
     * （`<version>`、仅含 `drawable` 的图标清单条目）一律忽略。
     *
     * @throws IllegalArgumentException 字节内容两种格式都不是时
     */
    fun parse(bytes: ByteArray): Parsed =
        if (isBinaryAxml(bytes)) {
            Parsed(collect(AxmlReader(bytes).root), Format.BINARY_AXML)
        } else {
            Parsed(parsePlainXml(bytes), Format.PLAIN_XML)
        }

    /**
     * 从 component 字符串提取包名。
     *
     * appfilter 里的 component 是 `ComponentInfo{包名/活动类}`，但各图标包写法不一
     * （有的省略 `ComponentInfo` 前缀，有的只给包名），且**声明的活动类未必是本机
     * 的启动活动**（可能是别名 Activity）。因此映射只认包名，由调用方换成设备上
     * 实际的启动组件——按整串 component 相等去匹配会大量落空。
     */
    fun packageOfComponent(component: String): String? {
        val inner = component.trim()
            .trimStart('{')
            .removePrefix(COMPONENT_INFO)
            .trimStart('{')
            .removeSuffix("}")
            .trim()
        val pkg = inner.substringBefore('/').trim()
        return if (IconMatchKeys.looksLikePackage(pkg)) pkg else null
    }

    // ---- 内部 ----

    /** 递归收集所有 `<item component=... drawable=...>`；component 属性可能带命名空间 */
    private fun collect(element: AxmlReader.Element): List<Entry> {
        val found = ArrayList<Entry>()
        if (element.name == "item") {
            val component = element.attributes.firstOrNull { it.name == "component" }?.rawValue
            val drawable = element.attributes.firstOrNull { it.name == "drawable" }?.rawValue
            if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                found += Entry(component, drawable)
            }
        }
        element.children.forEach { found += collect(it) }
        return found
    }

    /**
     * 明文 XML 分支。
     *
     * 显式关掉 DTD 与外部实体：导入的 ZIP 是**不可信输入**，实体展开是典型的
     * XXE 入口。Android 与 JVM 都可能不支持这些 feature，故逐个 runCatching，
     * 不支持时降级而不是让导入失败。
     */
    private fun parsePlainXml(bytes: ByteArray): List<Entry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val nodes = document.getElementsByTagName("item")
        val entries = ArrayList<Entry>(nodes.length)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val attrs = node.attributes ?: continue
            val component = attrs.getNamedItem("component")?.nodeValue
            val drawable = attrs.getNamedItem("drawable")?.nodeValue
            if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                entries += Entry(component, drawable)
            }
        }
        return entries
    }
}
