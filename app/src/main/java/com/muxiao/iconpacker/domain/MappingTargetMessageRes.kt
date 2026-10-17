package com.muxiao.iconpacker.domain

import androidx.annotation.StringRes
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.core.packer.engine.PackageNameRules

/**
 * core 引擎的包名校验枚举 → 界面文案资源 id（返回 0 视作缺省）。
 * core 保持"纯 Kotlin 无 Android 依赖"（中文文案留在引擎侧仅作内部分类与诊断），
 * 用户可见文案的本地化收口在 app 层。
 */
fun PackageNameRules.Issue.messageRes(): Int = when (this) {
    PackageNameRules.Issue.EMPTY -> R.string.err_pkg_empty
    PackageNameRules.Issue.TOO_LONG -> R.string.err_pkg_too_long
    PackageNameRules.Issue.TOO_FEW_SEGMENTS -> R.string.err_pkg_too_few_segments
    PackageNameRules.Issue.BLANK_SEGMENT -> R.string.err_pkg_blank_segment
    PackageNameRules.Issue.SEGMENT_START -> R.string.err_pkg_segment_start
    PackageNameRules.Issue.INVALID_CHARS -> R.string.err_pkg_invalid_chars
    PackageNameRules.Issue.RESERVED -> R.string.err_pkg_reserved
}
