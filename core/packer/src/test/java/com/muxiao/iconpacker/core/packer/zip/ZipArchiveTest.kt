package com.muxiao.iconpacker.core.packer.zip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/**
 * 自写 ZIP 读取层的回归。
 *
 * 核心回归是这个真实故障：中文 Windows 压出来的包用 GBK 写条目名且不置 UTF-8 标志，
 * Android 的 `ZipInputStream` 一律按 UTF-8 解，在第 2 个条目就抛 `MALFORMED[1]`，
 * 整个包导不进来。
 */
class ZipArchiveTest {

    private val gb18030: Charset = Charset.forName("GB18030")

    private fun spec(
        name: String,
        data: ByteArray = ByteArray(0),
        charset: Charset = Charsets.UTF_8,
        utf8Flag: Boolean = true,
        deflate: Boolean = false,
        encrypted: Boolean = false,
        dataDescriptor: Boolean = false,
    ) = ZipTestBuilder.Spec(name, data, deflate, utf8Flag, encrypted, dataDescriptor, charset)

    private fun tempZip(bytes: ByteArray): File =
        File.createTempFile("iconpacker-zip-", ".zip").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    private fun open(vararg specs: ZipTestBuilder.Spec): ZipArchive =
        ZipArchive.open(tempZip(ZipTestBuilder.build(specs.toList())))

    // ---- 编码 ----

    /**
     * 必须整包判定：`BOSS直聘` 的 GBK 字节 `D6 B1 C6 B8` 恰好也是一段合法 UTF-8
     * （解出来是 `ֱƸ`），逐条"先试 UTF-8"会得到看着像字、其实全错的名字。
     */
    @Test
    fun `gbk 命名的包整包按旧式编码解码`() {
        val payload = "hello".toByteArray()
        open(
            spec("a.png", payload),
            spec("123云盘.png", payload, gb18030, utf8Flag = false),
            spec("BOSS直聘.png", payload, gb18030, utf8Flag = false),
        ).use { archive ->
            assertEquals(
                listOf("a.png", "123云盘.png", "BOSS直聘.png"),
                archive.entries.map { it.name },
            )
            assertEquals("hello", String(archive.readBytes(archive.entries.last(), 1024)))
        }
    }

    @Test
    fun `置了 utf8 标志的名字按 utf8 解`() {
        open(spec("图标.png", "x".toByteArray())).use { archive ->
            assertEquals("图标.png", archive.entries.single().name)
        }
    }

    @Test
    fun `未置标志但字节合法 utf8 时仍按 utf8 解`() {
        open(spec("图标.png", "x".toByteArray(), utf8Flag = false)).use { archive ->
            assertEquals("图标.png", archive.entries.single().name)
        }
    }

    // ---- 数据 ----

    @Test
    fun `store 与 deflate 都能读回原数据`() {
        val raw = ByteArray(10_000) { (it % 251).toByte() }
        open(
            spec("stored.bin", raw),
            spec("deflated.bin", raw, deflate = true),
        ).use { archive ->
            archive.entries.forEach { entry ->
                assertArrayEquals(raw, archive.readBytes(entry, raw.size))
            }
        }
    }

    /** 带数据描述符（bit 3）的条目：本地头里的长度是 0，只有中央目录是准的 */
    @Test
    fun `长度写在数据描述符里的条目也能读`() {
        val raw = "descriptor payload".toByteArray()
        open(spec("dd.txt", raw, deflate = true, dataDescriptor = true)).use { archive ->
            val entry = archive.entries.single()
            assertEquals(raw.size.toLong(), entry.size)
            assertArrayEquals(raw, archive.readBytes(entry, raw.size))
        }
    }

    @Test
    fun `readBytes 超过上限时截断而不是报错`() {
        val raw = ByteArray(8192) { 7 }
        open(spec("big.bin", raw)).use { archive ->
            assertEquals(100, archive.readBytes(archive.entries.single(), 100).size)
        }
    }

    // ---- 异常输入 ----

    @Test
    fun `加密条目标记为不可读且打开时抛错`() {
        open(spec("secret.png", "x".toByteArray(), encrypted = true)).use { archive ->
            val entry = archive.entries.single()
            assertFalse(entry.isReadable)
            assertThrows(ZipFormatException::class.java) { archive.open(entry) }
        }
    }

    @Test
    fun `不是 zip 的文件抛 ZipFormatException`() {
        val file = tempZip(ByteArray(256) { 0x41 })
        assertThrows(ZipFormatException::class.java) { ZipArchive.open(file) }
    }

    @Test
    fun `被截断的 zip 抛 ZipFormatException 而不是返回半截数据`() {
        val whole = ZipTestBuilder.build(listOf(spec("a.png", ByteArray(4096) { 1 })))
        val truncated = tempZip(whole.copyOf(whole.size - 64))
        assertThrows(ZipFormatException::class.java) { ZipArchive.open(truncated) }
    }

    // ---- 其它结构 ----

    @Test
    fun `目录条目按结尾斜杠识别`() {
        open(
            spec("res/"),
            spec("res/xml/"),
            spec("res/xml/appfilter.xml", "<resources/>".toByteArray()),
        ).use { archive ->
            assertTrue(archive.entries[0].isDirectory)
            assertTrue(archive.entries[1].isDirectory)
            assertFalse(archive.entries[2].isDirectory)
            assertEquals(3, archive.entries.size)
        }
    }

    @Test
    fun `空归档没有条目`() {
        open().use { archive ->
            assertTrue(archive.entries.isEmpty())
        }
    }
}
