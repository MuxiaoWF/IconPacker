package com.muxiao.iconpacker.core.packer.zip

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * ZIP 条目名的编码判定（纯 Kotlin，可 JVM 单测）。
 *
 * 为什么需要自己判：ZIP 规范用通用标志位 bit 11 表示"文件名是 UTF-8"，但**中文 Windows
 * 自带的"压缩为 ZIP"根本不置这个位**，而是按系统 ANSI（GBK）写字节。而 Android 的
 * `java.util.zip` 实现**无视该标志位、一律按 UTF-8 解码**，遇到 GBK 字节直接抛
 * `IllegalArgumentException("MALFORMED[1]")`——用户在导入界面上看到的就是这句没有任何
 * 可操作信息的框架报错（实测：163 张图的包里 135 个条目名是 GBK，导入在第 2 个条目就崩）。
 *
 * 判定策略是**整包一次性决策**而不是逐条猜：
 * 1. 只要有未置标志且不是合法 UTF-8 的名字，就认定这个包是"旧式编码"，**所有未置标志的名字
 *    都按 GB18030 解**。逐条回退会踩到一个陷阱：`D6 B1 C6 B8`（GBK 的"直聘"）恰好也是
 *    合法 UTF-8，逐条解码会得到 `ֱƸ` 这种看着像字、其实全错的乱码；
 * 2. 全部合法 UTF-8（或都置了标志）→ 就按 UTF-8，这是绝大多数现代工具的行为；
 * 3. 兜底 ISO-8859-1：它把字节一对一映射成字符，永不失败，保证不会因为一个畸形名字
 *    让整包导入失败。
 *
 * GB18030 是 GBK 的超集，所以旧式编码只用它一个即可覆盖 GBK/GB2312。
 */
object ZipNameDecoder {

    /** 一个条目的原始名字字节 + 是否声明了 UTF-8（通用标志位 bit 11） */
    class Raw(val bytes: ByteArray, val utf8Flag: Boolean)

    private val UTF8: Charset = Charsets.UTF_8

    /** 旧式中文编码的回退链：GB18030 ⊃ GBK；都取不到时（理论上不会）退 ISO-8859-1 */
    private val LEGACY: Charset? by lazy {
        listOf("GB18030", "GBK").firstNotNullOfOrNull { name ->
            runCatching { Charset.forName(name) }.getOrNull()
        }
    }

    private val LATIN1: Charset = Charsets.ISO_8859_1

    /**
     * 解码整包的条目名，顺序与入参一致。
     *
     * @param raws 中央目录里的全部条目名（含目录条目）
     */
    fun decodeAll(raws: List<Raw>): List<String> {
        if (raws.isEmpty()) return emptyList()
        val legacy = LEGACY
        // 整包判定：只要有"未声明 UTF-8 且不是合法 UTF-8"的名字，就按旧式编码解全部
        val legacyArchive = legacy != null &&
            raws.any { !it.utf8Flag && strictDecode(it.bytes, UTF8) == null }

        return raws.map { raw ->
            when {
                // 作者明确声明了 UTF-8：优先信它；但声明与实际不符的包确实存在，
                // 字节解不成 UTF-8 时再按旧式编码救一次，救不回来才容忍乱码。
                raw.utf8Flag -> strictDecode(raw.bytes, UTF8)
                    ?: legacy?.let { strictDecode(raw.bytes, it) }
                    ?: lenient(raw.bytes, UTF8)
                legacyArchive -> strictDecode(raw.bytes, legacy) ?: lenient(raw.bytes, UTF8)
                else -> strictDecode(raw.bytes, UTF8) ?: lenient(raw.bytes, LATIN1)
            }
        }
    }

    /** 严格解码：任何非法字节都返回 null（用 REPORT 而不是默认的替换字符） */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? =
        runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    /** 宽松解码：替换非法字节，保证返回一个字符串 */
    private fun lenient(bytes: ByteArray, charset: Charset): String = String(bytes, charset)
}
