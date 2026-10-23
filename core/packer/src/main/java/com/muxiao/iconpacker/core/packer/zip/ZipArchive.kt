package com.muxiao.iconpacker.core.packer.zip

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** ZIP 结构层面的问题（损坏 / 加密 / 不支持的压缩方式）。消息只用于日志与排查，不直接给用户看 */
class ZipFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * ZIP 里的一个条目。
 *
 * 只保留解包需要的字段。`compressedSize` / `size` 取自**中央目录**而不是本地头：
 * 带数据描述符（标志位 bit 3）的条目，本地头里的长度是 0，只有中央目录才是准的。
 */
class ZipEntry internal constructor(
    val name: String,
    val isDirectory: Boolean,
    val method: Int,
    val compressedSize: Long,
    /** 解压后大小（不可信输入，调用方仍要自己设上限） */
    val size: Long,
    val isEncrypted: Boolean,
    internal val localHeaderOffset: Long,
) {
    /** 能否读取：未加密且是 STORE / DEFLATE */
    val isReadable: Boolean
        get() = !isEncrypted &&
            (method == ZipArchive.METHOD_STORE || method == ZipArchive.METHOD_DEFLATE)

    override fun toString(): String = "ZipEntry($name, method=$method, size=$size)"
}

/**
 * ZIP 读取器（纯 Kotlin + `java.io` / `java.util.zip`，可 JVM 单测）。
 *
 * **为什么不用 `java.util.zip.ZipFile` / `ZipInputStream`**：两者在 Android 上都无视
 * 通用标志位、一律按 UTF-8 解条目名。中文 Windows 自带的"压缩为 ZIP"用 GBK 写名字且
 * 不置 UTF-8 标志，于是导入在中途抛 `IllegalArgumentException("MALFORMED[1]")`——
 * 一句用户完全无从下手的框架报错。Android 也没有 `ZipFile(File, Charset)` 这个构造，
 * 所以只能自己读中央目录（见 [ZipNameDecoder] 的编码判定）。
 *
 * 实现要点，都是为了"读坏文件也不静默出错"：
 * - 以**中央目录**为准（本地头只用来定位数据起点），支持数据描述符；
 * - 支持 STORE / DEFLATE，其余压缩方式逐条报"不支持"而不是整包失败；
 * - 所有偏移都做边界校验，越界立即抛 [ZipFormatException]，不会读到别的条目里去；
 * - 数据读取用 `FileChannel.read(buf, position)`（无共享游标），因此同一实例可被多线程
 *   并发读取不同条目；但 [close] 之后所有流立即失效。
 */
class ZipArchive private constructor(private val raf: RandomAccessFile) : Closeable {

    private val channel: FileChannel = raf.channel
    private val fileSize: Long = raf.length()

    /** 中央目录里的全部条目（顺序即目录顺序） */
    val entries: List<ZipEntry>

    init {
        try {
            entries = readCentralDirectory()
        } catch (t: Throwable) {
            // 构造失败也要把句柄还回去，否则调用方拿不到实例、谁来关它？
            runCatching { raf.close() }
            throw t
        }
    }

    /**
     * 打开条目数据流。调用方负责关闭。
     *
     * 返回的是 [InputStream]，DEFLATE 条目内部是 `InflaterInputStream`（自带释放 inflater）。
     */
    fun open(entry: ZipEntry): InputStream {
        if (entry.isEncrypted) throw ZipFormatException("条目已加密：${entry.name}")
        val header = readAt(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (u32(header, 0) != LOCAL_SIG) {
            throw ZipFormatException("本地文件头损坏：${entry.name}")
        }
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        val dataStart = entry.localHeaderOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
        val raw = BoundedChannelStream(channel, dataStart, entry.compressedSize, entry.name)
        return when (entry.method) {
            METHOD_STORE -> raw
            METHOD_DEFLATE -> OwnedInflaterStream(raw, entry.name)
            else -> throw ZipFormatException("不支持的压缩方式 ${entry.method}：${entry.name}")
        }
    }

    /**
     * 读取条目内容，最多 [limit] 字节（超出即截断，不报错——appfilter 这类元数据的既有语义）。
     *
     * 上限是**硬要求**：ZIP 是不可信输入，不能按条目自称的大小去分配内存。
     */
    fun readBytes(entry: ZipEntry, limit: Int): ByteArray {
        require(limit >= 0) { "limit 不能为负" }
        val hint = minOf(entry.size.coerceAtLeast(0), limit.toLong()).toInt()
        val out = ByteArrayOutputStream(hint.coerceAtLeast(64))
        val buffer = ByteArray(STREAM_BUFFER)
        var total = 0
        open(entry).use { input ->
            while (total < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        return out.toByteArray()
    }

    override fun close() {
        runCatching { raf.close() }
    }

    // ---- 中央目录 ----

    private class Meta(
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        val localOffset: Long,
        val encrypted: Boolean,
    )

    private fun readCentralDirectory(): List<ZipEntry> {
        val (tailOffset, tail) = readTail()
        val eocd = findEocd(tail)
        val eocdAbs = tailOffset + eocd

        var count = u16(tail, eocd + 10).toLong()
        var cdSize = u32(tail, eocd + 12)
        var cdOffset = u32(tail, eocd + 16)

        // ZIP64：EOCD 里放不下时按规范去读 ZIP64 记录，读不到就按原值继续（可能是畸形但可读的包）
        if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            readZip64(cdOffset, cdSize, count, eocdAbs)?.let { (c, size, offset) ->
                count = c
                cdSize = size
                cdOffset = offset
            }
        }
        if (count == 0L) return emptyList()
        if (count > MAX_ENTRIES) throw ZipFormatException("条目数异常：$count")
        if (cdOffset < 0 || cdSize < 0 || cdSize > fileSize || cdOffset > fileSize - cdSize) {
            throw ZipFormatException("中央目录越界：offset=$cdOffset size=$cdSize fileSize=$fileSize")
        }

        val raws = ArrayList<ZipNameDecoder.Raw>(count.toInt())
        val metas = ArrayList<Meta>(count.toInt())
        var position = cdOffset
        for (index in 0 until count) {
            val header = readAt(position, CENTRAL_HEADER_SIZE)
            if (u32(header, 0) != CENTRAL_SIG) {
                throw ZipFormatException("中央目录损坏（第 ${index + 1} 条）")
            }
            val flags = u16(header, 8)
            val method = u16(header, 10)
            var compressedSize = u32(header, 20)
            var size = u32(header, 24)
            val nameLength = u16(header, 28)
            val extraLength = u16(header, 30)
            val commentLength = u16(header, 32)
            var localOffset = u32(header, 42)
            if (nameLength > MAX_NAME_BYTES) throw ZipFormatException("条目名过长：$nameLength")

            val nameBytes = readAt(position + CENTRAL_HEADER_SIZE, nameLength)
            if (size == ZIP64_SENTINEL || compressedSize == ZIP64_SENTINEL || localOffset == ZIP64_SENTINEL) {
                val extra = readAt(position + CENTRAL_HEADER_SIZE + nameLength, extraLength)
                val fixed = applyZip64Extra(extra, size, compressedSize, localOffset)
                size = fixed[0]
                compressedSize = fixed[1]
                localOffset = fixed[2]
            }
            raws += ZipNameDecoder.Raw(nameBytes, flags and FLAG_UTF8 != 0)
            metas += Meta(method, compressedSize, size, localOffset, flags and FLAG_ENCRYPTED != 0)
            position += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
        }

        val names = ZipNameDecoder.decodeAll(raws)
        val result = ArrayList<ZipEntry>(names.size)
        for (i in names.indices) {
            val name = names[i]
            if (name.isEmpty()) continue
            val meta = metas[i]
            result += ZipEntry(
                name = name,
                isDirectory = name.endsWith("/") || name.endsWith("\\"),
                method = meta.method,
                compressedSize = meta.compressedSize,
                size = meta.size,
                isEncrypted = meta.encrypted,
                localHeaderOffset = meta.localOffset,
            )
        }
        return result
    }

    /** 读文件尾部（EOCD 最多 22 + 65535 字节注释） */
    private fun readTail(): Pair<Long, ByteArray> {
        if (fileSize < MIN_EOCD_SIZE) throw ZipFormatException("文件过小，不是 ZIP：$fileSize 字节")
        val tailLength = minOf(fileSize, MAX_EOCD_SIZE.toLong()).toInt()
        val start = fileSize - tailLength
        return start to readAt(start, tailLength)
    }

    /**
     * 从尾部往前找 EOCD 签名。
     *
     * 优先接受"注释长度与剩余字节正好吻合"的那一条——ZIP 注释里可以出现同样的签名字节，
     * 只看签名会把注释里的假记录当成目录；实在对不上（少数工具写错注释长度）再退而求其次。
     */
    private fun findEocd(tail: ByteArray): Int {
        var fallback = -1
        var i = tail.size - MIN_EOCD_SIZE
        while (i >= 0) {
            if (u32(tail, i) == EOCD_SIG) {
                val commentLength = u16(tail, i + 20)
                if (i + MIN_EOCD_SIZE + commentLength == tail.size) return i
                if (fallback < 0) fallback = i
            }
            i--
        }
        if (fallback >= 0) return fallback
        throw ZipFormatException("未找到中央目录（文件可能已损坏）")
    }

    /** ZIP64 记录；不存在或读不通返回 null（退回普通 EOCD 的值） */
    private fun readZip64(
        cdOffset: Long,
        cdSize: Long,
        count: Long,
        eocdAbs: Long,
    ): Triple<Long, Long, Long>? {
        val locatorAt = eocdAbs - ZIP64_LOCATOR_SIZE
        if (locatorAt < 0) return null
        val locator = runCatching { readAt(locatorAt, ZIP64_LOCATOR_SIZE) }.getOrNull() ?: return null
        if (u32(locator, 0) != ZIP64_LOCATOR_SIG) return null
        val recordAt = u64(locator, 8)
        val record = runCatching { readAt(recordAt, ZIP64_EOCD_SIZE) }.getOrNull() ?: return null
        if (u32(record, 0) != ZIP64_EOCD_SIG) return null
        val fixedCount = u64(record, 32).takeIf { it > 0 } ?: count
        val fixedSize = u64(record, 40).takeIf { it > 0 } ?: cdSize
        val fixedOffset = u64(record, 48).takeIf { it > 0 } ?: cdOffset
        return Triple(fixedCount, fixedSize, fixedOffset)
    }

    /** 从 0x0001 扩展字段里取 ZIP64 的尺寸/偏移（只填 sentinel 的那几项） */
    private fun applyZip64Extra(
        extra: ByteArray,
        size: Long,
        compressedSize: Long,
        localOffset: Long,
    ): LongArray {
        var p = 0
        while (p + 4 <= extra.size) {
            val id = u16(extra, p)
            val length = u16(extra, p + 2)
            val end = p + 4 + length
            if (end > extra.size) break
            if (id == ZIP64_EXTRA_ID) {
                var q = p + 4
                var u = size
                var c = compressedSize
                var l = localOffset
                if (u == ZIP64_SENTINEL && q + 8 <= end) {
                    u = u64(extra, q)
                    q += 8
                }
                if (c == ZIP64_SENTINEL && q + 8 <= end) {
                    c = u64(extra, q)
                    q += 8
                }
                if (l == ZIP64_SENTINEL && q + 8 <= end) {
                    l = u64(extra, q)
                }
                return longArrayOf(u, c, l)
            }
            p = end
        }
        return longArrayOf(size, compressedSize, localOffset)
    }

    // ---- 底层读取 ----

    /** 从固定位置读满 [length] 字节；越界立即抛错（不做静默截断） */
    private fun readAt(position: Long, length: Int): ByteArray {
        // 用减法做上界判断，避免 position + length 在 Long 上溢出后绕过校验
        if (position < 0 || length < 0 || position > fileSize - length) {
            throw ZipFormatException("读取越界：offset=$position len=$length fileSize=$fileSize")
        }
        if (length == 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        var read = 0
        while (read < length) {
            val n = channel.read(buffer, position + read)
            if (n <= 0) throw ZipFormatException("读取中断：offset=${position + read}")
            read += n
        }
        return buffer.array()
    }

    /** 条目数据流：按位置读，不共享文件游标（同一归档可并发读不同条目） */
    private class BoundedChannelStream(
        private val channel: FileChannel,
        private val start: Long,
        private val length: Long,
        private val entryName: String,
    ) : InputStream() {

        private var position = 0L
        private val single = ByteArray(1)

        override fun read(): Int {
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= length) return -1
            val want = minOf(len.toLong(), length - position).toInt()
            val n = channel.read(ByteBuffer.wrap(b, off, want), start + position)
            if (n < 0) {
                throw ZipFormatException("条目数据被截断：$entryName（缺 ${length - position} 字节）")
            }
            if (n == 0) return -1
            position += n
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val step = minOf(n, length - position)
            position += step
            return step
        }

        override fun available(): Int = (length - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    /** 自带 inflater 释放：`InflaterInputStream` 只在"自己创建 inflater"时才 end()，我们传了外部的 */
    private class OwnedInflaterStream(
        input: InputStream,
        private val entryName: String,
    ) : InflaterInputStream(input, Inflater(true), STREAM_BUFFER) {
        override fun close() {
            try {
                super.close()
            } finally {
                runCatching { inf.end() }
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            super.read(b, off, len)
        } catch (e: ZipFormatException) {
            throw e
        } catch (e: IOException) {
            throw ZipFormatException("解压失败：$entryName（${e.message}）", e)
        }
    }

    companion object {
        const val METHOD_STORE = 0
        const val METHOD_DEFLATE = 8

        /** 条目名上限：正常图标包的资源名远小于此，超了就是异常输入 */
        private const val MAX_NAME_BYTES = 4096

        /** 条目数上限：防被伪造的中央目录拖成天量循环 */
        private const val MAX_ENTRIES = 200_000L

        private const val STREAM_BUFFER = 64 * 1024
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val MIN_EOCD_SIZE = 22
        private const val MAX_EOCD_SIZE = 22 + 0xFFFF
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_EOCD_SIZE = 56

        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_UTF8 = 0x0800

        private const val ZIP64_EXTRA_ID = 0x0001
        private const val ZIP64_SENTINEL = 0xFFFFFFFFL

        private const val LOCAL_SIG = 0x04034b50L
        private const val CENTRAL_SIG = 0x02014b50L
        private const val EOCD_SIG = 0x06054b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L

        /** 打开一个 ZIP 文件；结构损坏时抛 [ZipFormatException] */
        fun open(file: File): ZipArchive = ZipArchive(RandomAccessFile(file, "r"))

        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, off: Int): Long =
            (u16(b, off).toLong()) or (u16(b, off + 2).toLong() shl 16)

        private fun u64(b: ByteArray, off: Int): Long =
            u32(b, off) or (u32(b, off + 4) shl 32)
    }
}
