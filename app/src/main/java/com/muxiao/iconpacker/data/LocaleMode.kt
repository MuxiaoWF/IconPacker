package com.muxiao.iconpacker.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用内语言：跟随系统 / 手动中文 / 手动英文。
 *
 * 值序列化到 SharedPreferences（见 [AppSettings]），
 * 新枚举项只追加不重排，避免历史值错位。
 *
 * 切换实现：[wrap] 在 `Activity.attachBaseContext` 阶段用
 * `createConfigurationContext` 注入手动选择的语言；手动切换后重建 Activity
 * 生效（见 SettingsScreen）。"跟随系统"不包裹——系统语言（含 Android 13+
 * 的应用级语言设置，manifest `localeConfig`）自然穿透到资源解析。
 *
 * 资源口径：`values/strings.xml` 为中文默认，`values-en` 为英文。
 */
enum class LocaleMode {
    FollowSystem,
    Chinese,
    English,
    ;

    /** 用该语言包裹 [context]；跟随系统时原样返回 */
    fun wrap(context: Context): Context {
        val locale = when (this) {
            FollowSystem -> return context
            Chinese -> Locale.SIMPLIFIED_CHINESE
            English -> Locale.ENGLISH
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    companion object {
        /** 解析存储值；未知值（旧版本或脏数据）回退跟随系统 */
        fun from(value: String?): LocaleMode =
            entries.firstOrNull { it.name == value } ?: FollowSystem
    }
}
