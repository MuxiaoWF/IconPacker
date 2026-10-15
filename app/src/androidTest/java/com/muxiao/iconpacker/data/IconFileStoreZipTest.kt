package com.muxiao.iconpacker.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.iconpacker.core.packer.axml.AxmlAttr
import com.muxiao.iconpacker.core.packer.axml.AxmlElement
import com.muxiao.iconpacker.core.packer.axml.AxmlWriter
import com.muxiao.iconpacker.core.packer.axml.AttrValue
import com.muxiao.iconpacker.core.packer.engine.IconPackManifest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP 解包链路的 instrumentation 测试。
 *
 * 为什么必须跑在设备上：[IconFileStore.extractZipImages] 依赖 `BitmapFactory`
 * （压缩）与真实文件系统（去重后删副本），JVM 单测覆盖不到——而"多密度副本没删干净"
 * 恰好就是上一轮抓到的磁盘泄漏形态。纯逻辑部分（去重规则、appfilter 解析、两层绑定）
 * 已由 `ZipIconPlannerTest` / `IconPackManifestTest` / `ImportBinderTest` 覆盖。
 *
 * 方法名刻意不用反引号中文（本工程单测的惯例）：反引号名会生成含空格的内部类名，
 * 而 instrumentation 的 DEX 版本不接受空格，构建立刻失败。
 */
@RunWith(AndroidJUnit4::class)
class IconFileStoreZipTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = IconFileStore(context)

    /** 用一个远离真实项目编号的 packId，避免碰到用户数据 */
    private val packId = 987654L

    private val iconsDir: File get() = File(context.filesDir, "icons/$packId")

    @After
    fun tearDown() {
        iconsDir.deleteRecursively()
    }

    @Test
    fun iconPackZipKeepsPathsDedupesDensitiesAndCapturesAppFilter() {
        val zip = writeZip(
            buildMap {
                for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
                    put("res/drawable-$density/wechat.png", solidPng())
                }
                put("com.android.chrome/icon.png", solidPng())
                put("resources.arsc", ByteArray(16))
                put("classes.dex", ByteArray(16))
                put("AndroidManifest.xml", ByteArray(16))
                put("res/xml/appfilter.xml", PLAIN_APP_FILTER.toByteArray())
            },
        )

        val extraction = runBlocking { store.extractZipImages(packId, zip) }

        // 5 个密度副本 + 1 张按包名分目录的图 → 2 张；4 个副本被合并
        assertEquals(2, extraction.items.size)
        assertEquals(4, extraction.duplicates)
        // resources.arsc / classes.dex / AndroidManifest.xml 计入跳过（FR-03 验收 1）
        assertEquals(3, extraction.skipped)

        // 原始条目路径必须保留——它是"目录名即包名"这一路的唯一来源
        assertTrue(extraction.items.any { it.entryPath == "com.android.chrome/icon.png" })
        assertTrue(extraction.items.any { it.entryPath == "res/drawable-xxxhdpi/wechat.png" })
        assertEquals(
            listOf("com.android.chrome", "wechat"),
            extraction.items.map { it.matchKey }.sorted(),
        )

        // 包内 appfilter 被取出且可解析
        assertEquals("res/xml/appfilter.xml", extraction.appFilterPath)
        val bytes = extraction.appFilterBytes
        assertNotNull(bytes)
        assertEquals("wechat", IconPackManifest.parse(bytes!!).entries.single().drawable)

        // 去重掉的那批文件必须真的从磁盘删掉：否则就是"导入 6 张留下 6 个文件"的泄漏
        val onDisk = iconsDir.listFiles()!!.filter { it.isFile }
        assertEquals(2, onDisk.size)
        assertTrue(onDisk.all { it.length() > 0 })

        zip.delete()
    }

    /** 二进制 AXML 形态的 appfilter（真机导入的第三方包就是这种） */
    @Test
    fun binaryAxmlAppFilterInsideZipIsCaptured() {
        val appFilter = AxmlWriter().write(
            AxmlElement(
                name = "resources",
                children = listOf(
                    AxmlElement(
                        name = "item",
                        attributes = listOf(
                            AxmlAttr(
                                "component",
                                AttrValue.Str("ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}"),
                            ),
                            AxmlAttr("drawable", AttrValue.Str("wechat")),
                        ),
                    ),
                ),
            ),
        )
        val zip = writeZip(
            mapOf(
                "res/drawable-xxxhdpi/wechat.png" to solidPng(),
                "res/xml/appfilter.xml" to appFilter,
            ),
        )

        val extraction = runBlocking { store.extractZipImages(packId, zip) }

        assertEquals(1, extraction.items.size)
        assertEquals("wechat", extraction.items.single().matchKey)
        val bytes = extraction.appFilterBytes
        assertNotNull(bytes)
        val parsed = IconPackManifest.parse(bytes!!)
        assertEquals(IconPackManifest.Format.BINARY_AXML, parsed.format)
        assertEquals("wechat", parsed.entries.single().drawable)

        zip.delete()
    }

    /** 非图标包 ZIP（只有散图）：仍能导入，且不留下任何残留文件 */
    @Test
    fun plainImageZipImportsWithoutLeftovers() {
        val zip = writeZip(
            mapOf(
                "icons/chrome.png" to solidPng(),
                "icons/wechat.png" to solidPng(),
                "notes.txt" to "not an image".toByteArray(),
            ),
        )

        val extraction = runBlocking { store.extractZipImages(packId, zip) }

        assertEquals(2, extraction.items.size)
        assertEquals(0, extraction.duplicates)
        assertEquals(1, extraction.skipped)
        assertNull(extraction.appFilterPath)
        assertEquals(2, iconsDir.listFiles()!!.count { it.isFile })

        zip.delete()
    }

    // ---- 辅助 ----

    private fun writeZip(entries: Map<String, ByteArray>): File {
        val zip = File(context.cacheDir, "pack-under-test.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return zip
    }

    /** 生成一张真实的小 PNG：解包链路会走 BitmapFactory 判定是否需要压缩 */
    private fun solidPng(size: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        val PLAIN_APP_FILTER = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <item component="ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}" drawable="wechat"/>
                <item drawable="ignored-without-component"/>
            </resources>
        """.trimIndent()
    }
}
