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
}
