package com.muxiao.iconpacker.core.packer.engine

/**
 * 正方形裁切窗口（相对源图，归一化描述）。
 *
 * 源图往往不是正方形（手机照片多为 4:3 / 16:9），而图标必须是正方形。本模型描述
 * 「从源图里取出哪一个正方形」，由编辑页的手动裁剪框产生，打包时由 [IconProcessor]
 * 据此真正裁切。
 *
 * - [zoom] = [MIN_ZOOM] 表示取源图内**最大的居中正方形**（铺满短边），值越大裁得越小，
 *  等效于对源图放大。
 * - [offsetX] / [offsetY] 以「裁切框可移动范围」为单位把裁切框从居中位置推开：
 *  -1 = 贴到最左/最上，0 = 居中，+1 = 贴到最右/最下。当某一轴已无移动余量
 *  （例如正方形源图）时该分量恒为 0，不产生任何影响。
 *
 * 本类**不依赖 android.graphics**：纯数值模型，可直接跑 JVM 单测。
 */
data class CropSpec(
    val zoom: Float = MIN_ZOOM,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {

    /** 收敛到合法区间——手势计算与浮点误差都可能把值推出边界。 */
    fun normalized(): CropSpec = CropSpec(
        zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
        offsetX = offsetX.coerceIn(-1f, 1f),
        offsetY = offsetY.coerceIn(-1f, 1f),
    )

    /** 是否等同默认（取最大居中正方形），可用于跳过无意义的裁切。 */
    val isDefault: Boolean get() = this == DEFAULT

    /**
     * 换算为源图中的像素裁切矩形。
     *
     * 返回值保证：正方形、边长 ∈ [1, min(srcWidth, srcHeight)]、矩形完全落在源图内——
     * 越界会让 [android.graphics.Bitmap.createBitmap] 直接抛异常，必须在源头收敛。
     */
    fun resolveRect(srcWidth: Int, srcHeight: Int): CropRect {
        require(srcWidth > 0 && srcHeight > 0) { "源图尺寸非法：${srcWidth}x$srcHeight" }
        val s = normalized()
        val shortSide = minOf(srcWidth, srcHeight)
        val side = (shortSide / s.zoom).toInt().coerceIn(1, shortSide)
        val rangeX = srcWidth - side
        val rangeY = srcHeight - side
        val left = ((rangeX / 2f) + s.offsetX * rangeX / 2f).toInt().coerceIn(0, rangeX)
        val top = ((rangeY / 2f) + s.offsetY * rangeY / 2f).toInt().coerceIn(0, rangeY)
        return CropRect(left, top, side)
    }

    /**
     * 应用一次手势（拖动 + 双指缩放），返回新的裁切窗口。
     *
     * 语义：手指拖动的是**源图**，所以视口位移要先除以放大倍率换回源图像素；
     * 源图跟着手指走，等价于取景框朝反方向移动，故取负号。
     *
     * 放在模型里而非 UI 里：这是纯数值换算，不依赖 android.graphics 与 Compose，
     * 可以脱离界面直接单测（越界收敛是最容易出错的部分）。
     *
     * @param panX/panY 本次手势的视口位移（px）
     * @param zoomChange 本次手势的缩放倍率增量（1.0 = 不变）
     * @param viewportPx 裁剪视图的边长（px），用于把视口位移换算回源图像素
     */
    fun afterGesture(
        panX: Float,
        panY: Float,
        zoomChange: Float,
        srcWidth: Int,
        srcHeight: Int,
        viewportPx: Float,
    ): CropSpec {
        val cur = normalized()
        val nextZoom = (cur.zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val side = (minOf(srcWidth, srcHeight) / nextZoom).coerceAtLeast(1f)
        val rangeX = srcWidth - side
        val rangeY = srcHeight - side
        val scale = viewportPx / side

        val centerX = srcWidth / 2f + cur.offsetX * rangeX / 2f - panX / scale
        val centerY = srcHeight / 2f + cur.offsetY * rangeY / 2f - panY / scale
        val left = (centerX - side / 2f).coerceIn(0f, rangeX)
        val top = (centerY - side / 2f).coerceIn(0f, rangeY)

        return CropSpec(
            zoom = nextZoom,
            offsetX = if (rangeX > 0f) {
                ((left + side / 2f - srcWidth / 2f) / (rangeX / 2f)).coerceIn(-1f, 1f)
            } else {
                0f
            },
            offsetY = if (rangeY > 0f) {
                ((top + side / 2f - srcHeight / 2f) / (rangeY / 2f)).coerceIn(-1f, 1f)
            } else {
                0f
            },
        )
    }

    companion object {
        /** 1× = 取源图内最大居中正方形，不允许比它更小（否则会引入空白） */
        const val MIN_ZOOM = 1f

        /**
         * 上限 6×。产物是 192px，源图短边被裁到 1/6 后再放大到 192px 已明显糊；
         * 再放开只会让用户裁出无法使用的图标。
         */
        const val MAX_ZOOM = 6f

        val DEFAULT = CropSpec()
    }
}

/**
 * 源图中的像素裁切矩形，必为正方形（[side] 即边长）。
 *
 * 用独立类型而非 `IntArray`：调用方必须同时拿到三个量，拆散容易传错顺序。
 */
data class CropRect(val left: Int, val top: Int, val side: Int)
