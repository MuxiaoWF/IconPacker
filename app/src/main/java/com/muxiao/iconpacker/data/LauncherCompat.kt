package com.muxiao.iconpacker.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 启动器兼容性探测（FR-01）。
 *
 * 实现说明：
 * - 以"已安装的 HOME 启动器"为枚举源（[queryIntentActivities] 查 MAIN+HOME），
 *   再用 [LauncherSupport] 按包名分类兼容性——这是可靠的主路径。
 *   早期实现只查 `org.adw.ActivityStarter.THEMES`，而 ColorOS/One UI/MIUI 等
 *   OEM 启动器不注册该动作，导致被误判为"不支持"。
 * - 未知启动器再退回意图探测（见 [themeQueryIntent]）。
 *
 * 合规：MAIN/LAUNCHER 与 HOME 意图已在清单 `<queries>` 中声明，Android 11+
 * 包可见性过滤下仍可正确返回（否则当前启动器解析为 null，会误判"不支持"）。
 */
class LauncherCompat(private val context: Context) {

    data class LauncherInfo(
        val packageName: String,
        val label: String,
        val isCurrent: Boolean,
        val isKnown: Boolean,
        val supportLevel: LauncherSupport.SupportLevel,
        val guidance: String? = null,
    )

    /** 当前默认启动器包名（解析不到返回 null，如刚开机/多用户异常态） */
    fun currentLauncherPackage(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    /**
     * 已安装且可作为 HOME 的启动器列表，按 [LauncherSupport] 分类兼容性。
     * 排除本应用自身生成的图标包包名（[LauncherSupport.OWN_PACKAGE]）。
     */
    suspend fun detectIconPackLaunchers(): List<LauncherInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val current = currentLauncherPackage()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = pm.queryIntentActivities(home, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrNull() ?: pkg
                ResolvedLauncher(pkg, label)
            }
            .distinctBy { it.packageName }
        classifyLaunchers(resolved, current)
    }

    /**
     * 纯分类函数（无 Android 依赖，便于 JVM 单测）。
     * 把"原始启动器列表 + 当前包名"映射为带兼容性等级的 [LauncherInfo]。
     */
    fun classifyLaunchers(raw: List<ResolvedLauncher>, currentPackage: String?): List<LauncherInfo> =
        Companion.classifyLaunchers(raw, currentPackage)

    /** 当前启动器是否支持图标包（供快速判断 / 引导文案） */
    fun currentSupportLevel(): LauncherSupport.SupportLevel {
        val current = currentLauncherPackage() ?: return LauncherSupport.SupportLevel.UNKNOWN
        val known = LauncherSupport.supportLevelFor(current)
        if (known != LauncherSupport.SupportLevel.UNKNOWN) return known
        // 未知包：退回意图探测（声明了标准图标包动作的启动器视为支持）
        val hit = context.packageManager
            .queryIntentActivities(themeQueryIntent(), 0)
            .any { it.activityInfo?.packageName == current }
        return if (hit) LauncherSupport.SupportLevel.SUPPORTED else LauncherSupport.SupportLevel.UNKNOWN
    }

    fun currentGuidance(): String? {
        val current = currentLauncherPackage() ?: return null
        return LauncherSupport.classify(current)?.guidance
    }

    /** 打开系统"默认启动器/主屏幕应用"设置页 */
    fun defaultLauncherSettingsIntent(): Intent =
        Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 原始启动器（包名 + 展示名），用于 [classifyLaunchers] 的输入 */
    data class ResolvedLauncher(val packageName: String, val label: String)

    companion object {
        /**
         * ADW 系通行识别动作（启动器扫描图标包的事实标准，Kvaesitso 源码
         * AppFilterIconPackInstaller / KISS / Arcticons 等事实标准图标包均按此声明），
         * 仅作未知启动器兜底探测。
         *
         * 历史教训：旧值 `org.adw.ActivityStarter.THEMES` 是只被 Kvaesitso 一家
         * 额外扫描的兼容动作，ADW 标准串是 `org.adw.launcher.THEMES`——用错串
         * 会导致兜底探测恒空（UNKNOWN），且该错误曾同步蔓延到 app 清单的
         * <queries> 与模板产物清单，造成产物在主流桌面上不被识别为图标包。
         */
        const val THEME_ACTION = "org.adw.launcher.THEMES"

        /**
         * 纯分类函数（无 Android 依赖，便于 JVM 单测）。
         * 把"原始启动器列表 + 当前包名"映射为带兼容性等级的 [LauncherInfo]。
         */
        fun classifyLaunchers(raw: List<ResolvedLauncher>, currentPackage: String?): List<LauncherInfo> {
            return raw
                .filter { !LauncherSupport.isOwnPackage(it.packageName) }
                .map { r ->
                    val profile = LauncherSupport.classify(r.packageName)
                    LauncherInfo(
                        packageName = r.packageName,
                        label = profile?.displayName ?: r.label,
                        isCurrent = r.packageName == currentPackage,
                        isKnown = profile != null,
                        supportLevel = profile?.supportLevel ?: LauncherSupport.SupportLevel.UNKNOWN,
                        guidance = profile?.guidance,
                    )
                }
                .sortedBy { it.label }
        }

        /**
         * 带 DEFAULT 分类的探测意图：filter 的 category 必须包含意图的全部 category
         * （意图带 DEFAULT ⇒ 只有声明了 DEFAULT 的 filter 能被命中），恰好与
         * "action + DEFAULT" 的标准图标包识别 filter 形态对齐。
         */
        fun themeQueryIntent(): Intent =
            Intent(THEME_ACTION).addCategory(Intent.CATEGORY_DEFAULT)
    }
}
