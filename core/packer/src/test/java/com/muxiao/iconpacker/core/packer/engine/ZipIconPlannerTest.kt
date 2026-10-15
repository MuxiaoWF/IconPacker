package com.muxiao.iconpacker.core.packer.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ZIP 条目规划的回归防线。
 *
 * 这里的每条断言都对应一个真机实测出来的问题：
 * - 多密度副本重复入池：3 个图标 × 5 个密度 = 17 个条目，去重后只剩 4 个，
 *  同一张图占 5 个槽位（上限 1024）、`originalName` 还完全相同；
 * - 目录名丢失：`com.tencent.mm/icon.png` 与 `com.android.settings/icon.png`
 *  双双变成 `icon`，两个不同 App 的图无法区分。
 */
class ZipIconPlannerTest {

    private fun entry(path: String, sizeBytes: Long = 1000) = ZipIconPlanner.Entry(path, sizeBytes)

    // ---- 多密度去重 ----

    @Test
    fun `density variants of one drawable collapse into a single entry`() {
        val plan = ZipIconPlanner.plan(
            listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
                .map { entry("res/drawable-$it/chrome.png") },
        )
        assertEquals(1, plan.kept.size)
        assertEquals(4, plan.duplicates)
        // 保留密度最高的那份
        assertEquals("res/drawable-xxxhdpi/chrome.png", plan.kept.single().path)
    }

    @Test
    fun `unqualified directory merges with the density-qualified ones`() {
        val plan = ZipIconPlanner.plan(
            listOf(entry("res/drawable/chrome.png"), entry("res/drawable-xhdpi/chrome.png")),
        )
        assertEquals(1, plan.kept.size)
        assertEquals("res/drawable-xhdpi/chrome.png", plan.kept.single().path)
    }

    @Test
    fun `anydpi and api-version qualifiers do not split a group`() {
        val plan = ZipIconPlanner.plan(
            listOf(
                entry("res/drawable/ic_launcher.png"),
                entry("res/drawable-anydpi-v26/ic_launcher.png"),
            ),
        )
        assertEquals(1, plan.kept.size)
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `equal density ties break by size then path, not by input order`() {
        val big = entry("res/drawable-xxhdpi/chrome.png", sizeBytes = 9000)
        val small = entry("res/drawable-xxhdpi/chrome.png", sizeBytes = 10)
        val plan = ZipIconPlanner.plan(listOf(big, small))
        assertEquals(1, plan.kept.size)
        assertEquals(9000L, plan.kept.single().sizeBytes)
    }

    /**
     * 最关键的一条：不同目录下的同名文件**绝不能**被合并。
     * 合并等于"两个 App 只剩一张图"，是静默的数据丢失。
     */
    @Test
    fun `same file name under different package directories is never merged`() {
        val plan = ZipIconPlanner.plan(
            listOf(entry("com.tencent.mm/icon.png"), entry("com.android.settings/icon.png")),
        )
        assertEquals(2, plan.kept.size)
        assertEquals(0, plan.duplicates)
        assertEquals(
            listOf("com.android.settings", "com.tencent.mm"),
            plan.kept.map { it.matchKey }.sorted(),
        )
    }

    @Test
    fun `plan does not depend on input order`() {
        val entries = listOf(
            entry("res/drawable-hdpi/x.png"),
            entry("res/drawable-xxxhdpi/x.png"),
            entry("res/drawable-mdpi/x.png"),
        )
        val forward = ZipIconPlanner.plan(entries).kept.map { it.path }
        val backward = ZipIconPlanner.plan(entries.reversed()).kept.map { it.path }
        assertEquals(forward, backward)
    }

    // ---- 匹配键推导 ----

    @Test
    fun `file name that is a package name wins`() {
        assertEquals(
            "com.android.chrome",
            ZipIconPlanner.matchKeyOf("res/drawable-xxxhdpi-v4/com.android.chrome.png"),
        )
    }

    @Test
    fun `package-like directory is used when the file name is generic`() {
        assertEquals("com.tencent.mm", ZipIconPlanner.matchKeyOf("com.tencent.mm/icon.png"))
    }

    @Test
    fun `meaningful directory is preferred over a generic file name`() {
        assertEquals("Chrome", ZipIconPlanner.matchKeyOf("Chrome/icon.png"))
    }

    @Test
    fun `falls back to the file name when nothing better exists`() {
        assertEquals("chrome", ZipIconPlanner.matchKeyOf("res/drawable-xxxhdpi-v4/chrome.png"))
        // 序号名没有语义，但也没有更好的来源——保持原样，交给模糊匹配或人工
        assertEquals("ic_0001", ZipIconPlanner.matchKeyOf("res/drawable-xxxhdpi-v4/ic_0001.png"))
    }

    @Test
    fun `underscore-separated package names are kept intact`() {
        assertEquals("com_android_chrome", ZipIconPlanner.matchKeyOf("icons/com_android_chrome.png"))
    }

    @Test
    fun `windows-style separators are normalized`() {
        assertEquals("com.tencent.mm", ZipIconPlanner.matchKeyOf("com.tencent.mm\\icon.png"))
        assertEquals(640, ZipIconPlanner.densityRank("res\\drawable-xxxhdpi\\x.png"))
    }

    // ---- 内部判据 ----

    @Test
    fun `density rank reflects the densest qualifier in the path`() {
        assertEquals(0, ZipIconPlanner.densityRank("chrome.png"))
        assertEquals(640, ZipIconPlanner.densityRank("res/drawable-xxxhdpi/chrome.png"))
        assertEquals(320, ZipIconPlanner.densityRank("res/drawable-en-xhdpi-v4/chrome.png"))
    }

    @Test
    fun `empty input yields an empty plan`() {
        val plan = ZipIconPlanner.plan(emptyList())
        assertEquals(0, plan.kept.size)
        assertEquals(0, plan.duplicates)
    }
}
