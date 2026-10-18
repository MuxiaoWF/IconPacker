package com.muxiao.iconpacker.core.packer.engine

import java.io.File
import java.util.zip.ZipFile

/**
 * 产物体检：打包成功后对产物做一组轻量结构检查。
 *
 * 这些断言原本只活在 RealTemplateTest 里（CI 期守门）；运行时把同样的问题
 * 对着刚产出的 APK 再问一遍，让"装上去图标不生效"提前到"打包页就能看到
 * 哪一项挂了"。检查全部只读（ZipFile + 条目字节头），不做签名校验——
 * 产物刚刚由 apksig 签完，重复验证耗时且无增量信息。
 *
 * 检查项与打包引擎的硬约束一一对应：
 * - [CheckId.APK_READABLE]：ZIP 可打开（产物非空、中央目录完好）
 * - [CheckId.MANIFEST_PRESENT]：AndroidManifest.xml 在（装不上的第一原因）
 * - [CheckId.ARSC_PRESENT]：resources.arsc 在（getIdentifier 按包名找资源的根）
 * - [CheckId.APPFILTER_VALID]：appfilter.xml 存在且是合法 AXML/明文 XML 头
 *   （历史上 AxmlWriter 写错 headerSize/attributeStart 就是在这里断的）
 * - [CheckId.ICON_DENSITY]：图标条目落在带密度限定符的目录（禁 anydpi 的落点检查）
 */
object ArtifactInspector {

    /** 检查项标识；文案由 UI 层映射（core 不持有资源） */
    enum class CheckId {
        APK_READABLE,
        MANIFEST_PRESENT,
        ARSC_PRESENT,
        APPFILTER_VALID,
        ICON_DENSITY,
    }

    /** 单项结果：[detail] 是给 UI 附注的上下文（体积/条目名/计数），可为空串 */
    data class Check(val id: CheckId, val passed: Boolean, val detail: String)

    /**
     * 图标条目：带密度限定符的 drawable 目录下的位图（AGENTS.md：禁用 anydpi）。
     * 负向先行排除 anydpi——它的目录名同样以 "dpi" 结尾，会被朴素匹配误放行。
     */
    private val DENSITY_ICON_ENTRY =
        Regex("res/drawable-(?!anydpi)[^/]*dpi[^/]*/.+\\.(png|webp)", RegexOption.IGNORE_CASE)

    fun inspect(apk: File): List<Check> = runCatching { inspectZip(apk) }.getOrElse { throwable ->
        // 连 ZIP 都打不开：全部判失败，原因原样带给 UI
        val reason = throwable.message.orEmpty().ifBlank { throwable.javaClass.simpleName }
        CheckId.entries.map { Check(it, false, reason) }
    }

    private fun inspectZip(apk: File): List<Check> {
        ZipFile(apk).use { zip ->
            val entryNames = zip.entries().asSequence()
                .map { it.name.replace('\\', '/') }
                .toList()

            val manifestOk = entryNames.any { it.equals("AndroidManifest.xml", ignoreCase = true) }
            val arscOk = entryNames.any { it.equals("resources.arsc", ignoreCase = true) }

            val appfilterEntry = zip.getEntry("res/xml/appfilter.xml")
                ?: entryNames.firstOrNull { it.endsWith("/appfilter.xml", true) }
                    ?.let { zip.getEntry(it) }
            val appfilterOk = appfilterEntry != null && zip.getInputStream(appfilterEntry).use { stream ->
                val bytes = stream.readBytes()
                // 合法二进制 AXML 首字节是 0x03（RES_XML_TYPE）；兼容明文 XML 的极少数包
                bytes.size > 8 && (bytes[0] == 0x03.toByte() || bytes[0] == '<'.code.toByte())
            }

            val iconCount = entryNames.count { DENSITY_ICON_ENTRY.containsMatchIn(it) }

            return listOf(
                Check(CheckId.APK_READABLE, true, "${apk.length() / 1024} KB"),
                Check(
                    CheckId.MANIFEST_PRESENT,
                    manifestOk,
                    if (manifestOk) "" else "AndroidManifest.xml",
                ),
                Check(CheckId.ARSC_PRESENT, arscOk, if (arscOk) "" else "resources.arsc"),
                Check(
                    CheckId.APPFILTER_VALID,
                    appfilterOk,
                    appfilterEntry?.name.orEmpty(),
                ),
                Check(CheckId.ICON_DENSITY, iconCount > 0, iconCount.toString()),
            )
        }
    }
}
