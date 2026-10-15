package com.muxiao.iconpacker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LauncherCompat.classifyLaunchers] 纯函数单测（JVM，无 Android 依赖）。
 * 验证：自身图标包被排除、已知包正确分类、当前启动器标记、按标签排序。
 */
class LauncherCompatTest {

    private fun r(pkg: String, label: String = pkg) = LauncherCompat.ResolvedLauncher(pkg, label)

    @Test
    fun `own generated pack package is excluded from launcher list`() {
        val raw = listOf(
            r("com.coloros.launcher", "ColorOS 启动器"),
            r(LauncherSupport.OWN_PACKAGE, "我的图标包"),
            r("com.teslacoilsw.launcher", "Nova Launcher"),
        )
        val result = LauncherCompat.classifyLaunchers(raw, "com.coloros.launcher")
        assertEquals(
            setOf("com.coloros.launcher", "com.teslacoilsw.launcher"),
            result.map { it.packageName }.toSet(),
        )
    }

    @Test
    fun `known packages are classified and current is flagged`() {
        val raw = listOf(
            r("com.coloros.launcher", "ColorOS 启动器"),
            r("com.teslacoilsw.launcher", "Nova Launcher"),
            r("com.miui.home", "MIUI 启动器"),
        )
        val result = LauncherCompat.classifyLaunchers(raw, "com.coloros.launcher")
        val coloros = result.first { it.packageName == "com.coloros.launcher" }
        assertTrue(coloros.isCurrent)
        assertEquals(LauncherSupport.SupportLevel.LIMITED, coloros.supportLevel)
        assertTrue(coloros.isKnown)

        val miui = result.first { it.packageName == "com.miui.home" }
        assertFalse(miui.isCurrent)
        assertEquals(LauncherSupport.SupportLevel.LIMITED, miui.supportLevel)

        val nova = result.first { it.packageName == "com.teslacoilsw.launcher" }
        assertEquals(LauncherSupport.SupportLevel.SUPPORTED, nova.supportLevel)
    }

    @Test
    fun `unknown package gets unknown level and is not known`() {
        val raw = listOf(r("com.some.unknown.launcher", "Weird Launcher"))
        val result = LauncherCompat.classifyLaunchers(raw, "com.some.unknown.launcher")
        assertEquals(1, result.size)
        assertEquals(LauncherSupport.SupportLevel.UNKNOWN, result[0].supportLevel)
        assertFalse(result[0].isKnown)
        assertTrue(result[0].isCurrent)
    }

    @Test
    fun `result is sorted by label`() {
        // 用未知包，避免 label 被已知包的 displayName 覆盖，纯粹验证排序语义
        val raw = listOf(
            r("com.z.unknown", "Banana"),
            r("com.y.unknown", "Apple"),
            r("com.x.unknown", "Cherry"),
        )
        val result = LauncherCompat.classifyLaunchers(raw, null)
        assertEquals(
            listOf("Apple", "Banana", "Cherry"),
            result.map { it.label },
        )
    }
}
