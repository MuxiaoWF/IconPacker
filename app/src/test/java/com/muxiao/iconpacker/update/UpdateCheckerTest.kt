package com.muxiao.iconpacker.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateChecker.isNewer] 版本比较口径单测。
 *
 * tag_name 可能带 v/V 前缀、段数不齐（1.2 vs 1.2.0），甚至整个不是数字——
 * 这些形态都必须收敛成确定的"新/不新"结论，而不是抛异常或随字典序乱判。
 */
class UpdateCheckerTest {

    @Test
    fun `patch bump is newer`() {
        assertTrue(UpdateChecker.isNewer("v1.0.2", "1.0.1"))
        assertTrue(UpdateChecker.isNewer("V2.0", "1.9.9"))
    }

    @Test
    fun `same version with different formats is not newer`() {
        assertFalse(UpdateChecker.isNewer("v1.0.1", "1.0.1"))
        assertFalse(UpdateChecker.isNewer("1.0.1", "v1.0.1"))
        // 段数不齐按 0 补齐：1.0 == 1.0.0
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun `older or rollback is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateChecker.isNewer("0.9", "1.0"))
    }

    @Test
    fun `non numeric segments count as zero`() {
        // "1.0.1-beta" 的第三段取数字前缀 1 → 与 1.0.1 相等 → 不算新
        assertFalse(UpdateChecker.isNewer("v1.0.1-beta", "1.0.1"))
        // "1.0.x" 第三段无法解析 → 0 → 旧于 1.0.1
        assertFalse(UpdateChecker.isNewer("1.0.x", "1.0.1"))
    }
}
