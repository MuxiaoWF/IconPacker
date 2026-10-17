package com.muxiao.iconpacker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [AppMatcher] 单元测试：包名维度、评分规则、贪心分配、确定性与边界 */
class AppMatcherTest {

    private fun candidate(slot: Int, name: String) = AppMatcher.Candidate(slot, name)

    private fun entry(component: String, label: String, packageName: String) =
        AppMatcher.AppEntryForMatch(component, label, packageName)

    // ---- 包名维度（新增） ----

    @Test
    fun `package exact match scores highest`() {
        assertEquals(110, AppMatcher.score("设置", "com.android.settings", "com.android.settings.png"))
        assertEquals(110, AppMatcher.score("Chrome", "com.android.chrome", "com.android.chrome.webp"))
    }

    @Test
    fun `package tail match beats label match`() {
        // 标签维度只有 100，包名尾段 95——但包名整体不相等时尾段优先于"包含"之类的标签分
        assertEquals(95, AppMatcher.score("系统设置", "com.android.settings", "settings.png"))
    }

    @Test
    fun `package match wins over label match`() {
        // "Chrome" 标签与 "com.android.chrome.png"：标签包含匹配 80，包名精确 110
        assertEquals(110, AppMatcher.score("Chrome", "com.android.chrome", "com.android.chrome.png"))
    }

    @Test
    fun `package match fixes chinese label case`() {
        // 标签匹配的盲区：中文标签 + 包名命名文件
        assertEquals(0, AppMatcher.score("设置", "", "com.android.settings.png"))
        assertEquals(110, AppMatcher.score("设置", "com.android.settings", "com.android.settings.png"))
    }

    @Test
    fun `empty package name falls back to label`() {
        assertEquals(100, AppMatcher.score("Chrome", "", "chrome.png"))
        assertEquals(80, AppMatcher.score("Camera", "", "camera_icon.png"))
    }

    // ---- 标签维度（回归） ----

    @Test
    fun `exact match after normalization`() {
        assertEquals(100, AppMatcher.score("Chrome", "", "chrome.png"))
        assertEquals(100, AppMatcher.score("Google Chrome", "", "google_chrome.webp"))
        assertEquals(100, AppMatcher.score("Settings", "", "SETTINGS"))
    }

    @Test
    fun `containment match`() {
        assertEquals(80, AppMatcher.score("Camera", "", "camera_icon.png"))
        assertEquals(80, AppMatcher.score("Google Chrome", "", "chrome"))
        // 过短的包含不计数（避免 "a" 命中一切）
        assertEquals(0, AppMatcher.score("ab", "", "abc"))
    }

    @Test
    fun `initials match`() {
        assertEquals(70, AppMatcher.score("Google Chrome", "", "gc_chrome.png"))
        assertEquals(70, AppMatcher.score("Google Photos", "", "gp"))
        // 单元首字母不匹配
        assertEquals(0, AppMatcher.score("Google Chrome", "", "xy"))
    }

    @Test
    fun `token overlap match`() {
        assertEquals(60, AppMatcher.score("Chrome Browser", "", "browser_icon.png"))
        // 停用词不参与：ic / icon / app 等
        assertEquals(0, AppMatcher.score("Chrome", "", "ic_icon_app"))
    }

    @Test
    fun `below threshold returns null`() {
        assertNull(AppMatcher.bestMatch("微信", "", listOf(candidate(1, "settings"))))
    }

    @Test
    fun `best match picks highest score`() {
        val best = AppMatcher.bestMatch(
            "Chrome",
            "com.android.chrome",
            listOf(candidate(1, "chromium.png"), candidate(2, "chrome.png")),
        )
        assertEquals(2, best?.slot)
    }

    // ---- 批量分配 ----

    @Test
    fun `matchAll assigns uniquely and greedily`() {
        val entries = listOf(
            entry("ComponentInfo{a/.A}", "Chrome", "com.android.chrome"),
            entry("ComponentInfo{b/.B}", "设置", "com.android.settings"),
            entry("ComponentInfo{c/.C}", "Gmail", "com.google.android.gm"),
        )
        val candidates = listOf(
            candidate(1, "com.android.chrome.png"),
            candidate(2, "com.android.settings.png"),
            candidate(3, "com.google.android.gm.png"),
            candidate(4, "random_stuff.png"),
        )
        val result = AppMatcher.matchAll(entries, candidates)
        assertEquals(
            mapOf(
                "ComponentInfo{a/.A}" to 1,
                "ComponentInfo{b/.B}" to 2,
                "ComponentInfo{c/.C}" to 3,
            ),
            result,
        )
    }

    @Test
    fun `matchAll never assigns one icon twice`() {
        val entries = listOf(
            entry("ComponentInfo{a/.A}", "Chrome", "com.android.chrome"),
            entry("ComponentInfo{b/.B}", "Chrome Browser", "com.chrome.beta"),
        )
        val candidates = listOf(candidate(1, "chrome.png"))
        val result = AppMatcher.matchAll(entries, candidates)
        assertEquals(1, result.size)
    }

    @Test
    fun `matchAll skips manually mapped components`() {
        val entries = listOf(
            entry("ComponentInfo{a/.A}", "Chrome", "com.android.chrome"),
            entry("ComponentInfo{b/.B}", "Settings", "com.android.settings"),
        )
        val candidates = listOf(candidate(1, "chrome.png"), candidate(2, "settings.png"))
        val result = AppMatcher.matchAll(entries, candidates, skipComponents = setOf("ComponentInfo{a/.A}"))
        assertEquals(mapOf("ComponentInfo{b/.B}" to 2), result)
    }

    @Test
    fun `matchAll is deterministic`() {
        val entries = listOf(
            entry("ComponentInfo{a/.A}", "Chrome", "com.android.chrome"),
            entry("ComponentInfo{b/.B}", "Chrome Beta", "com.chrome.beta"),
        )
        val candidates = listOf(candidate(1, "chrome.png"), candidate(2, "chrome_beta.png"))
        val first = AppMatcher.matchAll(entries, candidates)
        val second = AppMatcher.matchAll(entries, candidates)
        assertEquals(first, second)
    }

    // ---- 边界 ----

    @Test
    fun `chinese labels match via package name`() {
        // 包名维度填补了拼音盲区：相机 ↔ com.android.camera ↔ camera.png
        assertEquals(95, AppMatcher.score("相机", "com.android.camera", "camera.png"))
        // 无包名时仍是盲区（文件名与标签既不同种也无派生关系）
        assertEquals(0, AppMatcher.score("相机", "", "camera.png"))
        val result = AppMatcher.matchAll(
            listOf(entry("ComponentInfo{x/.X}", "相机", "com.android.camera")),
            listOf(candidate(1, "camera.png")),
        )
        assertEquals(mapOf("ComponentInfo{x/.X}" to 1), result)
    }

    @Test
    fun `normalize strips extension and punctuation`() {
        assertEquals("googlechrome", AppMatcher.normalize("Google-Chrome.png"))
        assertEquals("googlechromev2", AppMatcher.normalize("Google-Chrome.v2.png"))
        assertEquals("", AppMatcher.normalize("..."))
        assertEquals("微信", AppMatcher.normalize("微信"))
    }
}
