package com.muxiao.iconpacker.ui.packlist

import com.muxiao.iconpacker.data.PackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PackListUiState.visiblePacks]（搜索过滤 + 三种排序）纯函数单测。
 *
 * 列表是用户每天面对的主界面，排序口径错了很难被单测之外的方式发现——
 * 尤其"已安装优先"的次级排序（同组内按最近修改）。
 */
class PackListUiStateTest {

    private fun pack(
        id: Long,
        name: String,
        packageName: String = "com.muxiao.icon.p$id",
        updatedAt: Long,
    ) = PackEntity(
        id = id,
        name = name,
        packageName = packageName,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    // 与 DAO 一致：updatedAt DESC。RECENT 档沿用该顺序，不再二次排序
    private val packs = listOf(
        pack(2, "browser", packageName = "com.example.web", updatedAt = 300),
        pack(3, "日历", updatedAt = 200),
        pack(1, "Camera", updatedAt = 100),
    )

    private fun state(
        packs: List<PackEntity> = this.packs,
        installed: Map<String, Long> = emptyMap(),
        query: String = "",
        sort: PackSort = PackSort.RECENT,
    ) = PackListUiState(
        packs = packs,
        installedVersions = installed,
        query = query,
        sort = sort,
    )

    @Test
    fun `recent keeps dao order (updatedAt desc)`() {
        assertEquals(listOf(2L, 3L, 1L), state().visiblePacks.map { it.id })
    }

    @Test
    fun `name sort is case-insensitive and covers all entries`() {
        val sorted = state(sort = PackSort.NAME).visiblePacks.map { it.name }

        // "browser" < "Camera" < "日历"：小写 b 的小写比较值小于大写 C 的折算值
        assertEquals(listOf("browser", "Camera", "日历"), sorted)
    }

    @Test
    fun `installed sort groups installed first then by recency`() {
        // 1、3 已安装：同组内按 updatedAt 降序（3 在 1 前）；未安装的 2 垫底
        val sorted = state(
            installed = mapOf(
                "com.muxiao.icon.p1" to 1L,
                "com.muxiao.icon.p3" to 1L,
            ),
            sort = PackSort.INSTALLED,
        ).visiblePacks.map { it.id }

        assertEquals(listOf(3L, 1L, 2L), sorted)
    }

    @Test
    fun `query filters by name case-insensitively`() {
        val filtered = state(query = "cam").visiblePacks

        assertEquals(listOf(1L), filtered.map { it.id })
    }

    @Test
    fun `query also matches package name`() {
        val filtered = state(query = "com.example").visiblePacks

        assertEquals(listOf(2L), filtered.map { it.id })
    }

    @Test
    fun `blank query keeps everything`() {
        assertEquals(3, state(query = "   ").visiblePacks.size)
    }

    @Test
    fun `no match yields empty list`() {
        assertEquals(emptyList<PackEntity>(), state(query = "zzz").visiblePacks)
    }

    @Test
    fun `installedCount counts installed package names only`() {
        val s = state(installed = mapOf("com.muxiao.icon.p1" to 1L, "com.unknown" to 1L))

        assertEquals(1, s.installedCount)
    }

    @Test
    fun `canInstall and canUninstall follow their own sources`() {
        val s = state(installed = mapOf("com.muxiao.icon.p1" to 1L))
        val withArtifact = s.copy(installableIds = setOf(2L))

        assertEquals(true, s.canUninstall(s.packs.first { it.id == 1L }))
        assertEquals(false, s.canUninstall(s.packs.first { it.id == 2L }))
        assertEquals(true, withArtifact.canInstall(withArtifact.packs.first { it.id == 2L }))
        assertEquals(false, s.canInstall(s.packs.first { it.id == 2L }))
    }
}
