package com.muxiao.iconpacker.core.packer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ArtifactInspector] 纯 JVM 单测：用临时 ZIP 复刻"健康产物"与"缺东西的产物"。
 * 不依赖 Android 工程——检查逻辑本身只读 ZIP 字节。
 */
class ArtifactInspectorTest {

    @Test
    fun `healthy artifact passes all checks`() {
        val apk = tempZip(
            "AndroidManifest.xml" to byteArrayOf(1, 2, 3),
            "resources.arsc" to byteArrayOf(4, 5, 6),
            // AXML 首字节 0x03（RES_XML_TYPE）
            "res/xml/appfilter.xml" to byteArrayOf(0x03, 0x00, 8, 0) + ByteArray(16),
            "res/drawable-xxxhdpi-v4/icon1.webp" to ByteArray(10),
            "res/drawable-xxhdpi-v4/icon1.webp" to ByteArray(10),
        )

        val report = ArtifactInspector.inspect(apk)

        assertEquals(ArtifactInspector.CheckId.entries.size, report.size)
        report.forEach { check ->
            assertTrue("期望 ${check.id} 通过（detail=${check.detail}）", check.passed)
        }
        // 图标条目计数：只统计带密度目录里的位图，两个密度各 1 张
        assertEquals(
            "2",
            report.first { it.id == ArtifactInspector.CheckId.ICON_DENSITY }.detail,
        )
    }

    @Test
    fun `missing pieces fail the corresponding checks`() {
        val apk = tempZip(
            // appfilter 用明文 XML 也应判定"格式可读"（合法头兼容 '<'）
            "AndroidManifest.xml" to byteArrayOf(1),
            "res/xml/appfilter.xml" to "<appfilter/>".toByteArray(),
            // 图标放 anydpi（无密度）：必须判失败
            "res/drawable-anydpi/icon.webp" to ByteArray(4),
        )

        val report = ArtifactInspector.inspect(apk).associateBy { it.id }

        assertTrue(report.getValue(ArtifactInspector.CheckId.MANIFEST_PRESENT).passed)
        assertTrue(report.getValue(ArtifactInspector.CheckId.APPFILTER_VALID).passed)
        assertFalse(report.getValue(ArtifactInspector.CheckId.ARSC_PRESENT).passed)
        assertFalse(report.getValue(ArtifactInspector.CheckId.ICON_DENSITY).passed)
    }

    @Test
    fun `unreadable file fails everything with a reason`() {
        val report = ArtifactInspector.inspect(File(tempDir(), "not-exist.apk"))

        assertEquals(ArtifactInspector.CheckId.entries.size, report.size)
        report.forEach { check ->
            assertFalse(check.passed)
            assertTrue(check.detail.isNotBlank())
        }
    }

    // ---- helpers ----

    private fun tempZip(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("inspector", ".apk", tempDir())
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"))
}
