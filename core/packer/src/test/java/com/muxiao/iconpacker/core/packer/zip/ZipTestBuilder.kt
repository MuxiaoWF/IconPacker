package com.muxiao.iconpacker.core.packer.zip

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 测试用的最小 ZIP 写入器。
 *
 * 为什么不用 `java.util.zip.ZipOutputStream`：它写非 ASCII 名字时**只**会用 UTF-8 并置标志位，
 * 而本模块要治的正是"GBK 字节 + 不置标志位"这种真实世界里的坏包——造不出这个夹具，
 * 修复就永远是猜测。这里直接按规范拼字节，把编码、标志位、数据描述符都变成显式参数。
 */
internal object ZipTestBuilder {

    class Spec(
        val name: String,
        val data: ByteArray = ByteArray(0),
        /** true = deflate(method 8)，false = store(method 0) */
        val deflate: Boolean = false,
        /** 通用标志位 bit 11：声明"文件名是 UTF-8" */
        val utf8Flag: Boolean = true,
        /** 通用标志位 bit 0：加密（本模块不解密，只要求能识别并跳过） */
        val encrypted: Boolean = false,
        /** 通用标志位 bit 3：长度写在数据描述符里，本地头为 0 */
        val dataDescriptor: Boolean = false,
        /** 条目名的编码（与 [utf8Flag] 独立，才能造出"GBK 字节却不置标志"的包） */
        val charset: Charset = Charsets.UTF_8,
    )

    fun build(specs: List<Spec>): ByteArray {
        val out = ByteArrayOutputStream()
        val metas = ArrayList<Meta>(specs.size)

        for (spec in specs) {
            val nameBytes = spec.name.toByteArray(spec.charset)
            val payload = if (spec.deflate) deflate(spec.data) else spec.data
            val crc = CRC32().apply { update(spec.data) }.value
            val method = if (spec.deflate) 8 else 0
            val flags = flagsOf(spec)
            val offset = out.size()

            out.writeU32(0x04034b50L)
            out.writeU16(20)
            out.writeU16(flags)
            out.writeU16(method)
            out.writeU16(0) // 修改时间
            out.writeU16(0) // 修改日期
            out.writeU32(if (spec.dataDescriptor) 0L else crc)
            out.writeU32(if (spec.dataDescriptor) 0L else payload.size.toLong())
            out.writeU32(if (spec.dataDescriptor) 0L else spec.data.size.toLong())
            out.writeU16(nameBytes.size)
            out.writeU16(0) // extra
            out.write(nameBytes)
            out.write(payload)
            if (spec.dataDescriptor) {
                out.writeU32(0x08074b50L)
                out.writeU32(crc)
                out.writeU32(payload.size.toLong())
                out.writeU32(spec.data.size.toLong())
            }
            metas += Meta(nameBytes, crc, payload.size, spec.data.size, offset, method, flags)
        }

        val cdStart = out.size()
        for (meta in metas) {
            out.writeU32(0x02014b50L)
            out.writeU16(20) // version made by
            out.writeU16(20) // version needed
            out.writeU16(meta.flags)
            out.writeU16(meta.method)
            out.writeU16(0)
            out.writeU16(0)
            out.writeU32(meta.crc)
            out.writeU32(meta.compressedSize.toLong())
            out.writeU32(meta.size.toLong())
            out.writeU16(meta.nameBytes.size)
            out.writeU16(0) // extra
            out.writeU16(0) // comment
            out.writeU16(0) // disk start
            out.writeU16(0) // internal attrs
            out.writeU32(0L) // external attrs
            out.writeU32(meta.localOffset.toLong())
            out.write(meta.nameBytes)
        }
        val cdSize = out.size() - cdStart

        out.writeU32(0x06054b50L)
        out.writeU16(0)
        out.writeU16(0)
        out.writeU16(metas.size)
        out.writeU16(metas.size)
        out.writeU32(cdSize.toLong())
        out.writeU32(cdStart.toLong())
        out.writeU16(0) // comment length
        return out.toByteArray()
    }

    private class Meta(
        val nameBytes: ByteArray,
        val crc: Long,
        val compressedSize: Int,
        val size: Int,
        val localOffset: Int,
        val method: Int,
        val flags: Int,
    )

    private fun flagsOf(spec: Spec): Int {
        var flags = 0
        if (spec.utf8Flag) flags = flags or 0x0800
        if (spec.encrypted) flags = flags or 0x0001
        if (spec.dataDescriptor) flags = flags or 0x0008
        return flags
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val buffer = ByteArray(raw.size + 64)
            val n = deflater.deflate(buffer)
            buffer.copyOf(n)
        } finally {
            deflater.end()
        }
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write((value and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 24) and 0xFF).toInt())
    }
}
