package com.muxiao.iconpacker.data

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已安装应用枚举（FR-04 映射工作台的数据源）。
 *
 * 合规说明（docs/android-verification.md）：不申请 QUERY_ALL_PACKAGES，依赖清单 `<queries>`
 * 的 MAIN/LAUNCHER 意图声明获取"有桌面图标的应用"——恰好是映射需求的全部范围。
 *
 * 条目 `component`：`ComponentInfo{包名/活动类}`，直接用作 appfilter 的 component 值
 */
class AppInfoProvider(private val context: Context) {

    data class AppEntry(
        val packageName: String,
        val activityName: String,
        val label: String,
        val component: String,
    )

    suspend fun loadLaunchableApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
        resolved.mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(context.packageManager).toString() }.getOrNull()
                ?: activityInfo.packageName
            AppEntry(
                packageName = activityInfo.packageName,
                activityName = activityInfo.name,
                label = label,
                component = "ComponentInfo{${activityInfo.packageName}/${activityInfo.name}}",
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** 应用图标（UI 展示用；打包不依赖它） */
    fun loadAppIcon(packageName: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
}
