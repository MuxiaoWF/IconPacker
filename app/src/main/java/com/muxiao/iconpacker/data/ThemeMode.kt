package com.muxiao.iconpacker.data

/**
 * 主题外观模式：跟随系统 / 手动浅色 / 手动深色。
 *
 * 值序列化到 SharedPreferences（见 [AppSettings]），
 * 新枚举项只追加不重排，避免历史值错位。
 */
enum class ThemeMode(val label: String) {
    FollowSystem("跟随系统"),
    Light("浅色"),
    Dark("深色"),
    ;

    companion object {
        /** 解析存储值；未知值（旧版本或脏数据）回退跟随系统 */
        fun from(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: FollowSystem
    }
}
