package com.muxiao.iconpacker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LauncherSupport] 纯逻辑单测（JVM，无 Android 依赖）。
 * 重点覆盖 ColorOS 误判修复、第三方启动器、OEM 启动器与未知包降级。
 */
class LauncherSupportTest {

    @Test
    fun `ColorOS launcher is limited with guidance`() {
        // ColorOS 14（CN）实测不支持第三方图标包 APK（ART+/主题商店体系），
        // 曾据更新日志标 SUPPORTED，已降级——防止凭印象改回去。
        val profile = LauncherSupport.classify("com.coloros.launcher")
        assertEquals(LauncherSupport.SupportLevel.LIMITED, profile?.supportLevel)
        assertTrue(profile?.guidance?.isNotEmpty() == true)
    }

    @Test
    fun `OPPO launcher is limited`() {
        assertEquals(
            LauncherSupport.SupportLevel.LIMITED,
            LauncherSupport.classify("com.oppo.launcher")?.supportLevel,
        )
    }

    @Test
    fun `generic android launcher is limited not unsupported`() {
        // 用户提到的 com.android.launcher：应为 LIMITED（不阻断），而非 UNKNOWN/报错
        val level = LauncherSupport.supportLevelFor("com.android.launcher")
        assertEquals(LauncherSupport.SupportLevel.LIMITED, level)
        assertFalse(level == LauncherSupport.SupportLevel.UNKNOWN)
    }

    @Test
    fun `One UI and MIUI are limited`() {
        assertEquals(
            LauncherSupport.SupportLevel.LIMITED,
            LauncherSupport.classify("com.sec.android.app.launcher")?.supportLevel,
        )
        assertEquals(
            LauncherSupport.SupportLevel.LIMITED,
            LauncherSupport.classify("com.miui.home")?.supportLevel,
        )
    }

    @Test
    fun `Nova launcher is supported without guidance`() {
        val profile = LauncherSupport.classify("com.teslacoilsw.launcher")
        assertEquals(LauncherSupport.SupportLevel.SUPPORTED, profile?.supportLevel)
        assertNull(profile?.guidance)
    }

    @Test
    fun `unknown package falls back to unknown`() {
        assertEquals(LauncherSupport.SupportLevel.UNKNOWN, LauncherSupport.supportLevelFor("com.some.unknown.launcher"))
        assertEquals(LauncherSupport.SupportLevel.UNKNOWN, LauncherSupport.supportLevelFor(null))
    }

    @Test
    fun `own generated pack package is excluded from launcher classification`() {
        assertTrue(LauncherSupport.isOwnPackage("com.muxiao.icon"))
        assertFalse(LauncherSupport.isOwnPackage("com.coloros.launcher"))
        // 自身包不应出现在已知启动器画像中
        assertNull(LauncherSupport.classify("com.muxiao.icon"))
    }

    @Test
    fun `known third party launchers are all supported`() {
        val supported = listOf(
            "app.lawnchair",
            "com.anddoes.launcher",
            "com.gau.go.launcherex",
            "org.adw.launcher",
            "ginlemon.smartlauncher",
            "de.mm20.launcher",
        )
        supported.forEach { pkg ->
            assertEquals(
                "期望 $pkg 为 SUPPORTED",
                LauncherSupport.SupportLevel.SUPPORTED,
                LauncherSupport.classify(pkg)?.supportLevel,
            )
        }
    }
}
