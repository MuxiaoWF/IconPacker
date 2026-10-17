package com.muxiao.iconpacker.data

import androidx.annotation.StringRes
import com.muxiao.iconpacker.R

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
        /**
         * 本地化展示名资源 id（优先于 [displayName] 展示）；
         * 专有名词启动器名（Nova Launcher 等）不提供，直接用 [displayName]。
         */
        @StringRes val nameRes: Int? = null,
        /** 引导文案资源 id（用户可见，随语言切换）；无引导文案为 null */
        @StringRes val guidanceRes: Int? = null,
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
        "ch.deletescape.launcher" to LauncherProfile(
            "Lawnchair",
            SupportLevel.SUPPORTED,
            nameRes = R.string.launcher_name_lawnchair_legacy,
        ),
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
            nameRes = R.string.launcher_name_coloros,
            guidanceRes = R.string.launcher_guide_coloros,
        ),
        "com.oppo.launcher" to LauncherProfile(
            "OPPO 启动器",
            SupportLevel.LIMITED,
            nameRes = R.string.launcher_name_oppo,
            guidanceRes = R.string.launcher_guide_oppo,
        ),
        // realme UI 默认不支持直接应用标准图标包（GeekChamp 实测）
        "com.realme.launcher" to LauncherProfile(
            "realme 启动器",
            SupportLevel.LIMITED,
            nameRes = R.string.launcher_name_realme,
            guidanceRes = R.string.launcher_guide_realme,
        ),
        // 通用 AOSP 包名（部分 ROM 以该包名提供系统桌面）。注意：**本机 OPPO PJU110 的
        // ColorOS 桌面正是 com.android.launcher**——LIMITED 文案必须与 ColorOS 实测一致。
        "com.android.launcher" to LauncherProfile(
            "系统启动器",
            SupportLevel.LIMITED,
            nameRes = R.string.launcher_name_system,
            guidanceRes = R.string.launcher_guide_android,
        ),
        "com.sec.android.app.launcher" to LauncherProfile(
            "One UI Home",
            SupportLevel.LIMITED,
            guidanceRes = R.string.launcher_guide_oneui,
        ),
        "com.google.android.apps.nexuslauncher" to LauncherProfile(
            "Pixel Launcher",
            SupportLevel.LIMITED,
            guidanceRes = R.string.launcher_guide_pixel,
        ),
        "com.miui.home" to LauncherProfile(
            "MIUI / HyperOS 启动器",
            SupportLevel.LIMITED,
            nameRes = R.string.launcher_name_miui,
            guidanceRes = R.string.launcher_guide_miui,
        ),
        "com.huawei.android.launcher" to LauncherProfile(
            "华为启动器",
            SupportLevel.LIMITED,
            nameRes = R.string.launcher_name_huawei,
            guidanceRes = R.string.launcher_guide_huawei,
        ),
        "com.vivo.launcher" to LauncherProfile(
            "vivo 启动器",
            SupportLevel.LIMITED,
            nameRes = R.string.launcher_name_vivo,
            guidanceRes = R.string.launcher_guide_vivo,
        ),
        "com.meizu.flyme.launcher" to LauncherProfile(
            "魅族启动器",
            SupportLevel.LIMITED,
            nameRes = R.string.launcher_name_meizu,
            guidanceRes = R.string.launcher_guide_meizu,
        ),
    )

    fun classify(packageName: String?): LauncherProfile? =
        if (packageName == null) null else KNOWN[packageName]

    fun supportLevelFor(packageName: String?): SupportLevel =
        classify(packageName)?.supportLevel ?: SupportLevel.UNKNOWN
}
