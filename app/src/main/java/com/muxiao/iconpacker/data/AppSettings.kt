package com.muxiao.iconpacker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局默认值（SharedPreferences，非 Room：设置不属于任何项目，也无需迁移）。
 *
 * 现有字段都是"新建项目时预填什么"的口径：
 * - 版本名 / 签名者名：留空 = 维持各自引擎默认（见 `PackForm` 的折算）；
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
        /** 外观模式：跟随系统 / 浅色 / 深色（ThemeMode 序列化串） */
        val themeMode: ThemeMode = ThemeMode.FollowSystem,
        /** 应用内语言：跟随系统 / 中文 / 英文（LocaleMode 序列化串） */
        val localeMode: LocaleMode = LocaleMode.FollowSystem,
        /**
         * 新建项目是否默认使用导入的密钥库。
         *
         * 只预填"用哪种签名身份 + 哪个文件 + 哪个别名"，**不预填密码**：
         * 密码是一次性凭据，落进 SharedPreferences 等于给所有未来项目配一把通用钥匙，
         * 而 SharedPreferences 是明文 XML。密码在新建/编辑表单里输入。
         */
        val importedKeystore: Boolean = false,
        /** 默认密钥库文件在私有目录的全路径（空 = 未选择） */
        val keystorePath: String = "",
        /** 默认密钥别名 */
        val keystoreAlias: String = "",
        /**
         * 卸载产物前的一次性说明是否已被用户勾选"不再提示"。
         * 只影响提示展示，不影响卸载行为本身。
         */
        val uninstallHintDismissed: Boolean = false,
        /**
         * 每个项目保留的产物版本数：打包成功后更早的 `pack-v*.apk` 会被清理。
         * 只影响私有目录占用，不触碰已安装的应用。
         */
        val keepArtifacts: Int = IconFileStore.DEFAULT_KEEP_ARTIFACTS,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前产物保留数（仓库层打包后裁剪用，免得持流取值） */
    val keepArtifacts: Int get() = state.value.keepArtifacts

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 必须持有强引用：SharedPreferences 对监听器只持弱引用，
     * 直接传 lambda 会在 GC 后静默失效（表现为"设置写进磁盘了，界面不刷新"）。
     */
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            _state.value = read()
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun setVersionName(value: String) = edit { it.putString(KEY_VERSION_NAME, value.trim()) }

    fun setSignerName(value: String) = edit { it.putString(KEY_SIGNER_NAME, value.trim()) }

    fun setSchemeV1(value: Boolean) = edit { it.putBoolean(KEY_SCHEME_V1, value) }

    fun setSchemeV2(value: Boolean) = edit { it.putBoolean(KEY_SCHEME_V2, value) }

    fun setSchemeV3(value: Boolean) = edit { it.putBoolean(KEY_SCHEME_V3, value) }

    fun setArtifactTemplate(value: String) = edit { it.putString(KEY_ARTIFACT_TEMPLATE, value.trim()) }

    fun setThemeMode(value: ThemeMode) = edit { it.putString(KEY_THEME_MODE, value.name) }

    fun setLocaleMode(value: LocaleMode) = edit { it.putString(KEY_LOCALE_MODE, value.name) }

    fun setImportedKeystore(value: Boolean) = edit { it.putBoolean(KEY_IMPORTED_KEYSTORE, value) }

    fun setKeystorePath(value: String) = edit { it.putString(KEY_KEYSTORE_PATH, value) }

    fun setKeystoreAlias(value: String) = edit { it.putString(KEY_KEYSTORE_ALIAS, value.trim()) }

    /** 勾选"不再提示卸载说明" */
    fun setUninstallHintDismissed(value: Boolean) = edit { it.putBoolean(KEY_UNINSTALL_HINT, value) }

    /** 产物保留版本数；落盘前钳制到合法区间，界面只提供其中的几档 */
    fun setKeepArtifacts(value: Int) = edit {
        it.putInt(KEY_KEEP_ARTIFACTS, value.coerceIn(MIN_KEEP_ARTIFACTS, MAX_KEEP_ARTIFACTS))
    }

    /** 清除默认签名身份：回到设备端生成密钥 */
    fun clearKeystore() = edit {
        it.putBoolean(KEY_IMPORTED_KEYSTORE, false)
        it.putString(KEY_KEYSTORE_PATH, "")
        it.putString(KEY_KEYSTORE_ALIAS, "")
    }

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
        themeMode = ThemeMode.from(prefs.getString(KEY_THEME_MODE, null)),
        localeMode = LocaleMode.from(prefs.getString(KEY_LOCALE_MODE, null)),
        importedKeystore = prefs.getBoolean(KEY_IMPORTED_KEYSTORE, false),
        keystorePath = prefs.getString(KEY_KEYSTORE_PATH, "").orEmpty(),
        keystoreAlias = prefs.getString(KEY_KEYSTORE_ALIAS, "").orEmpty(),
        uninstallHintDismissed = prefs.getBoolean(KEY_UNINSTALL_HINT, false),
        keepArtifacts = prefs.getInt(KEY_KEEP_ARTIFACTS, IconFileStore.DEFAULT_KEEP_ARTIFACTS)
            .coerceIn(MIN_KEEP_ARTIFACTS, MAX_KEEP_ARTIFACTS),
    )

    companion object {
        private const val PREFS_NAME = "app_settings"

        /**
         * 只读语言设置，供 `MainActivity.attachBaseContext` 使用——
         * 那一刻不能实例化完整 [AppSettings]（会多挂一个无谓的 prefs listener）。
         */
        fun readLocaleMode(context: Context): LocaleMode =
            LocaleMode.from(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LOCALE_MODE, null),
            )

        /** 导出建议名的默认模板：与设置页出现前的行为逐字节一致 */
        const val DEFAULT_ARTIFACT_TEMPLATE = "IconPack-{name}-v{code}.apk"

        private const val KEY_VERSION_NAME = "default_version_name"
        private const val KEY_SIGNER_NAME = "default_signer_name"
        private const val KEY_SCHEME_V1 = "default_scheme_v1"
        private const val KEY_SCHEME_V2 = "default_scheme_v2"
        private const val KEY_SCHEME_V3 = "default_scheme_v3"
        private const val KEY_ARTIFACT_TEMPLATE = "artifact_template"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LOCALE_MODE = "locale_mode"
        private const val KEY_IMPORTED_KEYSTORE = "default_imported_keystore"
        private const val KEY_KEYSTORE_PATH = "default_keystore_path"
        private const val KEY_KEYSTORE_ALIAS = "default_keystore_alias"
        private const val KEY_UNINSTALL_HINT = "uninstall_hint_dismissed"
        private const val KEY_KEEP_ARTIFACTS = "keep_artifacts"

        /** 产物保留数的合法区间（界面档位是其中的子集） */
        const val MIN_KEEP_ARTIFACTS = 1
        const val MAX_KEEP_ARTIFACTS = 10
    }
}
