package com.muxiao.iconpacker.data

import com.muxiao.iconpacker.core.packer.engine.ImportImagePolicy

/**
 * 导入压缩档位：把「导入即压缩」的三个参数（采样上限 / 免压阈值）打包成预设。
 *
 * 有损质量不参与档位——90 在照片上与 85 体积接近但重编码衰减更小（见
 * ImportCompressor.DEFAULT_QUALITY 注释），动它只会更差，不开放。
 */
enum class ImportQuality(
    val maxLongSide: Int,
    val keepUnderBytes: Long,
) {
    /** 高画质：更大的采样上限与免压阈值，适合源图本身就干净的图标包 */
    HIGH(2048, 512L * 1024),

    /** 均衡（默认）：与历史行为逐参数一致 */
    BALANCED(ImportImagePolicy.DEFAULT_MAX_LONG_SIDE, ImportImagePolicy.DEFAULT_KEEP_UNDER_BYTES),

    /** 体积优先：更小的采样上限 + 更低的免压阈值，相机原图批量导入的首选 */
    COMPACT(960, 256L * 1024),
}
