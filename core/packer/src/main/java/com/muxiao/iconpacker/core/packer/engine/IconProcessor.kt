package com.muxiao.iconpacker.core.packer.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sign

/** 图标外形（FR-05 批量统一化与单张编辑共用）。作为**裁剪蒙版**独立生效，与背景色无关。 */
enum class IconShape {
    /** 保持原图（正方形铺满，不裁剪） */
    ORIGINAL,

    /** 圆形 */
    CIRCLE,

    /** 圆角矩形 */
    ROUNDED_RECT,

    /** Squircle（连续曲率圆角，iOS 风格） */
    SQUIRCLE,
}

/**
 * 图标样式：裁切窗口 + 外形 + 背景 + 内容缩放 + 边框。
 *
 * 设计原则：**背景与边框默认全部关闭，绝不自动添加**。
 * 此前 `contentScale` 默认取安全区 0.611、且批量对话框默认背景色为品牌蓝，
 * 二者叠加会在图标四周留出一圈蓝色底板，看起来就像"莫名其妙的蓝色边框"。
 * 现在：
 * - [contentScale] 默认 1.0（铺满），只有用户显式开背景/边框时才建议收到安全区；
 * - [backgroundColor] 默认 [Color.TRANSPARENT] = 不画底板；
 * - [borderWidth] 默认 0 = 不描边；
 * - [shape] 独立作为裁剪蒙版，不填背景色也能裁剪外形。
 *
 * @param contentScale 内容占画布比例（1.0 = 铺满）。
 *  背景/边框开启时建议用 [SAFE_SCALE]（自适应图标安全区 66dp/108dp ≈ 0.61），
 *  这样内容不会被启动器蒙版裁掉。
 * @param crop 正方形裁切窗口，见 [CropSpec]。
 * @param borderWidth 边框描边宽度占画布比例，0 = 无边框。
 */
data class IconStyle(
    val shape: IconShape = IconShape.ORIGINAL,
    val backgroundColor: Int = Color.TRANSPARENT,
    val contentScale: Float = DEFAULT_CONTENT_SCALE,
    val cornerRatio: Float = DEFAULT_CORNER_RATIO,
    val crop: CropSpec = CropSpec.DEFAULT,
    val borderWidth: Float = 0f,
    val borderColor: Int = DEFAULT_BORDER_COLOR,
) {
    /** 是否绘制背景底板（默认否） */
    val hasBackground: Boolean get() = backgroundColor != Color.TRANSPARENT

    /** 是否绘制边框描边（默认否） */
    val hasBorder: Boolean get() = borderWidth > 0f

    /**
     * 是否需要走合成路径。
     *
     * 纯铺满直出（外形原图、无背景、无边框、缩放 100%）可跳过 [IconProcessor.compose]，
     * 省一次 192×192 的画布分配——批量处理 1024 张时这点开销不可忽略。
     */
    val needsCompose: Boolean
        get() = shape != IconShape.ORIGINAL ||
            hasBackground ||
            hasBorder ||
            contentScale < 0.999f

    companion object {
        /** 66dp / 108dp：自适应图标可见区占画布比例，开启背景/边框时的推荐值 */
        const val SAFE_SCALE = 66f / 108f

        /** 默认铺满——未启用背景时不留白，避免图标被"莫名缩小一圈" */
        const val DEFAULT_CONTENT_SCALE = 1f

        const val DEFAULT_CORNER_RATIO = 0.25f

        /** 默认边框色：白色。深色图标上白边清晰，且不会像品牌蓝那样突兀 */
        const val DEFAULT_BORDER_COLOR = Color.WHITE

        /** 背景色候选（编辑器色板；第 0 项透明 = 不画背景，也是默认项） */
        val PRESET_COLORS = listOf(
            Color.TRANSPARENT to "无",
            Color.parseColor("#F5F7FA") to "纸白",
            Color.parseColor("#212121") to "墨黑",
            Color.parseColor("#1565C0") to "品牌蓝",
            Color.parseColor("#00897B") to "青绿",
            Color.parseColor("#F9A825") to "琥珀",
            Color.parseColor("#E53935") to "朱红",
        )

        /** 边框色候选 */
        val PRESET_BORDER_COLORS = listOf(
            Color.WHITE to "白",
            Color.parseColor("#BDBDBD") to "浅灰",
            Color.parseColor("#212121") to "墨黑",
            Color.parseColor("#1565C0") to "品牌蓝",
        )
    }
}

/**
 * 图标预处理：源图 → 192px WebP。
 *
 * 两条路径：
 * - 铺满直出（[IconStyle] 无任何合成要素）：解码 → 按 [CropSpec] 裁方 → 缩放 192px；
 * - 样式合成：按 [CropSpec] 裁方 → 外形裁剪 → 背景 → 内容 → 边框。
 *
 * 注意：使用 [Bitmap.CompressFormat.WEBP]（API 14 起的有损 WebP，minSdk 24 可用）；
 * WEBP_LOSSLESS 常量 API 30 才引入，不可在 minSdk 24 上引用。
 */
class IconProcessor(
    private val targetPx: Int = DEFAULT_TARGET_PX,
    private val quality: Int = DEFAULT_QUALITY,
) {

    /** 原图直出（打包管线默认路径） */
    fun encode(sourcePath: String): ByteArray = encode(sourcePath, IconStyle())

    /** 带样式编码（编辑器保存 / 批量统一化） */
    fun encode(sourcePath: String, style: IconStyle): ByteArray {
        val squared = decodeSquare(sourcePath, targetPx, style.crop)
        val bitmap = if (style.needsCompose) compose(squared, style) else scaleTo(squared, targetPx)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
        val bytes = out.toByteArray()
        require(bytes.isNotEmpty()) { "WebP 编码失败：$sourcePath" }
        if (bitmap !== squared) bitmap.recycle()
        squared.recycle()
        return bytes
    }

    /**
     * 图标包自身的启动器图标：源图 → 192px **WebP**。
     *
     * 与槽位图标（[encode]，192px WebP）一致，输出 WebP 以匹配模板
     * `res/mipmap-<密度>-v4/ic_launcher.webp`（目录由 `PackAssembler` 从模板探测，勿写死），
     * 条目扩展名与内容类型一致，避免部分设备解码失败。
     * 尺寸必须是 192，与各密度 mipmap（density × 48dp / 160）严格对应。
     * 启动器按**内禀尺寸**取图标，尺寸与密度不匹配会被放大/缩小到错误的 dp
     * （曾用 512px：在 640dpi 下等于 128dp，比标准的 48dp 大 2.67 倍）。
     */
    fun encodeLauncherIcon(sourcePath: String): ByteArray {
        val squared = decodeSquare(sourcePath, LAUNCHER_ICON_PX)
        val bitmap = scaleTo(squared, LAUNCHER_ICON_PX)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
        val bytes = out.toByteArray()
        require(bytes.isNotEmpty()) { "PNG 编码失败：$sourcePath" }
        if (bitmap !== squared) bitmap.recycle()
        squared.recycle()
        return bytes
    }

    /**
     * 用**已解码的源图**生成预览，与 [encode] 走同一条合成路径（所见即所得）。
     *
     * 编辑页拖动裁剪框会按帧重算，若每帧都从文件重解码 4K JPEG 必然掉帧；
     * 因此由调用方持有源图，这里只做裁切与合成。
     *
     * 不回收 [source]（调用方持有）；内部临时产生的中间位图会自行回收。
     */
    fun previewFrom(source: Bitmap, style: IconStyle): Bitmap {
        val rect = style.crop.resolveRect(source.width, source.height)
        val whole = rect.left == 0 && rect.top == 0 &&
            rect.side == source.width && rect.side == source.height
        val squared = if (whole) source
        else Bitmap.createBitmap(source, rect.left, rect.top, rect.side, rect.side)
        val result = if (style.needsCompose) compose(squared, style) else scaleTo(squared, targetPx)
        if (!whole && squared !== result) squared.recycle()
        return result
    }

    /** 生成编辑器预览（与保存同一条合成路径，保证"所见即所得"） */
    fun generatePreview(sourcePath: String, style: IconStyle): Bitmap {
        val squared = decodeSquare(sourcePath, targetPx, style.crop)
        return if (style.needsCompose) compose(squared, style) else scaleTo(squared, targetPx)
        // 预览不 recycle squared（返回的 bitmap 可能与之不同）；调用方按需释放
    }

    /**
     * 解码**完整源图**供编辑页裁剪视图使用（不做裁切）。
     *
     * 长边压到 [maxPx] 以内：4K 照片原样解码要 50MB+，而裁剪框只需要看清构图。
     */
    fun decodeForEditing(sourcePath: String, maxPx: Int = EDIT_SOURCE_PX): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码图片：$sourcePath" }
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longSide / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(sourcePath, options) ?: error("图片解码失败：$sourcePath")
    }

    /** 等比缩放到目标边长；已是目标尺寸时原样返回（不额外分配） */
    private fun scaleTo(source: Bitmap, px: Int): Bitmap =
        if (source.width == px && source.height == px) source
        else Bitmap.createScaledBitmap(source, px, px, true)

    /**
     * 解码并按 [crop] 裁出正方形。
     *
     * @param minPx 采样后短边需保留的下限，决定解码精度（启动器图标与槽位图标同为 192）
     */
    private fun decodeSquare(
        sourcePath: String,
        minPx: Int = targetPx,
        crop: CropSpec = CropSpec.DEFAULT,
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码图片：$sourcePath" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, minPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(sourcePath, options)
            ?: error("图片解码失败：$sourcePath")
        val rect = crop.resolveRect(decoded.width, decoded.height)
        if (rect.left == 0 && rect.top == 0 &&
            rect.side == decoded.width && rect.side == decoded.height
        ) {
            return decoded
        }
        return Bitmap.createBitmap(decoded, rect.left, rect.top, rect.side, rect.side)
    }

    /**
     * 按样式合成：外形裁剪 → 背景 → 内容 → 边框。
     *
     * 顺序有讲究：外形必须先 `clipPath`，让背景填充与内容都被裁进同一外形；
     * 边框最后描，才不会被内容盖住。
     */
    private fun compose(source: Bitmap, style: IconStyle): Bitmap {
        val size = targetPx
        val sizeF = size.toFloat()
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val fullRect = RectF(0f, 0f, sizeF, sizeF)
        val clipped = style.shape != IconShape.ORIGINAL

        if (clipped) {
            // 画布此时尚无任何裁剪区，clipPath(path) 的求交等价于直接设定形状。
            // 不用 clipPath(path, Region.Op.REPLACE)：该重载在 API 28 起废弃。
            canvas.save()
            canvas.clipPath(shapePath(style))
        }

        if (style.hasBackground) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.backgroundColor }
            if (clipped) canvas.drawPath(shapePath(style), fill) else canvas.drawRect(fullRect, fill)
        }

        val contentPx = (size * style.contentScale.coerceIn(0.2f, 1f)).toInt().coerceAtLeast(1)
        val scaled = scaleTo(source, contentPx)
        val offset = (size - contentPx) / 2
        canvas.drawBitmap(
            scaled,
            offset.toFloat(),
            offset.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        if (scaled !== source) scaled.recycle()

        if (style.hasBorder) {
            val strokePx = (size * style.borderWidth).coerceAtLeast(1f)
            val inset = strokePx / 2f
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = strokePx
                color = style.borderColor
            }
            val strokeRect = RectF(inset, inset, sizeF - inset, sizeF - inset)
            when (style.shape) {
                IconShape.CIRCLE -> canvas.drawOval(strokeRect, stroke)
                IconShape.ROUNDED_RECT -> {
                    val r = (sizeF * style.cornerRatio) - inset
                    canvas.drawRoundRect(strokeRect, r.coerceAtLeast(0f), r.coerceAtLeast(0f), stroke)
                }
                IconShape.SQUIRCLE -> canvas.drawPath(squirclePath(sizeF, inset), stroke)
                IconShape.ORIGINAL -> canvas.drawRect(strokeRect, stroke)
            }
        }

        if (clipped) canvas.restore()
        return output
    }

    /** 外形路径（无收缩；描边走独立 strokeRect/squirclePath 计算交由调用处处理） */
    private fun shapePath(style: IconStyle): Path {
        val sizeF = targetPx.toFloat()
        val rect = RectF(0f, 0f, sizeF, sizeF)
        return when (style.shape) {
            IconShape.CIRCLE -> Path().apply { addOval(rect, Path.Direction.CW) }
            IconShape.ROUNDED_RECT -> Path().apply {
                val r = (sizeF * style.cornerRatio).coerceAtLeast(0f)
                addRoundRect(rect, r, r, Path.Direction.CW)
            }
            IconShape.SQUIRCLE -> squirclePath(sizeF, 0f)
            IconShape.ORIGINAL -> Path().apply { addRect(rect, Path.Direction.CW) }
        }
    }

    /** 采样后短边不小于 2*minPx：再小就没有放大意义，再大则浪费解码内存 */
    private fun computeSampleSize(width: Int, height: Int, minPx: Int): Int {
        var sample = 1
        var minSide = minOf(width, height)
        while (minSide / 2 >= minPx * 2) {
            minSide /= 2
            sample *= 2
        }
        return sample
    }

    companion object {
        const val DEFAULT_TARGET_PX = 192
        const val DEFAULT_QUALITY = 85

        /**
         * 图标包自身启动器图标的边长（[encodeLauncherIcon]）。
         *
         * 与模板 `mipmap-xxxhdpi`（density=640）对齐：640 × 48dp / 160 = 192px。
         * 启动器按内禀尺寸取用，像素数与声明的密度必须一致，否则图标会以错误的 dp 渲染。
         */
        const val LAUNCHER_ICON_PX = 192

        /** 编辑页裁剪视图的源图长边上限 */
        const val EDIT_SOURCE_PX = 1080

        /**
         * Squircle（超椭圆近似）：|x/a|^n + |y/a|^n = 1，n=4 给出连续曲率圆角，
         * 视觉接近 iOS 图标外形；折线采样 48 段足够平滑。
         *
         * @param inset 向内收缩量（描边用），0 = 铺满画布
         */
        internal fun squirclePath(size: Float, inset: Float = 0f): Path {
            val path = Path()
            val a = (size - 2 * inset) / 2f
            val c = size / 2f
            val n = 4.0
            val steps = 48
            for (i in 0..steps) {
                val t = i * 2.0 * Math.PI / steps
                val ct = cos(t)
                val st = sin(t)
                // 全程 Double 运算后一次性转 Float：a 是 Float，与 Double 相乘会提升为
                // Double，若混算会让 path.moveTo/lineTo 收到 Double 而编译失败
                val x = (a * sign(ct) * abs(ct).pow(2.0 / n)).toFloat()
                val y = (a * sign(st) * abs(st).pow(2.0 / n)).toFloat()
                if (i == 0) path.moveTo(c + x, c + y) else path.lineTo(c + x, c + y)
            }
            path.close()
            return path
        }
    }
}
