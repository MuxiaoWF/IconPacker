package com.muxiao.iconpacker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局默认值（SharedPreferences，非 Room：设置不属于任何项目，也无需迁移）。
 *
 * 现有字段都是"新建项目时预填什么"的口径：
 * - 版本名 / 签名者名：留空 = 维持各自引擎默认（见 [PackForm] 的折算）；
 * - 签名方案：新建项目的初始开关状态（编辑旧行为默认全开）；
 * - 产物文件名模板：导出 SAF 对话框的建议文件名，占位符 `{name}`/`{version}`/`{code}`。
 *
 * 读写分离：写走单个 setter（逐项落盘），读走 [state] 快照——UI 收集 [state] 即可
 * 响应式跟随，不必关心 SharedPreferences 的写入时机。
 *
 * 注意 listener 持有 [context]：约定传入 Application 上下文（见 ServiceLocator）。
 */
class AppSettings(context: Context) {

    data class State(
        /** 新建表单预填的默认版本名；空白 = 沿用模板默认（TemplateInfo） */
        val versionName: String = "",
        /** 新建表单预填的默认签名者名；空白 = 沿用引擎默认 */
        val signerName: String = "",
        val schemeV1: Boolean = true,
        val schemeV2: Boolean = true,
        val schemeV3: Boolean = true,
        /** 导出产物建议文件名模板；占位符 {name}/{version}/{code}，非法字符会被过滤 */
        val artifactTemplate: String = DEFAULT_ARTIFACT_TEMPLATE,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener { _, _ -> _state.value = read() }
    }

    fun setVersionName(value: String) = edit { it.putString(KEY_VERSION_NAME, value.trim()) }

    fun setSignerName(value: String) = edit { it.putString(KEY_SIGNER_NAME, value.trim()) }

    fun setSchemeV1(value: Boolean) = edit { it.putBoolean(KEY_SCHEME_V1, value) }

    fun setSchemeV2(value: Boolean) = edit { it.putBoolean(KEY_SCHEME_V2, value) }

    fun setSchemeV3(value: Boolean) = edit { it.putBoolean(KEY_SCHEME_V3, value) }

    fun setArtifactTemplate(value: String) = edit { it.putString(KEY_ARTIFACT_TEMPLATE, value.trim()) }

    private inline fun edit(block: (android.content.SharedPreferences.Editor) -> Unit) {
        prefs.edit().also(block).apply()
    }

    private fun read(): State = State(
        versionName = prefs.getString(KEY_VERSION_NAME, "").orEmpty(),
        signerName = prefs.getString(KEY_SIGNER_NAME, "").orEmpty(),
        schemeV1 = prefs.getBoolean(KEY_SCHEME_V1, true),
        schemeV2 = prefs.getBoolean(KEY_SCHEME_V2, true),
        schemeV3 = prefs.getBoolean(KEY_SCHEME_V3, true),
        artifactTemplate = prefs.getString(KEY_ARTIFACT_TEMPLATE, DEFAULT_ARTIFACT_TEMPLATE)
            .orEmpty().ifBlank { DEFAULT_ARTIFACT_TEMPLATE },
    )

    companion object {
        private const val PREFS_NAME = "app_settings"

        /** 导出建议名的默认模板：与设置页出现前的行为逐字节一致 */
        const val DEFAULT_ARTIFACT_TEMPLATE = "IconPack-{name}-v{code}.apk"

        private const val KEY_VERSION_NAME = "default_version_name"
        private const val KEY_SIGNER_NAME = "default_signer_name"
        private const val KEY_SCHEME_V1 = "default_scheme_v1"
        private const val KEY_SCHEME_V2 = "default_scheme_v2"
        private const val KEY_SCHEME_V3 = "default_scheme_v3"
        private const val KEY_ARTIFACT_TEMPLATE = "artifact_template"
    }
}
