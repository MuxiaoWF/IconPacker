package com.muxiao.iconpacker.core.packer.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 导入图片压缩策略（纯计算，可 JVM 单测）。
 *
 * 为什么需要：用户从相册选的多是相机原图（4000×3000、3MB 起步）。这些原图**必须长期保留**
 * 以便非破坏性再编辑（FR-05），但按原样落盘会让私有目录迅速膨胀——100 张就是 300MB。
 * 而打包产物里图标只有 192px（48dp/xxxhdpi），编辑页裁剪视图也只按长边 [IconProcessor.EDIT_SOURCE_PX]
 * 采样，保留 [DEFAULT_MAX_LONG_SIDE] 已有约 7 倍余量：再大的分辨率对最终图标没有任何贡献。
 *
 * 因此导入时压到长边 [DEFAULT_MAX_LONG_SIDE]：3.3MB/张 → 约 0.3MB/张，肉眼无损。
 */
object ImportImagePolicy {

    /**
     * 导入原图长边上限。
     *
     * 只降不升：已小于该值的图片绝不放大（放大只会虚增体积、不增信息）。
     * 取 1440 的理由：打包用的图标是 192px、编辑预览按 1080 采样，
     * 即使裁剪框放大 1.5 倍取景，仍有 ≥ 960px 有效像素——远超 192px 的需求。
     */
    const val DEFAULT_MAX_LONG_SIDE = 1440

    /**
     * 低于此体积且尺寸达标则原样保留，不做重编码。
     *
     * 必要性：小体积的图形类 PNG（纯色/线条）重编码成 WebP 往往**更大**，
     * 且有一次无谓的有损损失。384KB 是"普通照片压到 1440 后应该达到的量级"。
     */
    const val DEFAULT_KEEP_UNDER_BYTES = 384L * 1024

    /** 是否需要压缩：尺寸超标或体积超标 */
    fun needsCompress(
        width: Int,
        height: Int,
        byteCount: Long,
        maxLongSide: Int = DEFAULT_MAX_LONG_SIDE,
        keepUnderBytes: Long = DEFAULT_KEEP_UNDER_BYTES,
    ): Boolean = width > maxLongSide || height > maxLongSide || byteCount > keepUnderBytes

    /**
     * 目标尺寸：等比缩放到长边 = [maxLongSide]；已在范围内则原样返回（不放大）。
     *
     * 返回 (宽, 高)，两者至少为 1。
     */
    fun targetSize(width: Int, height: Int, maxLongSide: Int = DEFAULT_MAX_LONG_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "图片尺寸非法：${width}x$height" }
        val longSide = maxOf(width, height)
        if (longSide <= maxLongSide) return width to height
        val scale = maxLongSide.toDouble() / longSide
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }

    /**
     * 解码采样率：取 2 的幂，使采样后长边仍 ≥ [maxLongSide]。
     *
     * 用 2 的幂是因为 [BitmapFactory.Options.inSampleSize] 只对 2 的幂有保证；
     * 取"仍够大"的一侧，后续再用 [targetSize] 做精确缩放，避免二次放大丢细节。
     */
    fun sampleSize(width: Int, height: Int, maxLongSide: Int = DEFAULT_MAX_LONG_SIDE): Int {
        var sample = 1
        var longSide = maxOf(width, height)
        while (longSide / 2 >= maxLongSide) {
            longSide /= 2
            sample *= 2
        }
        return sample
    }

    /** 压缩后应使用的扩展名（含点） */
    const val WEBP_SUFFIX = ".webp"
    const val PNG_SUFFIX = ".png"
}

/**
 * 导入图片压缩：把用户导入的原图按 [ImportImagePolicy] 压到可用尺寸后落盘。
 *
 * 与打包链路的区别：这里压的是**原始素材**（编辑与打包的共同输入），
 * 而 [IconProcessor] 负责产出 192px 的成品图标。两者互不替代。
 *
 * 编码选择：
 * - 不透明（照片类）→ WebP 有损，体积最小；
 * - 含透明像素（图形类）→ PNG，**不能**用有损 WebP：透明度一旦被丢会直接毁掉图标；
 * - 压缩后反而更大（例如小尺寸纯色 PNG）→ 放弃压缩，保留原文件。
 *
 * 全部失败路径都退回"原文件"：压缩是优化，绝不能因为一张图压不动就让导入失败。
 */
class ImportCompressor(
    private val maxLongSide: Int = ImportImagePolicy.DEFAULT_MAX_LONG_SIDE,
    private val quality: Int = DEFAULT_QUALITY,
    private val keepUnderBytes: Long = ImportImagePolicy.DEFAULT_KEEP_UNDER_BYTES,
) {

    /**
     * 原地压缩 [file]（必要时改扩展名），返回压缩后的文件。
     *
     * @return 压好的文件；若无需压缩、压缩失败或压缩后更大，返回传入的 [file]
     */
    fun compressInPlace(file: File): File {
        val result = runCatching { compress(file) }.getOrNull() ?: return file
        return result
    }

    private fun compress(file: File): File? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (!ImportImagePolicy.needsCompress(width, height, file.length(), maxLongSide, keepUnderBytes)) {
            return file
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImportImagePolicy.sampleSize(width, height, maxLongSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        val (tw, th) = ImportImagePolicy.targetSize(decoded.width, decoded.height, maxLongSide)
        val scaled = if (decoded.width == tw && decoded.height == th) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, tw, th, true).also { if (it !== decoded) decoded.recycle() }
        }

        try {
            // PNG/WebP 解码结果可能真带透明；JPEG 一定不透明，可省一次全图扫描。
            val transparent = bounds.outMimeType != MIME_JPEG && hasTransparency(scaled)
            val format = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.WEBP
            val suffix = if (transparent) ImportImagePolicy.PNG_SUFFIX else ImportImagePolicy.WEBP_SUFFIX

            val out = ByteArrayOutputStream()
            scaled.compress(format, quality, out)
            val bytes = out.toByteArray()
            if (bytes.isEmpty() || bytes.size.toLong() >= file.length()) return file

            val target = File(file.parentFile, file.nameWithoutExtension + suffix)
            if (target.absolutePath != file.absolutePath) file.delete()
            target.writeBytes(bytes)
            return target
        } finally {
            scaled.recycle()
        }
    }

    /** 是否存在半透明像素（逐像素扫描缩略图；1440×1080 量级约数毫秒） */
    private fun hasTransparency(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val row = IntArray(width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if (pixel ushr 24 != 0xFF) return true
            }
        }
        return false
    }

    companion object {
        /** 有损编码质量：90 在照片上与 85 体积接近，但重编码两次后衰减更小 */
        const val DEFAULT_QUALITY = 90

        private const val MIME_JPEG = "image/jpeg"
    }
}
