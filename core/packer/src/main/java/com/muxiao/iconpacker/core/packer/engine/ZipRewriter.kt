package com.muxiao.iconpacker.core.packer.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP 定点重写器：把源 ZIP 中的指定条目替换为新字节、按前缀剥离条目，其余原样拷贝。
 *
 * 打包管线用途（对模板 APK 做手术）：
 * - 替换：`res/drawable-xxxhdpi-v4/ic_XXXX.png`（用户图标）、`res/xml/appfilter.xml`、
 *   `res/xml/drawable.xml`、`AndroidManifest.xml`；
 * - 剥离：`META-INF/`（模板自带的调试签名——apksig 拒绝为带 JAR 签名的输入再签名）。
 *
 * 工程取舍：
 * - **保留源条目的原始压缩方式**：模板由 aapt2 产出，其中 `resources.arsc` 是
 *   STORED（未压缩）且 4 字节对齐的。Android R+（targetSdk ≥ 30）强制要求
 *   `resources.arsc` 必须 STORED 且 4 字节对齐，否则安装报
 *   `-124: ... resources.arsc ... must be stored uncompressed and aligned on a 4-byte boundary`。
 *   旧实现把所有条目统一 DEFLATED，等于把 resources.arsc 重新压缩，正是该报错的元凶。
 *   因此这里**拷贝条目沿用其原 method**，替换/新增条目（文本与 PNG）默认 DEFLATED。
 * - **STORED 条目强制 4 字节对齐**：通过往 extra 字段写入一个 APK 对齐扩展域
 *   （tag 0xD935）补足偏移，确保数据起点落在 4 字节边界。apksig 签名为 v2/v3 插入的
 *   签名块是 4096 页对齐、为 v1 追加的 META-INF 条目不会改变既有条目的数据偏移，
 *   故签名后对齐仍成立。
 * - 写入固定 ZIP 时间戳（[FIXED_TIME]）：同一模板 + 同一组替换 ⇒ 逐字节相同的产物，
 *   满足"结论可复核"的构建可复现性；也避免把构建时刻泄漏进产物。
 * - 条目顺序保持模板原顺序；源中不存在的新条目追加在末尾。
 */
class ZipRewriter(private val source: ByteArray) {

    private val replacements = LinkedHashMap<String, ByteArray>()
    /** 要求沿用源条目压缩方式的替换条目（resources.arsc 必须 STORED + 4 字节对齐） */
    private val keepSourceMethod = HashSet<String>()
    private val dropPrefixes = ArrayList<String>()

    /** 替换（或新增）一个条目 */
    fun replace(entryName: String, bytes: ByteArray): ZipRewriter {
        replacements[entryName] = bytes
        return this
    }

    /**
     * 替换一个**源中已存在**的条目，且沿用其原始压缩方式。
     *
     * 用于 resources.arsc 这类必须保持 STORED + 4 字节对齐的条目：
     * 走 [replace] 会被统一写成 DEFLATED，targetSdk ≥ 30 安装时报
     * `resources.arsc must be stored uncompressed and aligned`。
     * 源中不存在该条目时按 [replace] 语义处理（新条目没有"原始方式"可言）。
     */
    fun replacePreservingCompression(entryName: String, bytes: ByteArray): ZipRewriter {
        replacements[entryName] = bytes
        keepSourceMethod.add(entryName)
        return this
    }

    /** 剥离所有指定前缀的条目（如 META-INF/） */
    fun dropPrefix(prefix: String): ZipRewriter {
        dropPrefixes.add(prefix)
        return this
    }

    /** 执行重写，返回新 ZIP 字节。可重复调用（内部使用待写副本，不消耗替换表）。 */
    fun build(): ByteArray {
        // 待写副本：替换表是构建配方，build() 不得消耗它（否则第二次 build 会丢条目）
        val pending = LinkedHashMap(replacements)
        val out = ByteArrayOutputStream(source.size + pending.values.sumOf { it.size } + 4096)
        ZipOutputStream(out).use { zos ->
            ZipInputStream(ByteArrayInputStream(source)).use { zis ->
                var srcEntry = zis.nextEntry
                while (srcEntry != null) {
                    val name = srcEntry.name
                    val dropped = dropPrefixes.any { name.startsWith(it) }
                    if (!dropped) {
                        val replacement = pending.remove(name)
                        val preserve = name in keepSourceMethod
                        if (replacement != null) {
                            // 替换条目：用户图标 / 生成的 XML，默认 DEFLATED（小且无需 STORED）；
                            // 标记了沿用压缩方式的条目（resources.arsc）取源条目的 method
                            val method =
                                if (preserve) srcEntry.method else ZipEntry.DEFLATED
                            writeEntry(zos, out, name, replacement, method, srcEntry.isDirectory)
                        } else {
                            // 拷贝条目：沿用模板原始压缩方式（resources.arsc 等 STORED 得以保留）
                            val bytes = zis.readBytes()
                            writeEntry(zos, out, name, bytes, srcEntry.method, srcEntry.isDirectory)
                        }
                    }
                    srcEntry = zis.nextEntry
                }
            }
            // 源中不存在的替换条目，按插入顺序追加
            for ((name, bytes) in pending) {
                writeEntry(zos, out, name, bytes, ZipEntry.DEFLATED, name.endsWith("/"))
            }
        }
        return out.toByteArray()
    }

    private fun writeEntry(
        zos: ZipOutputStream,
        out: ByteArrayOutputStream,
        name: String,
        bytes: ByteArray,
        method: Int,
        isDirectory: Boolean,
    ) {
        val entry = ZipEntry(name)
        entry.time = FIXED_TIME
        if (!isDirectory) {
            entry.method = method
            if (method == ZipEntry.STORED) {
                // STORED 条目必须预先写入 CRC / 大小，且需 4 字节对齐
                val crc = CRC32().apply { update(bytes) }
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = crc.value
                entry.extra = alignmentExtra(out.size().toLong(), name)
            }
        }
        zos.putNextEntry(entry)
        if (!isDirectory) {
            zos.write(bytes)
        }
        zos.closeEntry()
    }

    /**
     * 计算使条目数据起点落在对齐边界（[ALIGNMENT] 字节）所需的 extra 字段。
     * 数据起点 = 本地头起点 + 30（固定头）+ 名称字节数 + extra 长度。
     * 本地头起点即当前流写入字节数（[offsetBeforeHeader]）。
     * 返回的 extra 字段为单个 APK 对齐扩展域（tag 0xD935），保证可被 ZIP 阅读器正确解析。
     */
    private fun alignmentExtra(offsetBeforeHeader: Long, name: String): ByteArray {
        val alignment = ALIGNMENT
        val nameLen = name.toByteArray(Charsets.UTF_8).size
        val headerBase = LOCAL_HEADER_FIXED + nameLen
        val need = (alignment - ((offsetBeforeHeader + headerBase) % alignment)) % alignment
        if (need == 0L) return ByteArray(0)
        // extra 长度必须 ≥ 4 才能构成合法扩展域（tag 2 + len 2 + payload），故取 need + alignment
        val extraLen = (need + alignment).toInt()
        val payloadLen = extraLen - EXTRA_HEADER_FIXED
        val buf = ByteArray(extraLen)
        buf[0] = 0xD9.toByte() // 对齐扩展域 tag 高字节
        buf[1] = 0x35.toByte() // tag 低字节
        buf[2] = (payloadLen and 0xFF).toByte() // payload 长度低字节
        buf[3] = ((payloadLen ushr 8) and 0xFF).toByte() // payload 长度高字节
        // payload 余下字节保持 0，对齐扩展域的 value 字段值无实际语义要求
        return buf
    }

    companion object {
        /** 固定 ZIP 时间戳：2010-01-01T00:00:00Z（DOS 时间合法值） */
        const val FIXED_TIME = 1262304000000L

        /** STORED 条目（如 resources.arsc）要求的 4 字节对齐 */
        private const val ALIGNMENT = 4

        /** ZIP 本地文件头固定长度（不含名称与 extra） */
        private const val LOCAL_HEADER_FIXED = 30

        /** 扩展域头部固定长度（tag 2 + length 2） */
        private const val EXTRA_HEADER_FIXED = 4
    }
}
