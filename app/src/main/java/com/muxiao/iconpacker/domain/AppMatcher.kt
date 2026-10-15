package com.muxiao.iconpacker.domain

import com.muxiao.iconpacker.core.packer.engine.IconMatchKeys

/**
 * 图标文件名 ↔ 应用自动匹配（FR-04 的"一键自动匹配"内核，纯 Kotlin 可单测）。
 *
 * 匹配策略（评分制，贪心分配，每个图标至多分配给一个应用）：
 * 1. 文件名 == 完整包名（com.android.settings ↔ com.android.settings.png）——110 分；
 * 2. 文件名 == 包名尾段（settings ↔ com.android.settings）——95 分；
 * 3. 规范化后标签完全相等（chrome ↔ chrome）——100 分；
 * 4. 标签一方包含另一方（googlechrome ↔ chrome）——80 分；
 * 5. ASCII 首字母相等（Google Chrome ↔ gc）——70 分；
 * 6. 词元重叠（google_chrome ↔ Chrome 共享 chrome）——60 分；
 * 低于 60 分不匹配（宁缺毋滥：错配比不匹配更让用户困惑）。
 *
 * 为什么包名维度优先：大量第三方图标包 ZIP 按包名命名文件（com.android.chrome.png），
 * 此时标签匹配对中文名应用（设置 ↔ com.android.settings）完全无效，包名是更可靠的键。
 *
 * 已知边界（docs/features.md 记录，二期改进）：中文应用名与英文文件名的拼音匹配
 * （相机 ↔ camera）暂不支持，计划引入 TinyPinyin 类库补拼音首字母维度。
 */
object AppMatcher {

    /** 待匹配的应用（component 是映射键，label/packageName 是匹配依据） */
    data class AppEntryForMatch(
        val component: String,
        val label: String,
        val packageName: String,
    )

    data class Candidate(val slot: Int, val originalName: String)

    private const val SCORE_PACKAGE_EXACT = 110
    private const val SCORE_PACKAGE_TAIL = 95
    private const val SCORE_EXACT = 100
    private const val SCORE_CONTAINS = 80
    private const val SCORE_INITIALS = 70
    private const val SCORE_TOKEN = 60
    private const val THRESHOLD = 60

    /** 文件名里的噪声词，不参与首字母与词元匹配 */
    private val STOP_WORDS = setOf("ic", "icon", "app", "launcher", "img", "image", "logo", "iconpack")

    /** 单个应用的最佳候选；无合格候选返回 null */
    fun bestMatch(label: String, packageName: String, candidates: List<Candidate>): Candidate? =
        candidates
            .map { it to score(label, packageName, it.originalName) }
            .filter { it.second >= THRESHOLD }
            .maxWithOrNull(compareBy({ it.second }, { -it.first.slot }))
            ?.first

    /**
     * 批量匹配：返回 组件 → 槽位。
     *
     * @param skipComponents 已手动映射的组件（自动匹配不覆盖用户的手动指派）
     */
    fun matchAll(
        entries: List<AppEntryForMatch>,
        candidates: List<Candidate>,
        skipComponents: Set<String> = emptySet(),
    ): Map<String, Int> {
        data class Scored(val component: String, val candidate: Candidate, val score: Int)

        val pending = entries.filter { it.component !in skipComponents }
        val scored = ArrayList<Scored>()
        for (entry in pending) {
            for (candidate in candidates) {
                val s = score(entry.label, entry.packageName, candidate.originalName)
                if (s >= THRESHOLD) {
                    scored.add(Scored(entry.component, candidate, s))
                }
            }
        }
        // 高分优先；同分按组件名、槽位序稳定排序，保证结果确定可复现
        scored.sortWith(
            compareByDescending<Scored> { it.score }.thenBy { it.component }.thenBy { it.candidate.slot },
        )

        val usedSlots = HashSet<Int>()
        val usedComponents = HashSet<String>()
        val result = HashMap<String, Int>()
        for (s in scored) {
            if (s.candidate.slot in usedSlots) continue
            if (s.component in usedComponents) continue
            usedSlots.add(s.candidate.slot)
            usedComponents.add(s.component)
            result[s.component] = s.candidate.slot
        }
        return result
    }

    /** 评分：包名维度优先，标签维度回退 */
    fun score(appLabel: String, packageName: String, fileName: String): Int {
        val file = normalize(fileName)
        if (file.isEmpty()) return 0

        if (packageName.isNotBlank()) {
            // 包名必须走 normalizeKey（不去扩展名）——包名本身含点号，
            // 若用 normalize 会把最后一段当扩展名删掉（com.android.settings → android）
            if (file == normalizeKey(packageName)) return SCORE_PACKAGE_EXACT
            val tail = packageName.substringAfterLast('.', "")
            if (tail.isNotBlank() && file == normalizeKey(tail)) return SCORE_PACKAGE_TAIL
        }
        return scoreByLabel(appLabel, fileName, file)
    }

    /** 标签维度：equality/contains 用规范化串；initials/token 用原串（依赖分隔符分词） */
    private fun scoreByLabel(appLabel: String, fileName: String, normalizedFile: String): Int {
        val app = normalizeKey(appLabel)
        if (app.isEmpty()) return 0
        if (app == normalizedFile) return SCORE_EXACT
        if (app.length >= 3 && normalizedFile.length >= 3 &&
            (app.contains(normalizedFile) || normalizedFile.contains(app))
        ) {
            return SCORE_CONTAINS
        }
        // initials 与 token 分词前先去扩展名：否则 "gc_chrome.png" 会多出 png 词元
        val bareName = stripExtension(fileName)
        val appInitials = initials(appLabel)
        if (appInitials.length >= 2) {
            // 规则 5a：文件名整体即应用首字母串（gp ↔ Google Photos）
            if (normalizedFile == appInitials) return SCORE_INITIALS
            // 规则 5b：文件名各词元首字母等于应用首字母串（gc_chrome ↔ Google Chrome）
            if (initials(bareName) == appInitials) return SCORE_INITIALS
        }
        if (tokenOverlap(appLabel, bareName)) return SCORE_TOKEN
        return 0
    }

    /**
     * 去图片扩展名。
     *
     * 实现委托给 [IconMatchKeys]——导入解析（`ZipIconPlanner`）与自动匹配必须共用同一套
     * 规范化规则，否则"导入时算出的键"与"匹配时算出的键"会悄悄错开。
     */
    internal fun stripExtension(raw: String): String = IconMatchKeys.stripImageExtension(raw)

    /** 规范化文件名：去图片扩展名 + 去除非字母数字（CJK 保留） */
    internal fun normalize(raw: String): String = IconMatchKeys.normalize(stripExtension(raw))

    /**
     * 规范化匹配键：小写 + 去除非字母数字（CJK 保留），**不去扩展名**。
     * 用于包名与标签——它们的点号是语义分隔符，不是扩展名。
     */
    internal fun normalizeKey(raw: String): String = IconMatchKeys.normalize(raw)

    /** ASCII 词首字母：Google Chrome → gc；google_chrome → gc */
    internal fun initials(raw: String): String =
        tokenize(raw)
            .filter { it.first().code < 128 }
            .map { it.first().lowercaseChar() }
            .joinToString("")

    private fun tokenize(raw: String): List<String> =
        raw.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() && it !in STOP_WORDS }

    private fun tokenOverlap(appLabel: String, fileName: String): Boolean {
        val appTokens = tokenize(appLabel).filter { it.length >= 3 }.toSet()
        val fileTokens = tokenize(fileName).filter { it.length >= 3 }.toSet()
        return appTokens.isNotEmpty() && appTokens.intersect(fileTokens).isNotEmpty()
    }
}
