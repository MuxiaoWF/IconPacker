package com.muxiao.iconpacker.core.packer.axml

/**
 * AXML 字符串池（UTF-16LE，与 framework ResStringPool 布局一致）。
 *
 * 布局：28 字节头 + N 个 4 字节偏移 + 字符串数据（每条以 2 字节 0 结尾），
 * chunk 整体 4 字节对齐。字符串按首次插入顺序存放并去重，
 * framework 解析器不要求排序，插入序即可。
 *
 * 空字符串不入池：AXML 用索引 -1（0xFFFFFFFF）表示"无"，
 * 调用方不得 intern 空串。
 */
class StringPool {

    private val strings = ArrayList<String>()
    private val index = HashMap<String, Int>()

    /** 插入（或复用）字符串，返回其索引。空串会抛异常。 */
    fun intern(s: String): Int {
        require(s.isNotEmpty()) { "空字符串不入池，请使用 -1 表示无" }
        return index.getOrPut(s) {
            strings.add(s)
            strings.size - 1
        }
    }

    /** 当前已插入的字符串条数 */
    val size: Int get() = strings.size

    /**
     * 序列化为完整的字符串池 chunk（含 chunk 头）。
     * 必须在所有 intern 完成后调用。
     */
    fun toBytes(): ByteArray = encode(strings)

    companion object {
        const val RES_STRING_POOL_TYPE = 0x0001

        /**
         * 把给定字符串列表（按索引顺序）序列化为字符串池 chunk。
         * 不走去重：调用方保证列表本身无重复或可接受重复。
         * 供 [AxmlPatcher] 重建模板清单的字符串池（顺序不变，仅替换单条）。
         */
        fun encode(strings: List<String>): ByteArray {
            val n = strings.size
            val headerSize = 28
            val offsetsSize = 4 * n
            val dataStart = headerSize + offsetsSize

            val encoded = ArrayList<ByteArray>(n)
            var dataLen = 0
            for (s in strings) {
                // UTF-16 条目布局：u16 长度前缀 + UTF-16LE 数据 + u16 0 结尾。
                // 长度前缀不可省略——framework 与 [AxmlReader]/[AxmlPatcher] 均按此前缀
                // 定位条目，缺失会导致整体错位一字节（首字符被当成长度吞掉）。
                val chars = s.toByteArray(Charsets.UTF_16LE)
                val len = chars.size / 2
                val bytes = ByteArray(utf16LengthPrefixSize(len) + chars.size + 2)
                writeUtf16LengthPrefix(bytes, 0, len)
                System.arraycopy(chars, 0, bytes, utf16LengthPrefixSize(len), chars.size)
                // 结尾 2 字节保持 0（终止符）
                encoded.add(bytes)
                dataLen += bytes.size
            }

            // chunk 整体 4 字节对齐（dataStart 已 4 对齐；数据段不满时补零）
            val padding = (4 - (dataStart + dataLen) % 4) % 4
            val chunkSize = dataStart + dataLen + padding

            val out = ByteArray(chunkSize)
            // ResChunk_header
            putU16(out, 0, RES_STRING_POOL_TYPE)
            putU16(out, 2, headerSize)
            putU32(out, 4, chunkSize)
            // ResStringPool_header
            putU32(out, 8, n) // stringCount
            putU32(out, 12, 0) // styleCount
            putU32(out, 16, 0) // flags：0 = UTF-16
            putU32(out, 20, dataStart) // stringsStart
            putU32(out, 24, 0) // stylesStart
            // 偏移数组
            var off = 0
            for (i in 0 until n) {
                putU32(out, headerSize + 4 * i, off)
                off += encoded[i].size
            }
            // 数据段
            var pos = dataStart
            for (bytes in encoded) {
                System.arraycopy(bytes, 0, out, pos, bytes.size)
                pos += bytes.size
            }
            return out
        }

        /**
         * 从二进制 XML 中解析字符串池 chunk，返回按索引排列的字符串列表（与 [encode] 对称）。
         *
         * UTF-16 与 UTF-8 两种池都支持。注意 UTF-8 池中每条字符串带**两个**长度前缀：
         * 先 UTF-16 字符数（供 String16 转换），再 UTF-8 字节数（真实数据长度）。
         * 纯 ASCII 时二者数值相同，看上去像"长度字节重复写了两遍"；含中文等多字节字符时必然不同。
         * 只读一个前缀会让数据起点与长度同时取错——这正是 aapt2 产物一度解析失败的原因
         * （参见 AOSP ResourceTypes.cpp 的 decodeLength）。
         *
         * [poolOff] 为字符串池 chunk 的起始偏移。调用方应保证其 type 正确。
         */
        fun decode(data: ByteArray, poolOff: Int): List<String> {
            require(AxmlReader.readU16(data, poolOff) == RES_STRING_POOL_TYPE) {
                "不是字符串池 chunk：0x${AxmlReader.readU16(data, poolOff).toString(16)}"
            }
            val headerSize = AxmlReader.readU16(data, poolOff + 2)
            val count = AxmlReader.readU32(data, poolOff + 8)
            val flags = AxmlReader.readU32(data, poolOff + 16)
            val stringsStart = AxmlReader.readU32(data, poolOff + 20)
            val utf8 = (flags and 0x100) != 0
            val out = ArrayList<String>(count)
            for (i in 0 until count) {
                val rel = AxmlReader.readU32(data, poolOff + headerSize + 4 * i)
                out.add(readOne(data, poolOff + stringsStart + rel, utf8))
            }
            return out
        }

        /** 读单条字符串；[off] 指向首个长度前缀 */
        private fun readOne(data: ByteArray, off: Int, utf8: Boolean): String {
            if (!utf8) {
                val len = AxmlReader.readU16(data, off)
                val bytes = ByteArray(2 * len)
                System.arraycopy(data, off + 2, bytes, 0, 2 * len)
                return String(bytes, Charsets.UTF_16LE)
            }
            val utf16 = readUtf8Length(data, off)
            val utf8Len = readUtf8Length(data, off + utf16.second)
            val p = off + utf16.second + utf8Len.second
            return String(data, p, utf8Len.first, Charsets.UTF_8)
        }

        /**
         * 读 UTF-8 池的变长长度前缀，返回 (长度值, 前缀字节数)。
         *
         * AOSP 规则：小于 0x80 占 1 字节；否则占 2 字节，首字节高位置 0x80 表示高 8 位。
         */
        private fun readUtf8Length(data: ByteArray, off: Int): Pair<Int, Int> {
            val b0 = data[off].toInt() and 0xFF
            return if ((b0 and 0x80) != 0) {
                (((b0 and 0x7F) shl 8) or (data[off + 1].toInt() and 0xFF)) to 2
            } else {
                b0 to 1
            }
        }

        internal fun putU16(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        internal fun putU32(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v shr 8) and 0xFF).toByte()
            buf[off + 2] = ((v shr 16) and 0xFF).toByte()
            buf[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        /**
         * UTF-16 长度前缀的字节数。
         *
         * AOSP 编码：长度小于 0x8000 用单个 u16；否则拆成两个 u16，
         * 首字或上 0x8000 标记位（见 ResourceTypes.cpp 的 encodeLength）。
         */
        internal fun utf16LengthPrefixSize(len: Int): Int = if (len < 0x8000) 2 else 4

        /** 按 AOSP 规则写入 UTF-16 长度前缀，返回消耗的字节数 */
        internal fun writeUtf16LengthPrefix(buf: ByteArray, off: Int, len: Int): Int {
            if (len < 0x8000) {
                putU16(buf, off, len)
                return 2
            }
            val hi = (len shr 16) or 0x8000
            putU16(buf, off, hi)
            putU16(buf, off + 2, len and 0xFFFF)
            return 4
        }
    }
}
