package com.muxiao.iconpacker.data

/**
 * 启动器兼容性知识库（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 背景：早期实现仅用 `queryIntentActivities(org.adw.ActivityStarter.THEMES)` 探测——
 * 该动作只有 ADW 系启动器注册，ColorOS / One UI / MIUI 等 OEM 启动器不注册它
 * （它们走自有主题引擎），于是被误判为"不支持"。但 曾据"ColorOS 11+ 更新日志
 * 写明支持三方图标包"把 ColorOS 标为 SUPPORTED——** 实测（PJU110 /
 * ColorOS 14.0.63 CN）推翻**：其图标功能走 ART+/主题商店体系，不支持第三方图标包
 * APK（证据链见 `com.coloros.launcher` 条目注释），已降回 LIMITED。
 *
 * 做法：以"包名 → 兼容性画像"为主，覆盖已知第三方图标包启动器与 OEM 启动器；
 * 未知启动器交给 [LauncherCompat] 的意图探测兜底（见 [LauncherCompat.themeQueryIntent]）。
 */
object LauncherSupport {

    /** 兼容性等级 */
    enum class SupportLevel {
        /** 标准第三方图标包协议，可直接应用 */
        SUPPORTED,

        /** 自带主题引擎，可能支持也可能不支持标准图标包，需用户在启动器设置中尝试 */
        LIMITED,

        /** 未知：无包名画像，也无意图命中 */
        UNKNOWN,
    }

    data class LauncherProfile(
        val displayName: String,
        val supportLevel: SupportLevel,
        val guidance: String? = null,
    )

    /** 本应用自身生成的图标包包名（探测时须排除，避免把产物当成启动器） */
    const val OWN_PACKAGE = "com.muxiao.icon"

    fun isOwnPackage(packageName: String?): Boolean = packageName == OWN_PACKAGE

    /**
     * 已知启动器画像。
     * 第三方图标包启动器 → [SupportLevel.SUPPORTED]；OEM 启动器 → [SupportLevel.LIMITED]（含引导文案）。
     * 包名来源：各启动器官方包名 + 常见 OEM 系统桌面包名。
     */
    val KNOWN: Map<String, LauncherProfile> = mapOf(
        // ---- 第三方图标包启动器（标准协议）----
        "com.teslacoilsw.launcher" to LauncherProfile("Nova Launcher", SupportLevel.SUPPORTED),
        "app.lawnchair" to LauncherProfile("Lawnchair", SupportLevel.SUPPORTED),
        "ch.deletescape.launcher" to LauncherProfile("Lawnchair（旧版）", SupportLevel.SUPPORTED),
        "com.anddoes.launcher" to LauncherProfile("Apex Launcher", SupportLevel.SUPPORTED),
        "com.microsoft.launcher" to LauncherProfile("Microsoft Launcher", SupportLevel.SUPPORTED),
        "com.gau.go.launcherex" to LauncherProfile("GO Launcher EX", SupportLevel.SUPPORTED),
        "org.adw.launcher" to LauncherProfile("ADW Launcher", SupportLevel.SUPPORTED),
        "org.adwfreak.launcher" to LauncherProfile("ADW EX", SupportLevel.SUPPORTED),
        "ginlemon.smartlauncher" to LauncherProfile("Smart Launcher", SupportLevel.SUPPORTED),
        "com.actionlauncher.playstore" to LauncherProfile("Action Launcher", SupportLevel.SUPPORTED),
        "com.chrislacy.actionlauncher" to LauncherProfile("Action Launcher", SupportLevel.SUPPORTED),
        "de.mm20.launcher" to LauncherProfile("Kvaesitso", SupportLevel.SUPPORTED),
        "projekt.launcher" to LauncherProfile("Hyperion Launcher", SupportLevel.SUPPORTED),

        // ---- OEM 启动器 ----
        // ColorOS 14（CN，PJU110 / 14.0.63）实测（逆向 OplusLauncher.apk +
        // 运行时日志）：其「壁纸与个性化 → 图标」只有 ART+ 图标（主题商店的设计师重绘）
        // 与内置风格，**没有应用第三方图标包 APK 的入口**；桌面 UxIconPackLoader 虽保留
        // appfilter 加载代码（res/xml → assets 兜底），但启动链路从不触发，
        // Settings.System.icon_pack_name 亦不被消费。故降为 LIMITED 并明示结论。
        "com.coloros.launcher" to LauncherProfile(
            "ColorOS 启动器",
            SupportLevel.LIMITED,
            "ColorOS 的「图标」功能走 ART+/主题商店体系，暂不支持直接应用第三方图标包 APK" +
                "（ColorOS 14 实测）。建议安装 Nova Launcher 或 Lawnchair 后启用本图标包。",
        ),
        "com.oppo.launcher" to LauncherProfile(
            "OPPO 启动器",
            SupportLevel.LIMITED,
            "OPPO 桌面与 ColorOS 同源，暂不支持直接应用第三方图标包 APK。建议安装 Nova Launcher 或 Lawnchair。",
        ),
        // realme UI 默认不支持直接应用标准图标包（GeekChamp 实测）
        "com.realme.launcher" to LauncherProfile(
            "realme 启动器",
            SupportLevel.LIMITED,
            "realme UI 默认不支持直接应用标准第三方图标包。建议安装 Nova Launcher 或 Lawnchair 以获得完整图标包支持。",
        ),
        // 通用 AOSP 包名（部分 ROM 以该包名提供系统桌面）。注意：**本机 OPPO PJU110 的
        // ColorOS 桌面正是 com.android.launcher**——LIMITED 文案必须与 ColorOS 实测一致。
        "com.android.launcher" to LauncherProfile(
            "系统启动器",
            SupportLevel.LIMITED,
            "该启动器可能使用自有主题机制（OPPO ColorOS 14 实测不支持第三方图标包 APK）。" +
                "建议安装 Nova Launcher 或 Lawnchair 以获得最佳兼容性。",
        ),
        "com.sec.android.app.launcher" to LauncherProfile(
            "One UI Home",
            SupportLevel.LIMITED,
            "One UI 使用 Galaxy Themes 主题机制，不直接应用标准第三方图标包。建议安装 Nova Launcher 或 Lawnchair。",
        ),
        "com.google.android.apps.nexuslauncher" to LauncherProfile(
            "Pixel Launcher",
            SupportLevel.LIMITED,
            "Pixel Launcher 不支持标准第三方图标包。建议安装 Nova Launcher 或 Lawnchair。",
        ),
        "com.miui.home" to LauncherProfile(
            "MIUI / HyperOS 启动器",
            SupportLevel.LIMITED,
            "小米启动器使用自有主题商店，不直接应用标准第三方图标包。建议安装 Nova Launcher 或 Lawnchair。",
        ),
        "com.huawei.android.launcher" to LauncherProfile(
            "华为启动器",
            SupportLevel.LIMITED,
            "华为启动器使用自有主题机制，不直接应用标准第三方图标包。建议安装 Nova Launcher 或 Lawnchair。",
        ),
        "com.vivo.launcher" to LauncherProfile(
            "vivo 启动器",
            SupportLevel.LIMITED,
            "vivo 启动器使用主题应用，不直接应用标准第三方图标包。建议安装 Nova Launcher 或 Lawnchair。",
        ),
        "com.meizu.flyme.launcher" to LauncherProfile(
            "魅族启动器",
            SupportLevel.LIMITED,
            "魅族启动器使用自有主题机制，不直接应用标准第三方图标包。建议安装 Nova Launcher 或 Lawnchair。",
        ),
    )

    fun classify(packageName: String?): LauncherProfile? =
        if (packageName == null) null else KNOWN[packageName]

    fun supportLevelFor(packageName: String?): SupportLevel =
        classify(packageName)?.supportLevel ?: SupportLevel.UNKNOWN
}
