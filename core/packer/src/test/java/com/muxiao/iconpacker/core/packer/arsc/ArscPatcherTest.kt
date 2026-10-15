package com.muxiao.iconpacker.core.packer.arsc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArscPatcherTest {

    /** 构造最小合法 arsc：ResTable 头 + 一个包 chunk（id 0x7F，名字 [name]） */
    private fun fakeArsc(name: String, trailingBytes: Int = 0): ByteArray {
        val pkgHeader = 288 // type 2 + headerSize 2 + id 4 + name 256 + lastPublicType 4 + lastKey 4 + ... 取标准头
        val table = ByteBuffer.allocate(12 + pkgHeader + trailingBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        table.putShort(0x0002)                    // RES_TABLE_TYPE
        table.putShort(12)                        // headerSize
        table.putInt(12 + pkgHeader + trailingBytes) // 整表 size
        table.putInt(1)                           // packageCount（ResTable 头共 12 字节）
        table.putShort(0x0200)                    // RES_TABLE_PACKAGE_TYPE
        table.putShort(pkgHeader.toShort())       // headerSize
        table.putInt(pkgHeader)                   // chunk size
        table.putInt(0x7F)                        // package id
        val nameField = ByteArray(256)
        val utf16 = name.toByteArray(Charsets.UTF_16LE)
        utf16.copyInto(nameField)
        table.put(nameField)
        table.putInt(0) // lastPublicType
        table.putInt(0) // lastKeyType
        return table.array()
    }

    @Test
    fun `package name is rewritten in place`() {
        val arsc = fakeArsc("com.muxiao.icon")
        val out = ArscPatcher.setPackageName(arsc, "com.muxiao.icon.p4")
        assertEquals("com.muxiao.icon.p4", ArscPatcher.packageName(out))
        // 原位改写：长度不变（字段定长）
        assertEquals(arsc.size, out.size)
    }

    @Test
    fun `longer package name still fits the fixed field`() {
        val arsc = fakeArsc("com.muxiao.icon")
        val longer = "com.muxiao.icon.pack.that.is.much.longer.than.original.p123"
        ArscPatcher.setPackageName(arsc, longer)
        assertEquals(longer, ArscPatcher.packageName(arsc))
    }

    @Test
    fun `same package name returns arsc unchanged`() {
        val arsc = fakeArsc("com.muxiao.icon")
        val out = ArscPatcher.setPackageName(arsc, "com.muxiao.icon")
        assertEquals("com.muxiao.icon", ArscPatcher.packageName(out))
    }

    @Test
    fun `other chunks after the package chunk do not confuse the scan`() {
        // 包 chunk 之后有额外字节（真实 arsc 里是 type/key 池等子 chunk）
        val arsc = fakeArsc("com.muxiao.icon", trailingBytes = 64)
        ArscPatcher.setPackageName(arsc, "com.demo.p1")
        assertEquals("com.demo.p1", ArscPatcher.packageName(arsc))
    }

    @Test
    fun `missing package chunk fails loudly`() {
        val junk = ByteArray(64)
        assertThrows(IllegalStateException::class.java) {
            ArscPatcher.setPackageName(junk, "com.demo.p1")
        }
        assertThrows(IllegalStateException::class.java) {
            ArscPatcher.packageName(junk)
        }
    }

    @Test
    fun `invalid arguments fail loudly`() {
        val arsc = fakeArsc("com.muxiao.icon")
        assertThrows(IllegalArgumentException::class.java) {
            ArscPatcher.setPackageName(arsc, "")
        }
        // 定长字段 256 字节 = 128 char16，减去结尾 NUL 最多 127 字符
        assertThrows(IllegalArgumentException::class.java) {
            ArscPatcher.setPackageName(arsc, "a".repeat(128) + ".p1")
        }
    }
}
