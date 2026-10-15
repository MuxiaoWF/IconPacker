package com.muxiao.iconpacker.domain

import com.muxiao.iconpacker.core.packer.engine.IconPackManifest
import com.muxiao.iconpacker.core.packer.engine.PackageNameRules

/**
 * 用户手输的映射目标解析（FR-04「编辑映射包名」）。
 *
 * 映射在 appfilter 里必须是 `ComponentInfo{包名/活动类}`，而用户手上通常只有包名。
 * 这里把用户可能写出的几种形式都收进来：
 *
 * | 输入 | 结果 |
 * | --- | --- |
 * | `com.foo.bar` | 取设备上该应用的**真实启动组件** |
 * | `com.foo.bar/.MainActivity` | 补全类名后采用（应用未安装时也接受） |
 * | `com.foo.bar/com.foo.Alias` | 原样采用 |
 * | `ComponentInfo{...}` | 剥壳后同上 |
 *
 * 两条刻意定下的规则：
 *
 * 1. **已安装的应用一律以设备值为准。** 用户写的活动类可能指向别名 Activity 或已经过时，
 *  只有设备上的真实启动组件保证匹配得上。界面上也会实时回显最终采用的组件，
 *  不让人猜"我填的到底生效了没有"。
 * 2. **纯包名且本机未安装 → 拒绝**，而不是替用户编一个 `.MainActivity`。
 *  编出来的组件名永远匹配不上，只会在产物里留一条死条目——映射在界面上赫然存在、
 *  桌面上却毫无反应，这是最难排查的一类问题。要让未安装的应用先占位，
 *  走 ZIP 导入的 appfilter（那里有作者写的真实组件名）。
 */
object MappingTarget {

    /** 解析结果 */
    sealed interface Resolution {
        /**
         * @param installed 目标应用是否已在本机安装。false 表示这是一条预置映射，
         *  应用装上后由 [MappingReconciler] 校准为真实组件。
         */
        data class Resolved(
            val component: String,
            val packageName: String,
            val installed: Boolean,
        ) : Resolution

        /** 输入不可用；[message] 直接面向用户 */
        data class Invalid(val message: String) : Resolution
    }

    private const val COMPONENT_INFO = "ComponentInfo"

    /**
     * 解析用户输入。
     *
     * @param installed 本机已安装的可启动应用（用于把包名换成真实组件）
     */
    fun resolve(input: String, installed: List<ImportBinder.AppItem>): Resolution {
        val raw = unwrap(input)
        if (raw.isEmpty()) return Resolution.Invalid("请输入包名，或点「从应用列表选择」")

        val slash = raw.indexOf('/')
        val packageName = (if (slash < 0) raw else raw.substring(0, slash)).trim()
        val typedActivity = if (slash < 0) "" else raw.substring(slash + 1).trim()

        PackageNameRules.validate(packageName)?.let { return Resolution.Invalid(it.message) }

        // 已安装 → 用设备上的真实启动组件（见类文档第 1 条）
        installed.firstOrNull { it.packageName == packageName }?.let {
            return Resolution.Resolved(it.component, packageName, installed = true)
        }

        if (typedActivity.isEmpty()) {
            return Resolution.Invalid(
                "本机没有安装 $packageName，无法确定它的启动入口。" +
                    "可以先安装该应用，或补全成组件形式（如 $packageName/.MainActivity）。",
            )
        }
        if (!isValidActivity(typedActivity, packageName)) {
            return Resolution.Invalid("活动类名不合法，只能使用英文字母、数字、下划线和点")
        }

        return Resolution.Resolved(
            component = "$COMPONENT_INFO{$packageName/${qualify(packageName, typedActivity)}}",
            packageName = packageName,
            installed = false,
        )
    }

    /** 从 component 反推包名（界面回显用）；解析不出返回原串 */
    fun packageOf(component: String): String =
        IconPackManifest.packageOfComponent(component) ?: component

    /**
     * 从既有 component 反推**可编辑形式**（图标库/映射列表的编辑框初值）。
     *
     * 剥掉 `ComponentInfo{}` 外壳、保留 `包名/活动类`：外壳对用户是噪音，
     * 而活动类必须留着——纯包名遇到未安装的目标会被 [resolve] 拒绝，
     * 把原有的活动类一并抹掉等于逼用户重新手打一遍。
     *
     * 预置映射若本身就没写活动类（部分图标包的 appfilter 写成 `ComponentInfo{包名/}`），
     * 回填结果就是 `包名/`，编辑框会直接给出"补全成组件形式"的提示——
     * 这正是需要用户补的那一步，而不是把残缺值藏起来。
     */
    fun editableOf(component: String): String = unwrap(component)

    // ---- 内部 ----

    /** 剥掉 `ComponentInfo{...}` 外壳；容忍只粘贴了内层、或带首尾空白 */
    private fun unwrap(input: String): String {
        var s = input.trim()
        if (s.startsWith(COMPONENT_INFO)) s = s.removePrefix(COMPONENT_INFO).trim()
        if (s.startsWith("{")) s = s.removePrefix("{")
        if (s.endsWith("}")) s = s.removeSuffix("}")
        return s.trim()
    }

    /**
     * 把短形式的类名补全。
     *
     * `.MainActivity` → `包名.MainActivity`；`MainActivity`（无点）也按包名下的类处理；
     * 已经是完整类名的原样保留。
     */
    private fun qualify(packageName: String, activity: String): String = when {
        activity.startsWith(".") -> packageName + activity
        '.' !in activity -> "$packageName.$activity"
        else -> activity
    }

    /**
     * 活动类名校验。
     *
     * 比包名宽松：类名允许 `$`（内部类与 Android 生成类常见，如
     * `com.foo.MainActivity$Entry`），这在包名里是不合法的。
     */
    private fun isValidActivity(activity: String, packageName: String): Boolean {
        val full = qualify(packageName, activity)
        if (full.length > PackageNameRules.MAX_LENGTH) return false
        return full.split('.').all { segment ->
            segment.isNotEmpty() &&
                (isLetter(segment[0]) || segment[0] == '_' || segment[0] == '$') &&
                segment.all { isLetter(it) || it in '0'..'9' || it == '_' || it == '$' }
        }
    }

    private fun isLetter(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z')
}
