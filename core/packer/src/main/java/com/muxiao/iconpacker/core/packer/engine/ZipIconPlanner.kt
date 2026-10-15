package com.muxiao.iconpacker.core.packer.engine

/**
 * ZIP 导入的条目规划（纯 Kotlin，可 JVM 单测）。
 *
 * 解决两件在"导入即建立映射"里必须先行的问题：
 *
 * 1. **多密度副本去重**。从图标包 APK 解出来的 ZIP 里，同一个 drawable 通常在
 *  `res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/` 各有一份。原先每个条目都会
 *  单独入池——同一张图占 5 个槽位（上限 1024）、`originalName` 还完全相同，
 *  自动匹配只能按槽位序随便挑一个。实测：3 个图标 × 5 个密度 = 17 个条目，
 *  去重后只剩 4 个。
 *
 * 2. **推导匹配键**。原先把 `File(entry.name).name` 当文件名、只取最后一段，
 *  目录名整段丢弃——而目录名往往就是最可靠的键。实测 `com.tencent.mm/icon.png`
 *  与 `com.android.settings/icon.png` 双双变成 `icon`，两个不同 App 的图无法区分。
 *
 * 去重的分组键刻意**只剥离密度与 API 版本限定符**，不剥离其他限定符：误合并会
 * 静默丢图（坏），少合并只是多占一个槽位（轻），故取保守策略。
 */
object ZipIconPlanner {

    /** ZIP 里的一个候选条目（只保留规划所需信息，便于纯 JVM 单测） */
    data class Entry(val path: String, val sizeBytes: Long)

    /** 规划后保留的条目 */
    data class Planned(
        /** ZIP 内原始条目路径（含目录） */
        val path: String,
        val sizeBytes: Long,
        /** 落地文件名（不含目录） */
        val fileName: String,
        /** 落库与匹配用的键：优先包名，其次有意义的目录名，最后文件名 */
        val matchKey: String,
    ) {
        /** 去掉图片扩展名的文件名——appfilter 的 drawable 名即此值 */
        val bareName: String get() = IconMatchKeys.stripImageExtension(fileName)
    }

    data class Plan(val kept: List<Planned>, val duplicates: Int)

    /** 密度限定符 → dpi 排名；用于"保留最高密度"，未知（nodpi/anydpi）不参与排名 */
    private val DENSITY_RANKS = mapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640,
    )

    /** 需要从路径里剥离以判断"是否同一张图"的限定符（不含密度排名语义） */
    private val NON_RANKED_QUALIFIERS = setOf("nodpi", "anydpi", "none")

    private val VERSION_QUALIFIER = Regex("^v\\d+$")

    /**
     * "泛化"文件名：单独一个 `icon` 不含任何应用信息，此时目录名更有价值。
     * 纯数字与 `ic_0001` 这类序号同样视为泛化——它们只是占位序号。
     */
    private val GENERIC_NAMES = setOf(
        "icon", "icons", "launcher", "logo", "app", "application",
        "image", "img", "icon_foreground", "icon_background", "untitled", "undefined", "unknown",
    )

    private val GENERIC_PATTERNS = listOf(
        Regex("^\\d+$"),
        Regex("^ic_?\\d+$"),
        Regex("^icon_?\\d+$"),
        Regex("^img_?\\d+$"),
    )

    /** 资源目录前缀：这些段的字面值对匹配没有意义（`res` / `drawable-xxxhdpi` 等） */
    private val RESOURCE_PREFIXES = listOf("res", "drawable", "mipmap", "assets", "raw")

    // ---- 对外 ----

    /**
     * 规划：按"同一张图的不同密度副本"分组去重，并推导每条保留项的匹配键。
     *
     * @return [Plan.kept] 按路径排序（结果确定可复现）；[Plan.duplicates] 被丢弃的条目数
     */
    fun plan(entries: List<Entry>): Plan {
        val groups = LinkedHashMap<String, MutableList<Entry>>()
        for (entry in entries) {
            groups.getOrPut(dedupGroupKey(entry.path)) { ArrayList() }.add(entry)
        }
        val kept = ArrayList<Planned>(groups.size)
        var duplicates = 0
        for (group in groups.values) {
            // 同级密度下比体积、再比路径，保证不依赖 ZIP 条目顺序
            val winner = group.maxWithOrNull(
                compareBy({ densityRank(it.path) }, { it.sizeBytes }, { it.path }),
            ) ?: continue
            duplicates += group.size - 1
            kept += Planned(
                path = winner.path,
                sizeBytes = winner.sizeBytes,
                fileName = fileNameOf(winner.path),
                matchKey = matchKeyOf(winner.path),
            )
        }
        return Plan(kept.sortedBy { it.path }, duplicates)
    }

    /** ZIP 条目路径 → 文件名（不含目录） */
    fun fileNameOf(path: String): String = path.replace('\\', '/').substringAfterLast('/')

    /**
     * 推导匹配键，按可靠度依次尝试：
     * 1. 文件名去掉图片扩展名后本身就是包名（`com.android.chrome.png`）；
     * 2. 路径中最深的包名目录段（`com.tencent.mm/icon.png`）——从深到浅取第一个；
     * 3. 文件名是泛化名时，路径中最深的非泛化、非资源目录段（`Chrome/icon.png`）；
     * 4. 兜底：文件名去扩展名。
     */
    fun matchKeyOf(path: String): String {
        val bare = IconMatchKeys.stripImageExtension(fileNameOf(path))
        if (IconMatchKeys.looksLikePackage(bare)) return bare

        val dirs = dirSegmentsOf(path)
        dirs.asReversed().firstOrNull { IconMatchKeys.looksLikePackage(it) }?.let { return it }

        if (isGenericName(bare)) {
            dirs.asReversed()
                .firstOrNull { !isGenericName(it) && !isResourceSegment(it) && !isQualifierOnly(it) }
                ?.let { return it }
        }
        return bare
    }

    /**
     * 去重分组键：**目录剥离密度与版本限定符后** + 文件名。
     *
     * `res/drawable-mdpi/chrome.png` 与 `res/drawable-xxxhdpi/chrome.png` 同组；
     * 而 `com.tencent.mm/icon.png` 与 `com.android.settings/icon.png` 目录不同、绝不同组
     * （后者若被合并就是"两个 App 只剩一张图"的静默数据丢失）。
     */
    internal fun dedupGroupKey(path: String): String {
        val dir = dirSegmentsOf(path).joinToString("/") { segment ->
            segment.lowercase().split('-')
                .filterNot { it in DENSITY_RANKS || it in NON_RANKED_QUALIFIERS || VERSION_QUALIFIER.matches(it) }
                .joinToString("-")
        }
        return "$dir/${fileNameOf(path)}"
    }

    /** 路径中出现的最高密度排名；无密度限定符为 0 */
    internal fun densityRank(path: String): Int =
        dirSegmentsOf(path)
            .flatMap { it.lowercase().split('-') }
            .mapNotNull { DENSITY_RANKS[it] }
            .maxOrNull() ?: 0

    // ---- 内部 ----

    /** 路径中的目录段（顺序保持由浅到深），已把反斜杠归一为斜杠 */
    private fun dirSegmentsOf(path: String): List<String> {
        val normalized = path.replace('\\', '/')
        val lastSlash = normalized.lastIndexOf('/')
        if (lastSlash <= 0) return emptyList()
        return normalized.substring(0, lastSlash).split('/').filter { it.isNotBlank() }
    }

    private fun isGenericName(segment: String): Boolean {
        val lower = segment.lowercase()
        return lower in GENERIC_NAMES || GENERIC_PATTERNS.any { it.matches(lower) }
    }

    private fun isResourceSegment(segment: String): Boolean {
        val lower = segment.lowercase()
        return RESOURCE_PREFIXES.any { lower == it || lower.startsWith("$it-") }
    }

    /** 整段只由限定符构成（`xxxhdpi`、`v4`、`xxhdpi-v4`）——不含任何名称信息 */
    private fun isQualifierOnly(segment: String): Boolean {
        val tokens = segment.lowercase().split('-').filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.all {
            it in DENSITY_RANKS || it in NON_RANKED_QUALIFIERS || VERSION_QUALIFIER.matches(it)
        }
    }
}
