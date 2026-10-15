package com.muxiao.iconpacker.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 已安装产物查询（FR-08 版本对比）。
 *
 * 必须显式传包名：每个项目的产物包名由自己决定（见
 * `com.muxiao.iconpacker.core.packer.engine.PackageNameRules`），
 * 多个图标包共存时"装没装上"要逐个项目分别问——用单一常量查询会把所有项目
 * 都误报成同一个安装状态。
 */
class InstalledPackChecker(private val context: Context) {

    data class InstalledInfo(val versionCode: Long, val versionName: String?)

    fun query(packageName: String): InstalledInfo? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            InstalledInfo(code, info.versionName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
