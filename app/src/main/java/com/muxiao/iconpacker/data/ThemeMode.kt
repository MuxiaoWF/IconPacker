package com.muxiao.iconpacker.data

/**
 * 外观模式（跟随系统 / 手动浅色 / 手动深色）。
 *
 * 值序列化到 SharedPreferences（见 [AppSettings]）。
 * 枚举只能追加不能改名，避免历史值失联。
 */
enum class ThemeMode {
    FollowSystem,
    Light,
    Dark,
    ;

    companion object {
        /** 解析存储值；未知值（旧版本或脏数据）回退跟随系统 */
        fun from(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: FollowSystem
    }
}
