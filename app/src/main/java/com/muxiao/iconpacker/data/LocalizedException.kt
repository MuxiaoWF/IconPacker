package com.muxiao.iconpacker.data

import androidx.annotation.StringRes

/**
 * 符合"用户可读失败"语义的异常：携带资源 id 与格式化参数，
 * 由 UI 层在下一次重组时按当前语言解析（对比：普通异常的 `message`
 * 是落库时的物化字符串，不随语言切换重译）。
 *
 * 供仓库层/领域校验抛出——那些 mesasge 原本直接展示给用户的失败点。
 * VM 捕获后用 `Throwable.uiMessage` 收敛为 UiText：
 * 命中本类则按资源 id 透传，否则退回"上下文文案 + 原始 message"。
 */
class LocalizedException(
    @StringRes val reasonRes: Int,
    val args: List<Any> = emptyList(),
) : IllegalStateException() {
    constructor(@StringRes reasonRes: Int, vararg args: Any) : this(reasonRes, args.toList())
}
