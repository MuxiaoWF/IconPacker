package com.muxiao.iconpacker.core.packer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CropSpec] 是"用户在编辑页拖出来的裁切框"与"打包时真正裁哪块像素"之间唯一的换算。
 *
 * 这里的断言全部围绕**边界收敛**：一旦 [CropSpec.resolveRect] 返回越界矩形，
 * [android.graphics.Bitmap.createBitmap] 会直接抛异常，打包整条链路失败。
 * 所以每种越界输入都要有对应用例锁住。
 */
class CropSpecTest {

    // ---- resolveRect：默认行为 ----

    @Test
    fun `zoom 1 takes the largest centered square from a landscape source`() {
        // 4000x3000 横图：短边 3000，居中裁方应为 (500, 0, 3000)
        val rect = CropSpec.DEFAULT.resolveRect(4000, 3000)
        assertEquals(500, rect.left)
        assertEquals(0, rect.top)
        assertEquals(3000, rect.side)
    }

    @Test
    fun `zoom 1 takes the largest centered square from a portrait source`() {
        // 竖图：短边是宽，居中裁方应为 (0, 500, 3000)
        val rect = CropSpec.DEFAULT.resolveRect(3000, 4000)
        assertEquals(0, rect.left)
        assertEquals(500, rect.top)
        assertEquals(3000, rect.side)
    }

    @Test
    fun `square source needs no cropping at all`() {
        val rect = CropSpec.DEFAULT.resolveRect(1200, 1200)
        assertEquals(CropRect(0, 0, 1200), rect)
    }

    @Test
    fun `already square source is reported as default`() {
        assertTrue(CropSpec().isDefault)
        assertFalse(CropSpec(zoom = 2f).isDefault)
        assertFalse(CropSpec(offsetX = 0.5f).isDefault)
    }

    // ---- resolveRect：缩放 ----

    @Test
    fun `zoom shrinks the square proportionally`() {
        // 1000x1000，zoom=2 → 边长 500；zoom=4 → 250
        assertEquals(500, CropSpec(zoom = 2f).resolveRect(1000, 1000).side)
        assertEquals(250, CropSpec(zoom = 4f).resolveRect(1000, 1000).side)
    }

    @Test
    fun `zoom below one is clamped up - never introduces padding`() {
        // 0.5 会让边长超过短边（引入空白），必须收敛回 1
        val rect = CropSpec(zoom = 0.5f).resolveRect(1000, 800)
        assertEquals(800, rect.side)
    }

    @Test
    fun `zoom above the ceiling is clamped`() {
        val rect = CropSpec(zoom = 999f).resolveRect(1000, 1000)
        assertEquals((1000 / CropSpec.MAX_ZOOM).toInt(), rect.side)
    }

    // ---- resolveRect：偏移 ----

    @Test
    fun `offset minus one pins the square to the left and top edge`() {
        val rect = CropSpec(zoom = 2f, offsetX = -1f, offsetY = -1f).resolveRect(1000, 800)
        // 边长 400：左侧贴边 → left=0；上下可区间 400 → top=0
        assertEquals(400, rect.side)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
    }

    @Test
    fun `offset plus one pins the square to the right and bottom edge`() {
        val rect = CropSpec(zoom = 2f, offsetX = 1f, offsetY = 1f).resolveRect(1000, 800)
        assertEquals(400, rect.side)
        assertEquals(1000 - 400, rect.left)
        assertEquals(800 - 400, rect.top)
    }

    @Test
    fun `offset is ignored on an axis that has no room to move`() {
        // 正方形源图 + zoom=1：可移动区间为 0，偏移必须彻底失效
        val rect = CropSpec(zoom = 1f, offsetX = 1f, offsetY = -1f).resolveRect(600, 600)
        assertEquals(CropRect(0, 0, 600), rect)
    }

    @Test
    fun `out of range offsets are clamped instead of producing an illegal rect`() {
        // 1200x900、zoom=3 → 边长 300，横向可移动区间 900，纵向 600
        val rect = CropSpec(zoom = 3f, offsetX = 99f, offsetY = -99f).resolveRect(1200, 900)
        assertEquals(300, rect.side)
        assertInsideSource(rect, 1200, 900)
        // offsetX 收敛到 +1 → 贴右；offsetY 收敛到 -1 → 贴顶
        assertEquals(1200 - 300, rect.left)
        assertEquals(0, rect.top)
    }

    // ---- resolveRect：不变量 ----

    @Test
    fun `rect is always square and inside the source across a wide sweep of inputs`() {
        val sources = listOf(1 to 1, 4000 to 3000, 3000 to 4000, 192 to 192, 7 to 900, 900 to 7)
        val zooms = listOf(-5f, 0f, 0.3f, 1f, 1.7f, 3f, 6f, 50f, Float.NaN)
        val offsets = listOf(-3f, -1f, -0.4f, 0f, 0.4f, 1f, 3f, Float.NaN)
        for ((w, h) in sources) {
            for (z in zooms) {
                for (ox in offsets) {
                    for (oy in offsets) {
                        val rect = CropSpec(z, ox, oy).resolveRect(w, h)
                        val label = "src=${w}x$h zoom=$z offset=($ox,$oy) -> $rect"
                        assertTrue("边长必须 > 0：$label", rect.side > 0)
                        assertTrue("边长不得超过短边：$label", rect.side <= minOf(w, h))
                        assertInsideSource(rect, w, h, label)
                    }
                }
            }
        }
    }

    @Test
    fun `non-finite inputs fall back to a legal rect`() {
        // NaN 经 coerceIn 会保持 NaN，若直接参与取整会得到 0 边长 → createBitmap 崩溃
        val rect = CropSpec(zoom = Float.NaN, offsetX = Float.NaN, offsetY = Float.NaN)
            .resolveRect(800, 600)
        assertTrue(rect.side > 0)
        assertInsideSource(rect, 800, 600)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero sized source is rejected loudly`() {
        CropSpec.DEFAULT.resolveRect(0, 600)
    }

    // ---- afterGesture ----

    @Test
    fun `panning right moves the crop window left`() {
        // 视口 300px、源图 1000x800、zoom=2 → 边长 400，放大倍率 300/400 = 0.75。
        // 向右拖 150px 等价于源图右移，取景框左移 150/0.75 = 200px：
        // 居中时 left=300，故 left=100，归一化 (100+200-500)/300 = -2/3
        val moved = CropSpec(zoom = 2f).afterGesture(
            panX = 150f, panY = 0f, zoomChange = 1f,
            srcWidth = 1000, srcHeight = 800, viewportPx = 300f,
        )
        assertEquals(2f, moved.zoom, 1e-4f)
        assertEquals(-2f / 3f, moved.offsetX, 1e-3f)
        assertEquals(100, moved.resolveRect(1000, 800).left)
    }

    @Test
    fun `panning past the edge is clamped at the boundary`() {
        val moved = CropSpec(zoom = 2f).afterGesture(
            panX = 9999f, panY = 0f, zoomChange = 1f,
            srcWidth = 1000, srcHeight = 800, viewportPx = 300f,
        )
        assertEquals(-1f, moved.offsetX, 1e-4f)
        assertEquals(0, moved.resolveRect(1000, 800).left)
    }

    @Test
    fun `pinch zoom in shrinks the crop square`() {
        val zoomed = CropSpec(zoom = 1f).afterGesture(
            panX = 0f, panY = 0f, zoomChange = 2f,
            srcWidth = 1000, srcHeight = 800, viewportPx = 300f,
        )
        assertEquals(2f, zoomed.zoom, 1e-4f)
        assertEquals(400, zoomed.resolveRect(1000, 800).side)
    }

    @Test
    fun `pinch zoom out is clamped at the maximum square`() {
        val zoomed = CropSpec(zoom = 2f).afterGesture(
            panX = 0f, panY = 0f, zoomChange = 0.01f,
            srcWidth = 1000, srcHeight = 800, viewportPx = 300f,
        )
        assertEquals(CropSpec.MIN_ZOOM, zoomed.zoom, 1e-4f)
    }

    @Test
    fun `gesture zoom respects the ceiling`() {
        val zoomed = CropSpec(zoom = 5f).afterGesture(
            panX = 0f, panY = 0f, zoomChange = 100f,
            srcWidth = 1000, srcHeight = 1000, viewportPx = 300f,
        )
        assertEquals(CropSpec.MAX_ZOOM, zoomed.zoom, 1e-4f)
    }

    @Test
    fun `gesture on an unzoomable square source keeps offset at zero`() {
        // zoom=1 且源图为正方形 → 边长已等于宽高，可移动区间为 0，任何拖动都无效
        val moved = CropSpec(zoom = 1f).afterGesture(
            panX = 500f, panY = 500f, zoomChange = 1f,
            srcWidth = 900, srcHeight = 900, viewportPx = 300f,
        )
        assertEquals(0f, moved.offsetX, 1e-4f)
        assertEquals(0f, moved.offsetY, 1e-4f)
    }

    private fun assertInsideSource(
        rect: CropRect,
        srcWidth: Int,
        srcHeight: Int,
        label: String = "",
    ) {
        assertTrue("left 越界 (${rect.left}) $label", rect.left >= 0)
        assertTrue("top 越界 (${rect.top}) $label", rect.top >= 0)
        assertTrue(
            "右边界越界 ${rect.left + rect.side} > $srcWidth $label",
            rect.left + rect.side <= srcWidth,
        )
        assertTrue(
            "下边界越界 ${rect.top + rect.side} > $srcHeight $label",
            rect.top + rect.side <= srcHeight,
        )
    }
}
