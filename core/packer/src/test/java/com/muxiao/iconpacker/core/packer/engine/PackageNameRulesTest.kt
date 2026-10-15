package com.muxiao.iconpacker.core.packer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 包名校验与派生规则。
 *
 * 这些规则是"多个图标包共存"的第一道闸门：包名一旦写进产物清单就与已安装应用绑定，
 * 改错会让用户装不上或覆盖掉别的包，而这在产物表面完全看不出来。
 */
class PackageNameRulesTest {

    @Test
    fun validNamesAreAccepted() {
        val names = listOf(
            "com.muxiao.icon.p2",
            "com.example.pack",
            "a.b",
            "com.a1.b_2",
            "_priv.segment",
            "com.example.a.b.c.d",
        )
        for (name in names) {
            assertNull("应判定为合法：$name", PackageNameRules.validate(name))
            assertTrue(PackageNameRules.isValid(name))
        }
    }

    @Test
    fun blankIsRejected() {
        assertEquals(PackageNameRules.Issue.EMPTY, PackageNameRules.validate(""))
        assertEquals(PackageNameRules.Issue.EMPTY, PackageNameRules.validate("   "))
    }

    /** Android 要求包名至少两段——单段名装得上但会被系统按怪例处理 */
    @Test
    fun singleSegmentIsRejected() {
        assertEquals(PackageNameRules.Issue.TOO_FEW_SEGMENTS, PackageNameRules.validate("pack"))
        assertEquals(PackageNameRules.Issue.TOO_FEW_SEGMENTS, PackageNameRules.validate("com"))
    }

    @Test
    fun emptySegmentIsRejected() {
        assertEquals(PackageNameRules.Issue.BLANK_SEGMENT, PackageNameRules.validate("com..a"))
        assertEquals(PackageNameRules.Issue.BLANK_SEGMENT, PackageNameRules.validate(".com.a"))
        assertEquals(PackageNameRules.Issue.BLANK_SEGMENT, PackageNameRules.validate("com.a."))
    }

    @Test
    fun segmentStartingWithDigitIsRejected() {
        assertEquals(PackageNameRules.Issue.SEGMENT_START, PackageNameRules.validate("com.1a"))
        assertEquals(PackageNameRules.Issue.SEGMENT_START, PackageNameRules.validate("1com.a"))
    }

    @Test
    fun illegalCharactersAreRejected() {
        assertEquals(PackageNameRules.Issue.INVALID_CHARS, PackageNameRules.validate("com.a-b"))
        assertEquals(PackageNameRules.Issue.INVALID_CHARS, PackageNameRules.validate("com.a b"))
        // 非 ASCII 出现在段中间：首字符合法，问题出在字符集
        assertEquals(PackageNameRules.Issue.INVALID_CHARS, PackageNameRules.validate("com.a中"))
        assertEquals(PackageNameRules.Issue.INVALID_CHARS, PackageNameRules.validate("com.a\$b"))
    }

    /** 非 ASCII 出现在段首：先命中"段首必须是字母或下划线"这一条 */
    @Test
    fun nonAsciiSegmentStartIsRejected() {
        assertEquals(PackageNameRules.Issue.SEGMENT_START, PackageNameRules.validate("中文.com"))
        assertEquals(PackageNameRules.Issue.SEGMENT_START, PackageNameRules.validate("com.中文"))
    }

    @Test
    fun overlongNameIsRejected() {
        // 构造 256 字符的合法结构包名（"com." + 252 个 a）
        val tooLong = "com." + "a".repeat(252)
        assertEquals(256, tooLong.length)
        assertEquals(PackageNameRules.Issue.TOO_LONG, PackageNameRules.validate(tooLong))

        // 边界：正好 255 字符应当通过
        val atLimit = "com." + "a".repeat(251)
        assertEquals(255, atLimit.length)
        assertNull(PackageNameRules.validate(atLimit))
    }

    /** 本应用自身与系统命名空间不能被图标包占用 */
    @Test
    fun reservedNamesAreRejected() {
        assertEquals(
            PackageNameRules.Issue.RESERVED,
            PackageNameRules.validate("com.muxiao.iconpacker"),
        )
        assertEquals(PackageNameRules.Issue.RESERVED, PackageNameRules.validate("android"))
        assertEquals(PackageNameRules.Issue.RESERVED, PackageNameRules.validate("com.android"))
    }

    /** 模板基础包名本身必须合法（它是默认值与派生前缀） */
    @Test
    fun templateBaseNameIsValid() {
        assertTrue(PackageNameRules.isValid(TemplateInfo.PACKAGE_NAME))
    }

    @Test
    fun suggestDerivesUniqueValidNames() {
        val a = PackageNameRules.suggest(1)
        val b = PackageNameRules.suggest(2)
        assertEquals("com.muxiao.icon.p1", a)
        assertEquals("com.muxiao.icon.p2", b)
        assertNotEquals(a, b)
        // 派生结果必须自己就是合法的，否则"默认值"会直接把用户带进坑里
        assertTrue(PackageNameRules.isValid(a))
        assertTrue(PackageNameRules.isValid(b))
    }

    @Test
    fun requireValidThrowsWithReadableMessage() {
        PackageNameRules.requireValid("com.example.ok")
        val e = runCatching { PackageNameRules.requireValid("com..bad") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertEquals(PackageNameRules.Issue.BLANK_SEGMENT.message, e?.message)
    }

    @Test
    fun everyIssueHasNonBlankMessage() {
        for (issue in PackageNameRules.Issue.entries) {
            assertTrue("问题枚举缺少文案：$issue", issue.message.isNotBlank())
        }
        assertFalse(PackageNameRules.Issue.entries.isEmpty())
    }
}
