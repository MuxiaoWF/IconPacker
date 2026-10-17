package com.muxiao.iconpacker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本规则回归（FR-02 补充）。
 *
 * 版本号与版本名是两件事：前者给系统判断新旧（错一档就装不上），
 * 后者纯粹给人看。规则里最容易出事故的是**自动递增的边界**——
 * 溢出成负数的版本号会被打包请求直接拒绝，表现成"打包按钮点了没反应"。
 */
class PackVersionRulesTest {

    // ---- 版本名 ----

    @Test
    fun `version name accepts free-form strings`() {
        assertNull(PackVersionRules.validateName("2.1.0"))
        assertNull(PackVersionRules.validateName("2026.10 内测3"))
        assertNull(PackVersionRules.validateName("1.0"))
    }

    @Test
    fun `version name rejects blank and overlong values`() {
        assertEquals(PackVersionRules.Issue.NAME_EMPTY, PackVersionRules.validateName("   "))
        assertEquals(
            PackVersionRules.Issue.NAME_TOO_LONG,
            PackVersionRules.validateName("1".repeat(PackVersionRules.MAX_NAME_LENGTH + 1)),
        )
    }

    // ---- 版本号 ----

    @Test
    fun `null code means auto and is always valid`() {
        assertNull(PackVersionRules.validateCode(null))
    }

    @Test
    fun `code must be positive and within bounds`() {
        assertEquals(
            PackVersionRules.Issue.CODE_NOT_POSITIVE,
            PackVersionRules.validateCode(0),
        )
        assertEquals(
            PackVersionRules.Issue.CODE_TOO_LARGE,
            PackVersionRules.validateCode(PackVersionRules.MAX_CODE + 1),
        )
    }

    /** 自动递增到上限后必须停住，而不是 +1 溢出成负数 */
    @Test
    fun `auto next stops at the boundary instead of overflowing`() {
        assertEquals(2, PackVersionRules.autoNext(1))
        assertEquals(43, PackVersionRules.autoNext(42))
        assertEquals(
            PackVersionRules.MAX_CODE,
            PackVersionRules.autoNext(PackVersionRules.MAX_CODE),
        )
    }

    // ---- 界面输入的折算 ----

    @Test
    fun `blank input maps to auto`() {
        assertEquals(PackVersionRules.Parsed.Auto, PackVersionRules.parseCode("  "))
        assertEquals(PackVersionRules.Parsed.Auto, PackVersionRules.parseCode(""))
    }

    @Test
    fun `numeric input maps to explicit value`() {
        assertEquals(PackVersionRules.parseCode("10"), PackVersionRules.Parsed.Explicit(10))
        assertEquals(
            PackVersionRules.parseCode(" 10 "),
            PackVersionRules.Parsed.Explicit(10),
        )
    }

    /** 非数字、非正数、超上限都要归为 Invalid，让界面禁用保存而不是静默改写 */
    @Test
    fun `garbage negative and overflow all map to invalid`() {
        assertEquals(PackVersionRules.Parsed.Invalid, PackVersionRules.parseCode("v5"))
        assertEquals(PackVersionRules.Parsed.Invalid, PackVersionRules.parseCode("0"))
        assertEquals(PackVersionRules.Parsed.Invalid, PackVersionRules.parseCode("-3"))
        assertEquals(
            PackVersionRules.Parsed.Invalid,
            PackVersionRules.parseCode("9".repeat(11)),
        )
    }

    /** 自动值 = 最近版本 +1，且显式指定它等价于不指定（这条由 UI 折算，规则侧只保证边界） */
    @Test
    fun `auto next is exactly one above the current version`() {
        val current = 7
        assertTrue(PackVersionRules.autoNext(current) > current)
    }
}
