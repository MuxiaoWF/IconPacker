package com.muxiao.iconpacker.core.packer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入压缩策略的边界验证。
 *
 * 这层被抽成纯函数就是为了能在这里覆盖边界：真实导入路径依赖 android.graphics，
 * JVM 单测跑不了，而"该压的没压（私有目录爆掉）/不该压的压了（放大丢细节）"
 * 都只在边界上出错——相机原图、超长图、已达标的小图、透明小图各是一类。
 */
class ImportImagePolicyTest {

    private val limit = ImportImagePolicy.DEFAULT_MAX_LONG_SIDE

    @Test
    fun `camera photo exceeds both size and byte limits`() {
        // 4160x3120 的 3.3MB JPEG：尺寸与体积双超标
        assertTrue(ImportImagePolicy.needsCompress(4160, 3120, 3_318_127))
    }

    @Test
    fun `portrait photo long side on height is detected too`() {
        // 竖拍照片长边在高上，只看 width 会漏判
        assertTrue(ImportImagePolicy.needsCompress(1080, 2160, 1_000_000))
    }

    @Test
    fun `small already-optimized image is left untouched`() {
        assertFalse(ImportImagePolicy.needsCompress(192, 192, 7_844))
        assertFalse(ImportImagePolicy.needsCompress(1080, 1080, 300_000))
    }

    @Test
    fun `small dimensions but oversized bytes still gets compressed`() {
        // 1080x1080 的 PNG 撑到 2MB：尺寸达标但体积超标，仍要压
        assertTrue(ImportImagePolicy.needsCompress(1080, 1080, 2L * 1024 * 1024))
    }

    @Test
    fun `exactly at the limit is not compressed`() {
        assertFalse(ImportImagePolicy.needsCompress(limit, limit, 100_000))
    }

    @Test
    fun `one pixel over the limit is compressed`() {
        assertTrue(ImportImagePolicy.needsCompress(limit + 1, 900, 100_000))
    }

    @Test
    fun `landscape source is scaled so that the long side hits the limit`() {
        assertEquals(1440 to 1080, ImportImagePolicy.targetSize(4160, 3120))
    }

    @Test
    fun `portrait source keeps its orientation`() {
        // 3120x5547 竖图：长边在高上，缩放后高必须正好等于上限
        assertEquals(809 to 1440, ImportImagePolicy.targetSize(3120, 5547))
    }

    @Test
    fun `panorama is scaled by its long side without distorting the aspect`() {
        val (w, h) = ImportImagePolicy.targetSize(6000, 1000)
        assertEquals(1440, w)
        assertEquals(240, h)
    }

    @Test
    fun `target size never upscales`() {
        assertEquals(512 to 384, ImportImagePolicy.targetSize(512, 384))
        assertEquals(1 to 1, ImportImagePolicy.targetSize(1, 1))
    }

    @Test
    fun `tiny side never collapses to zero`() {
        // 超长条：短边四舍五入后可能算成 0，必须收敛到 1，否则 createBitmap 抛异常
        val (w, h) = ImportImagePolicy.targetSize(8000, 3)
        assertEquals(1440, w)
        assertTrue("短边必须至少 1，实际 $h", h >= 1)
    }

    @Test
    fun `sample size keeps the decoded long side at or above the limit`() {
        // 4160 -> sample 2 -> 2080（>= 1440，够用且最省内存）
        assertEquals(2, ImportImagePolicy.sampleSize(4160, 3120))
        // 8000 -> 4 -> 2000
        assertEquals(4, ImportImagePolicy.sampleSize(8000, 6000))
        // 已达标 -> 不采样
        assertEquals(1, ImportImagePolicy.sampleSize(1440, 1000))
        assertEquals(1, ImportImagePolicy.sampleSize(1000, 1440))
    }

    @Test
    fun `sample size is always a power of two`() {
        listOf(1, 100, 1441, 2881, 5000, 12000, 40000).forEach { side ->
            val sample = ImportImagePolicy.sampleSize(side, side)
            assertTrue("$side -> $sample 不是 2 的幂", sample > 0 && sample and (sample - 1) == 0)
        }
    }

    @Test
    fun `sampled result is never smaller than the limit`() {
        listOf(1441, 2000, 2880, 5000, 12000, 40000).forEach { side ->
            val sampled = side / ImportImagePolicy.sampleSize(side, side)
            assertTrue("$side 采样后 $sampled < $limit", sampled >= limit)
        }
    }
}
