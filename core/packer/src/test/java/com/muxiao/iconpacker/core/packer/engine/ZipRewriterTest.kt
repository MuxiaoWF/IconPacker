package com.muxiao.iconpacker.core.packer.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [ZipRewriter] 单元测试：替换、剥离、新增、确定性与回读 */
class ZipRewriterTest {

    private fun buildSource(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("MANIFEST".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("resources.arsc"))
            zos.write("ARSC".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/drawable-xxxhdpi-v4/ic_0001.png"))
            zos.write(ByteArray(8) { 1 })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/xml/appfilter.xml"))
            zos.write("OLD-APPFILTER".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("DEBUG-SIG".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/CERT.SF"))
            zos.write("DEBUG-SIG".toByteArray())
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `replaces existing entries and keeps the rest intact`() {
        val source = buildSource()
        val newIcon = byteArrayOf(9, 9, 9, 9)
        val out = ZipRewriter(source)
            .replace("res/drawable-xxxhdpi-v4/ic_0001.png", newIcon)
            .replace("res/xml/appfilter.xml", "NEW-APPFILTER".toByteArray())
            .build()

        assertArrayEquals(newIcon, ZipUtils.readEntry(out, "res/drawable-xxxhdpi-v4/ic_0001.png"))
        assertArrayEquals("NEW-APPFILTER".toByteArray(), ZipUtils.readEntry(out, "res/xml/appfilter.xml"))
        assertArrayEquals("MANIFEST".toByteArray(), ZipUtils.readEntry(out, "AndroidManifest.xml"))
        assertArrayEquals("ARSC".toByteArray(), ZipUtils.readEntry(out, "resources.arsc"))
    }

    @Test
    fun `drops meta-inf prefix entries`() {
        val out = ZipRewriter(buildSource())
            .dropPrefix("META-INF/")
            .build()
        assertFalse(ZipUtils.hasEntry(out, "META-INF/MANIFEST.MF"))
        assertFalse(ZipUtils.hasEntry(out, "META-INF/CERT.SF"))
        assertTrue(ZipUtils.hasEntry(out, "AndroidManifest.xml"))
    }

    @Test
    fun `appends entries not present in source`() {
        val out = ZipRewriter(buildSource())
            .replace("res/drawable-xxxhdpi-v4/ic_0042.png", "BRAND-NEW".toByteArray())
            .build()
        assertArrayEquals("BRAND-NEW".toByteArray(), ZipUtils.readEntry(out, "res/drawable-xxxhdpi-v4/ic_0042.png"))
    }

    @Test
    fun `build is deterministic and repeatable`() {
        val source = buildSource()
        val first = ZipRewriter(source)
            .replace("res/drawable-xxxhdpi-v4/ic_0001.png", byteArrayOf(7))
            .build()
        val second = ZipRewriter(source)
            .replace("res/drawable-xxxhdpi-v4/ic_0001.png", byteArrayOf(7))
            .build()
        assertArrayEquals(first, second)

        // 同一 rewriter 实例重复 build：替换表是配方，不得被消耗
        val rewriter = ZipRewriter(source).replace("res/drawable-xxxhdpi-v4/ic_0001.png", byteArrayOf(7))
        assertArrayEquals(rewriter.build(), rewriter.build())
    }

    @Test
    fun `entry order follows the source`() {
        val out = ZipRewriter(buildSource()).dropPrefix("META-INF/").build()
        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(out.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                e = zis.nextEntry
            }
        }
        assertEquals(
            listOf("AndroidManifest.xml", "resources.arsc", "res/drawable-xxxhdpi-v4/ic_0001.png", "res/xml/appfilter.xml"),
            names,
        )
    }

    @Test
    fun `directory entries pass through`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("res/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/a.txt"))
            zos.write("A".toByteArray())
            zos.closeEntry()
        }
        val rewritten = ZipRewriter(out.toByteArray()).build()
        assertNotNull(ZipUtils.readEntry(rewritten, "res/a.txt"))
    }

    @Test
    fun `drops zipflinger padding entries that only exist in the local stream`() {
        val out = ZipRewriter(zipflingerPaddedSource())
            .replace("res/drawable-xxxhdpi-v4/ic_0001.png", byteArrayOf(9, 9))
            .build()

        // 空名填充不得进入输出：照抄会让 ZipOutputStream 给它登记一条空名 CDE，
        // Android 运行时的 java.util.zip.ZipFile 对空名条目直接 NPE
        // （产物体检 "attempt to get length of null array" 的根因，见 build() 注释）
        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(out.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                e = zis.nextEntry
            }
        }
        assertEquals(
            listOf("res/drawable-xxxhdpi-v4/ic_0001.png", "res/xml/appfilter.xml"),
            names,
        )
        assertArrayEquals(byteArrayOf(9, 9), ZipUtils.readEntry(out, "res/drawable-xxxhdpi-v4/ic_0001.png"))
        assertArrayEquals("OLD-APPFILTER".toByteArray(), ZipUtils.readEntry(out, "res/xml/appfilter.xml"))
    }

    /**
     * 手工构造 zipflinger 风格的对齐填充源：两个正常条目之间插一个**只有本地头**的
     * 空名条目（nameLen=0，extra 即填充字节，中央目录里没有登记）。真实模板在 dex
     * 尺寸不凑巧时就会带这种填充（AGP 的 zipflinger 产物，实测踩过）。
     */
    private fun zipflingerPaddedSource(): ByteArray {
        // 与 aapt2/zipflinger 产物同形态：STORED + 本地头写明实际长度（无数据描述符）
        val plain = ByteArrayOutputStream()
        ZipOutputStream(plain).use { zos ->
            zos.putNextEntry(storedEntry("res/drawable-xxxhdpi-v4/ic_0001.png", ByteArray(8) { 1 }))
            zos.write(ByteArray(8) { 1 })
            zos.closeEntry()
            zos.putNextEntry(storedEntry("res/xml/appfilter.xml", "OLD-APPFILTER".toByteArray()))
            zos.write("OLD-APPFILTER".toByteArray())
            zos.closeEntry()
        }
        val bytes = plain.toByteArray()
        val locSig = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        var secondLoc = -1
        var found = 0
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == locSig[0] && bytes[i + 1] == locSig[1] &&
                bytes[i + 2] == locSig[2] && bytes[i + 3] == locSig[3]
            ) {
                found++
                if (found == 2) {
                    secondLoc = i
                    break
                }
            }
        }
        check(secondLoc > 0) { "source must contain two local headers" }
        // 30 字节本地头（nameLen=0, extraLen=8）+ 8 字节全零填充
        val padding = byteArrayOf(
            0x50, 0x4B, 0x03, 0x04,
            0x0A, 0x00,             // version needed 1.0
            0x00, 0x00,             // flags
            0x00, 0x00,             // method STORED
            0x00, 0x00,             // time
            0x00, 0x00,             // date
            0x00, 0x00, 0x00, 0x00, // crc
            0x00, 0x00, 0x00, 0x00, // csize
            0x00, 0x00, 0x00, 0x00, // usize
            0x00, 0x00,             // nameLen
            0x08, 0x00,             // extraLen
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val spliced = ByteArrayOutputStream(bytes.size + padding.size)
        spliced.write(bytes, 0, secondLoc)
        spliced.write(padding)
        spliced.write(bytes, secondLoc, bytes.size - secondLoc)
        return spliced.toByteArray()
    }

    private fun storedEntry(name: String, data: ByteArray): ZipEntry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = data.size.toLong()
        compressedSize = data.size.toLong()
        crc = java.util.zip.CRC32().apply { update(data) }.value
    }
}
