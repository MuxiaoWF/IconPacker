package com.muxiao.iconpacker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [pruneSelection] 纯函数单测。
 *
 * 重点守住两类历史踩过的坑：
 * 1. `pack-v9` vs `pack-v10` 的字典序陷阱（逐字符比较 9 > 1）；
 * 2. 解析不出版本号的文件名——兜底 0，必须排在最后被优先清掉。
 */
class PruneSelectionTest {

    private fun info(version: Long, name: String = "pack-v$version.apk") =
        ArtifactInfo(version, File(tempDir(), name))

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"))

    @Test
    fun `keeps newest N by numeric version`() {
        // 故意乱序 + 含 v10：字符串排序会把 v10 排在 v9 前面，数字排序不该
        val artifacts = listOf(
            info(9),
            info(10),
            info(2),
            info(7),
        )
        // 待删除列表保持"新 → 旧"的排序：先删较新的旧版本，行为可预测
        val dropped = pruneSelection(artifacts, keep = 2).map { it.versionCode }

        assertEquals(listOf(7L, 2L), dropped)
    }

    @Test
    fun `keep larger than size drops nothing`() {
        val artifacts = listOf(info(1), info(2))
        assertTrue(pruneSelection(artifacts, keep = 10).isEmpty())
    }

    @Test
    fun `keep below one still keeps the newest`() {
        val artifacts = listOf(info(3), info(1), info(2))
        val dropped = pruneSelection(artifacts, keep = 0).map { it.versionCode }

        assertEquals(listOf(2L, 1L), dropped)
    }

    @Test
    fun `unparseable names sort last and are pruned first`() {
        val junk = File(tempDir(), "pack-vlegacy.apk")
        val artifacts = listOf(ArtifactInfo(5, File(tempDir(), "pack-v5.apk")), ArtifactInfo(0, junk))

        val dropped = pruneSelection(artifacts, keep = 1)

        assertEquals(listOf(junk), dropped.map { it.file })
    }

    @Test
    fun `empty input is a no-op`() {
        assertTrue(pruneSelection(emptyList(), keep = 1).isEmpty())
    }
}
