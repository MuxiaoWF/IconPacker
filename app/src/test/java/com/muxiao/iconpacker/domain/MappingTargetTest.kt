package com.muxiao.iconpacker.domain

import com.muxiao.iconpacker.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 映射目标解析（FR-04「编辑映射包名」）。
 *
 * 这里锁的是两条容易事后反悔的规则：
 * 已安装应用一律以设备上的真实启动组件为准；纯包名遇到未安装的应用则明确拒绝，
 * 而不是替用户编一个活动类名。
 */
class MappingTargetTest {

    /** 设备上真实装的启动组件——注意是 Alias，与类名同名的猜法会落空 */
    private val deviceComponent = "ComponentInfo{com.foo.bar/com.foo.bar.MainActivityAlias}"
    private val installed = listOf(ImportBinder.AppItem(deviceComponent, "com.foo.bar"))

    private fun resolved(
        input: String,
        apps: List<ImportBinder.AppItem> = installed,
    ): MappingTarget.Resolution.Resolved =
        MappingTarget.resolve(input, apps) as MappingTarget.Resolution.Resolved

    private fun invalid(
        input: String,
        apps: List<ImportBinder.AppItem> = installed,
    ): MappingTarget.Resolution.Invalid =
        MappingTarget.resolve(input, apps) as MappingTarget.Resolution.Invalid

    @Test
    fun `pure package name resolves to the real launch component`() {
        val result = resolved("com.foo.bar")
        assertEquals(deviceComponent, result.component)
        assertEquals("com.foo.bar", result.packageName)
        assertTrue(result.installed)
    }

    @Test
    fun `component info wrapper and surrounding braces are stripped`() {
        assertEquals(deviceComponent, resolved("ComponentInfo{com.foo.bar}").component)
        assertEquals(deviceComponent, resolved("{com.foo.bar}").component)
        assertEquals(deviceComponent, resolved("  com.foo.bar  ").component)
    }

    @Test
    fun `typed activity is ignored when the app is installed`() {
        // 用户写的可能是过时的或别名之外的活动类；设备值才是唯一保证匹配得上的
        val result = resolved("com.foo.bar/com.foo.bar.LegacyActivity")
        assertEquals(deviceComponent, result.component)
        assertTrue(result.installed)
    }

    @Test
    fun `short activity form is qualified with the package name`() {
        val result = resolved("com.foo.bar/.MainActivity", apps = emptyList())
        assertEquals("ComponentInfo{com.foo.bar/com.foo.bar.MainActivity}", result.component)
        assertEquals("com.foo.bar", result.packageName)
        assertFalse("未安装的目标应标记为预置", result.installed)
    }

    @Test
    fun `activity without a dot is treated as a class under the package`() {
        assertEquals(
            "ComponentInfo{com.foo.bar/com.foo.bar.Main}",
            resolved("com.foo.bar/Main", apps = emptyList()).component,
        )
    }

    @Test
    fun `fully qualified activity is kept as is`() {
        assertEquals(
            "ComponentInfo{com.foo.bar/com.other.Alias}",
            resolved("com.foo.bar/com.other.Alias", apps = emptyList()).component,
        )
    }

    @Test
    fun `inner class activity with dollar sign is accepted`() {
        assertEquals(
            $$"ComponentInfo{com.foo.bar/com.foo.bar.Main$Entry}",
            resolved($$"com.foo.bar/com.foo.bar.Main$Entry", apps = emptyList()).component,
        )
    }

    @Test
    fun `uninstalled package without an activity is rejected instead of guessed`() {
        // 编一个 .MainActivity 出来只会在产物里留一条永远匹配不上的死条目
        val result = invalid("com.foo.bar", apps = emptyList())
        assertEquals(R.string.err_mapping_target_not_installed, result.messageRes)
        assertEquals(listOf("com.foo.bar"), result.args)
    }

    @Test
    fun `empty input is rejected`() {
        assertEquals(R.string.err_mapping_target_empty, invalid("   ").messageRes)
    }

    @Test
    fun `illegal package name is rejected with the rule message`() {
        assertEquals(R.string.err_pkg_invalid_chars, invalid("com.foo-bar").messageRes)
        assertEquals(R.string.err_pkg_too_few_segments, invalid("com").messageRes)
    }

    @Test
    fun `illegal activity name is rejected`() {
        assertEquals(
            R.string.err_activity_invalid,
            invalid("com.foo.bar/com.foo.1Bad", apps = emptyList()).messageRes,
        )
    }

    @Test
    fun `packageOf extracts the package for display`() {
        assertEquals("com.foo.bar", MappingTarget.packageOf(deviceComponent))
        assertEquals("com.foo.bar", MappingTarget.packageOf("com.foo.bar/.Main"))
        assertEquals("com.foo.bar", MappingTarget.packageOf("com.foo.bar"))
    }
}
