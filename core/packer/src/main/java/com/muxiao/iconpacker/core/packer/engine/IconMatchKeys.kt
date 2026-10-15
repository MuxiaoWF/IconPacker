package com.muxiao.iconpacker.core.packer.engine

/**
 * 图标标识的规范化与判定（纯 Kotlin，可 JVM 单测）。
 *
 * 导入解析、包名匹配、appfilter 关联三处必须共用同一套"什么算同一个键"的规则。
 * 分散实现会让同一事实有多个来源，任一处调整就静默失配——本工程已因
 * "同一事实两个来源"踩坑两次（AXML 偏移约定、图标条目目录名），故集中在此。
 */
object IconMatchKeys {

    /** 图片扩展名集合（用于判断末段是扩展名而非包名的一段） */
    val IMAGE_EXTENSIONS: Set<String> = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico", "tiff", "tif", "heic", "heif",
    )

    /**
     * 规范化匹配键：小写 + 去除非字母数字（CJK 保留），**不去扩展名**。
     *
     * 用于包名与标签——它们的点号是语义分隔符，不是扩展名。因此
     * `com.android.chrome` 与文件名 `com_android_chrome.png` 都会归一到
     * `comandroidchrome`，这正是"包名维度"能跨命名风格命中的原因。
     */
    fun normalize(raw: String): String = raw.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 去掉末段图片扩展名；末段不是已知图片扩展名时原样返回，
     * 避免把无扩展名的包名（`com.android.chrome`）误删一段。
     */
    fun stripImageExtension(raw: String): String {
        val ext = raw.substringAfterLast('.', "")
        return if (ext.isNotEmpty() && ext.lowercase() in IMAGE_EXTENSIONS) {
            raw.substringBeforeLast('.')
        } else {
            raw
        }
    }

    /**
     * 是否形如 Android 包名：至少两段，每段是合法标识符。
     *
     * 用于在 ZIP 路径里识别"这一段是包名"。伪阳性（如 `my.theme.icons`）无害——
     * 若设备上不存在该包，绑定会自然落空并继续走模糊匹配。
     */
    fun looksLikePackage(raw: String): Boolean {
        val parts = raw.split('.')
        return parts.size >= 2 && parts.all { isIdentifier(it) }
    }

    /**
     * 标识符判定：首字符必须是 ASCII 字母或下划线，其余为字母/数字/下划线。
     *
     * 刻意不接收 CJK 首字符——包名只能是 ASCII，否则 `中文.应用` 这类
     * 中文应用名会被误判成包名。
     */
    private fun isIdentifier(segment: String): Boolean {
        val first = segment.firstOrNull() ?: return false
        val asciiStart = first in 'a'..'z' || first in 'A'..'Z' || first == '_'
        return asciiStart && segment.all { it.isLetterOrDigit() || it == '_' }
    }
}
