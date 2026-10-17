package com.muxiao.iconpacker.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 待本地化的 UI 文案：资源 id + 格式化参数（VM/data 层产出，UI 层解析）。
 *
 * 为什么不在 VM 里直接生成字符串：VM 持有的 state 不会随语言切换自动重算，
 * 存资源 id 让 `stringResource` 在重组时按当前语言解析，切语言（重建 Activity）
 * 后所有历史状态里的文案都自动落对语言；同时保持 VM 纯 Kotlin 可 JVM 单测。
 *
 * `args` 支持两种复合形式：
 * - 嵌套 [UiText]（如"沿用模板"这类自身的可译分段），解析时先递归落成字符串再注入外层格式串；
 * - 列表参数（`List<*>`）：逐项展开后**直接拼接**（无分隔符）——需要列表内分隔时由
 *   调用方把分隔符作为可译 [UiText] 交错插入（见导入结果的映射明细）。
 *
 * 注意：已是运行期数据的应用名等字符串参数不随切换重译——属预期，只影响未重新生成的动态内容。
 */
data class UiText(
    @StringRes val res: Int,
    val args: List<Any> = emptyList(),
) {
    constructor(@StringRes res: Int, vararg args: Any) : this(res, args.toList())
}

/** 在组合内解析为当前语言的字符串（嵌套 UiText/列表参数递归展开） */
@Composable
fun UiText.asString(): String {
    val resolved = args.map { resolveUiArg(it) }
    return stringResource(res, *resolved.toTypedArray())
}

@Composable
private fun resolveUiArg(arg: Any): Any = when (arg) {
    is UiText -> arg.asString()
    is List<*> -> arg.map { part -> resolveUiArg(part ?: ".").toString() }.joinToString("")
    else -> arg
}

/**
 * 非 Composable 环境（LaunchedEffect 等副作用块）按 [Context] 解析；
 * 参数规则与 [asString] 一致（嵌套 UiText/列表递归展开）。
 */
fun UiText.asString(context: Context): String {
    val resolved = args.map { resolveContextArg(it, context) }
    return context.getString(res, *resolved.toTypedArray())
}

private fun resolveContextArg(arg: Any, context: Context): Any = when (arg) {
    is UiText -> arg.asString(context)
    is List<*> -> arg.map { part -> resolveContextArg(part ?: ".", context).toString() }.joinToString("")
    else -> arg
}

/**
 * 把失败原因收敛为 UiText：命中 [com.muxiao.iconpacker.data.LocalizedException]
 * （资源化失败）按其透传，其余异常退回"上下文文案 + 原始 message"——
 * 引擎/系统异常文本不承诺本地化，由上下文包裹呈现。
 */
fun Throwable.uiMessage(
    @StringRes fallbackRes: Int,
): UiText = when (this) {
    is com.muxiao.iconpacker.data.LocalizedException -> UiText(reasonRes, args)
    else -> UiText(fallbackRes, message.orEmpty())
}
