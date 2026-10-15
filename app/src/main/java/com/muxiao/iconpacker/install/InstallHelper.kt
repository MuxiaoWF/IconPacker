package com.muxiao.iconpacker.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 安装与分享辅助（FR-07）。
 *
 * 产物 APK 位于应用私有目录，对外暴露必须经 FileProvider
 * （authorities = <applicationId>.fileprovider，见 res/xml/file_paths.xml）。
 */
object InstallHelper {

    const val MIME_APK = "application/vnd.android.package-archive"

    fun apkUri(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)

    /** 系统安装器安装意图（调用方需先确认 [canInstallPackages]） */
    fun installIntent(context: Context, apk: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri(context, apk), MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * 是否已授权"安装未知应用"。
     * API 26 起为 per-app 权限；API 24/25 无 per-app 概念（全局开关），直接放行。
     */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳转授权页：API 26+ 为本应用专页，低版本回退安全设置总页 */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    fun shareIntent(context: Context, apk: File): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME_APK
            putExtra(Intent.EXTRA_STREAM, apkUri(context, apk))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** 当前默认启动器包名（FR-07 引导用） */
    fun currentLauncherPackage(context: Context): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolve?.activityInfo?.packageName
    }

    /** 打开当前启动器 App 本体（引导用户自行进入图标样式设置） */
    fun openLauncherAppIntent(context: Context): Intent? {
        val pkg = currentLauncherPackage(context) ?: return null
        return context.packageManager.getLaunchIntentForPackage(pkg)
    }

    // ---------- Android 开发者验证（Google，2026 起）----------

    /**
     * Google 开发者验证系统服务的包名（见 docs/android-verification.md）。
     * 该服务只存在于**已认证 GMS 设备**上；国内无 GMS 的机器、AOSP ROM 均不存在，
     * 因此本方法同时充当"当前设备是否受验证约束"的判定依据。
     */
    private const val VERIFIER_PACKAGE = "com.google.android.verifier"

    /**
     * 当前设备是否受 Android 开发者验证约束。
     *
     * 判定依据是 [VERIFIER_PACKAGE] 是否安装——它是 Google 系统服务，随 GMS 下发，
     * 不存在即意味着这台设备不会执行验证拦截。
     *
     * 注意：必须在清单 `<queries>` 中声明该包名，否则 Android 11+ 的包可见性
     * 限制会让查询恒返回 false（见 AndroidManifest.xml）。
     */
    fun isDeveloperVerificationActive(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                VERIFIER_PACKAGE,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(VERIFIER_PACKAGE, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * 高级流程（Advanced Flow）引导入口。
     *
     * Google 未公开该流程的深链 Intent，因此只跳到系统设置总页，具体路径交由
     * UI 文案指引（设置 → 系统 → Android 开发者身份验证工具）。若未来出现公开
     * action，替换此处即可，调用方无需改动。
     */
    fun advancedFlowIntent(): Intent = Intent(Settings.ACTION_SETTINGS)
}
