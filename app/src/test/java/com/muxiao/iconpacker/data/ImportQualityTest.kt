package com.muxiao.iconpacker.data

import com.muxiao.iconpacker.core.packer.engine.ImportImagePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ImportQuality] 档位参数单测。
 *
 * 重点是 BALANCED：它承诺"与历史行为逐参数一致"——如果上游
 * [ImportImagePolicy] 的默认值将来被调整而这里没跟着改，导入行为就会
 * 静默漂移。本测试就是这道闸。
 */
class ImportQualityTest {

    @Test
    fun `balanced matches historical policy defaults`() {
        assertEquals(ImportImagePolicy.DEFAULT_MAX_LONG_SIDE, ImportQuality.BALANCED.maxLongSide)
        assertEquals(
            ImportImagePolicy.DEFAULT_KEEP_UNDER_BYTES,
            ImportQuality.BALANCED.keepUnderBytes,
        )
    }

    @Test
    fun `high quality relaxes both limits`() {
        assertEquals(2048, ImportQuality.HIGH.maxLongSide)
        assertEquals(512L * 1024, ImportQuality.HIGH.keepUnderBytes)
    }

    @Test
    fun `compact tightens both limits`() {
        assertEquals(960, ImportQuality.COMPACT.maxLongSide)
        assertEquals(256L * 1024, ImportQuality.COMPACT.keepUnderBytes)
    }

    @Test
    fun `presets are strictly ordered high - balanced - compact`() {
        // 高画质上限最大、体积优先最小；免压阈值同向——保证三档真实有区别
        assert(ImportQuality.HIGH.maxLongSide > ImportQuality.BALANCED.maxLongSide)
        assert(ImportQuality.BALANCED.maxLongSide > ImportQuality.COMPACT.maxLongSide)
        assert(ImportQuality.HIGH.keepUnderBytes > ImportQuality.BALANCED.keepUnderBytes)
        assert(ImportQuality.BALANCED.keepUnderBytes > ImportQuality.COMPACT.keepUnderBytes)
    }
}
