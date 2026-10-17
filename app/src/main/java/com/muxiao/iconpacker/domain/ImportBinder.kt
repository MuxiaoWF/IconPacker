package com.muxiao.iconpacker.domain

import com.muxiao.iconpacker.core.packer.engine.IconMatchKeys
import com.muxiao.iconpacker.core.packer.engine.IconPackManifest

/**
 * 导入即建立映射（FR-03 × FR-04）。纯 Kotlin，可 JVM 单测。
 *
 * 分两层，可靠性完全不同——这条界线决定了"哪些能自动、哪些必须人工确认"：
 *
 * - **L1 [Source.MANIFEST]**：读 ZIP 内 `appfilter.xml` 的权威映射。包作者写下的
 *  `组件 → 图标名` 对应关系，零猜测。**不要求目标应用已安装**——组件名就在条目里，
 *  哪怕应用还没装，也应当先把映射占好位（见 [Binding.installed]）。
 * - **L2 [Source.PACKAGE_NAME]**：图标名/目录名恰好等于包名。属于**确定性**判据
 *  （规范化后完全相等），不是相似度猜测，故同样可以自动应用。**仅限已安装应用**：
 *  component 必须是 `包名/活动类`（Lawnchair 与 Kvaesitso 的规范都如此，短形式
 *  `.Activity` 也要求类名以包名为前缀），未安装时无从得知类名，凭空编一个只会在
 *  产物里留下永远匹配不上的死条目。这类图不会丢——它们仍在图标池里，
 *  应用装上后由「一键自动匹配」接手。
 * - **模糊匹配**（标签包含、首字母、词元重叠）**刻意不在这里**：那是概率判断，
 *  错配比不匹配更让人困惑，必须由用户点"一键自动匹配"主动触发。
 *  （见 [AppMatcher]）
 *
 * 安全约束：不覆盖用户已有的映射（[skipComponents]）。
 */
object ImportBinder {

    /** 绑定依据 */
    enum class Source { MANIFEST, PACKAGE_NAME }

    /**
     * 待绑定的图标。
     *
     * @param index 在本次导入批次中的下标（调用方据此换取槽位）
     * @param fileName 落地文件名（不含目录），用于与 appfilter 的 drawable 名对齐
     * @param matchKey 由 `ZipIconPlanner` 推导的匹配键（优先包名，其次有意义的目录名）
     */
    data class IconItem(val index: Int, val fileName: String, val matchKey: String)

    /**
     * 设备上已安装的可启动应用。
     *
     * @param component 设备上**真实**的启动 Activity。它可能与图标包作者写的不一致
     *  （不同 ROM / 应用版本会换启动 Activity，或用别名 Activity），
     *  因此已安装场景一律以它为准，只按包名去认。
     */
    data class AppItem(val component: String, val packageName: String)

    /**
     * 一条自动绑定。
     *
     * @param installed 目标应用当前是否已安装。false 表示这是一条**预置映射**：
     *  图标包先替尚未安装的应用占好位，等应用装上即可生效——
     *  这是"导入了 800 个应用的图标、手机只装了 300 个"时，
     *  剩下 500 个不被白白丢掉的原因。
     */
    data class Binding(
        val component: String,
        val iconIndex: Int,
        val source: Source,
        val installed: Boolean,
    )

    /**
     * @param manifestEntries 包内 appfilter 的条目；非图标包 ZIP 传空列表
     * @param skipComponents 已被用户手动映射的组件——自动绑定一律让位，不覆盖人工结果
     */
    fun bind(
        manifestEntries: List<IconPackManifest.Entry>,
        icons: List<IconItem>,
        apps: List<AppItem>,
        skipComponents: Set<String> = emptySet(),
    ): List<Binding> {
        // apps 允许为空：没有已安装应用时，L1 仍应能靠 appfilter 建立预置映射
        if (icons.isEmpty()) return emptyList()

        val appByPackage = LinkedHashMap<String, AppItem>()
        for (app in apps) appByPackage.putIfAbsent(app.packageName, app)

        // ---- L1：包内映射表（权威） ----
        // appfilter 的 drawable 是资源名，即"文件名去扩展名"。
        val iconByBareName = LinkedHashMap<String, Int>()
        for ((index, fileName) in icons) {
            val key = IconMatchKeys.normalize(IconMatchKeys.stripImageExtension(fileName))
            iconByBareName.putIfAbsent(key, index)
        }

        // 同一组件只认第一次出现的条目：appfilter 理论上不该重复，但真出现了
        // 也要给出与条目顺序无关的稳定结果。
        val bindings = LinkedHashMap<String, Binding>()
        for ((component1, drawable) in manifestEntries) {
            val iconIndex = iconByBareName[IconMatchKeys.normalize(drawable)] ?: continue
            val packageName = IconPackManifest.packageOfComponent(component1) ?: continue
            // 已安装 → 换成设备上真实的启动组件（作者的写法可能指向别名 Activity，
            // 按整串相等去匹配会大面积落空）；
            // 未安装 → 保留 appfilter 原值。丢掉它就等于丢弃了这份映射，
            // 而那正是"以后装了新应用就能直接用上图标"的全部依据。
            val installedApp = appByPackage[packageName]
            val component = installedApp?.component ?: component1
            if (component in skipComponents) continue
            bindings.putIfAbsent(
                component,
                Binding(component, iconIndex, Source.MANIFEST, installed = installedApp != null),
            )
        }

        // ---- L2：文件名/目录名 = 包名（确定性） ----
        // 用规范化后的键做**完全相等**比较，因此不会误绑；同一键只认下标最小者。
        // 对 matchKey 再做一次去扩展名：生产链路里 ZipIconPlanner 已处理过，
        // 但绑定器不该依赖调用方一定做对——多去一次已知图片扩展名永远不会误伤包名
        // （`com.android.chrome` 的 `chrome` 不是图片扩展名，原样保留）。
        val iconByPackageKey = LinkedHashMap<String, Int>()
        for ((index, _, matchKey) in icons) {
            val key = IconMatchKeys.normalize(IconMatchKeys.stripImageExtension(matchKey))
            iconByPackageKey.putIfAbsent(key, index)
        }
        for ((component, packageName) in apps) {
            if (component in skipComponents) continue
            if (bindings.containsKey(component)) continue
            val iconIndex = iconByPackageKey[IconMatchKeys.normalize(packageName)] ?: continue
            bindings[component] =
                Binding(component, iconIndex, Source.PACKAGE_NAME, installed = true)
        }

        return bindings.values.toList()
    }
}
