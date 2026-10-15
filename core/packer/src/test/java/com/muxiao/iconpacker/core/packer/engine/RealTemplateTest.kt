package com.muxiao.iconpacker.core.packer.engine

import com.muxiao.iconpacker.core.packer.arsc.ArscPatcher
import com.muxiao.iconpacker.core.packer.axml.AxmlElement
import com.muxiao.iconpacker.core.packer.axml.AxmlReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipEntry

/**
 * 真实模板（aapt2 编译、含 namespace/resource-map chunk）端到端回归：
 * 验证 [PackAssembler.buildUnsigned] 在真实 AXML 布局上能正确修补 label /
 * versionCode / 抽屉 category，而不只是自产假模板。
 */
class RealTemplateTest {

    private fun loadTemplate(): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream("template.apk")
            ?: error(
                "template.apk 不在测试资源中——它由 :core:packer:syncTemplateFixture 任务" +
                    "从 :template 模块产物自动同步，执行一次 gradle 构建即可补齐",
            )
        return stream.readBytes()
    }

    private fun findCategory(manifest: AxmlReader): AxmlReader.Element {
        fun walk(el: AxmlReader.Element): AxmlReader.Element? {
            if (el.name == "category") return el
            for (c in el.children) walk(c)?.let { return it }
            return null
        }
        return walk(manifest.root) ?: error("清单中未找到 <category> 元素")
    }

    /** 深度优先找第一个同名元素 */
    private fun findElement(el: AxmlReader.Element, name: String): AxmlReader.Element? {
        if (el.name == name) return el
        for (c in el.children) findElement(c, name)?.let { return it }
        return null
    }

    /** 深度优先收集全部同名元素（按文件顺序） */
    private fun collect(
        el: AxmlReader.Element,
        name: String,
        out: MutableList<AxmlReader.Element> = ArrayList(),
    ): List<AxmlReader.Element> {
        if (el.name == name) out.add(el)
        for (c in el.children) collect(c, name, out)
        return out
    }

    private fun attrOf(el: AxmlReader.Element, attribute: String): String? =
        el.attributes.firstOrNull { it.name == attribute }?.rawValue

    /**
     * **resources.arsc 的包名必须跟随 manifest 的 package 改写**。
     *
     * 启动器加载图标包资源的方式是
     * `packResources.getIdentifier("appfilter", "xml", <产物包名>)`，其底层
     * （AssetManager2::GetResourceId）对请求包名与 **arsc 中记录的包名字符串**
     * 做逐字符精确匹配，不一致一律返回 0。只改 manifest 不改 arsc 的产物
     * 装得上、能被识别，但启动器按包名找 appfilter / 图标全部落空——
     * 桌面表现为"图标包没有任何可以替换的图标"（真机 ColorOS 实测踩到）。
     *
     * 同时锁住 resources.arsc 的 STORED 压缩方式：targetSdk ≥ 30 强制要求
     * 未压缩 + 4 字节对齐，替换时若被写成 DEFLATED 会直接装不上。
     */
    @Test
    fun `arsc package name follows the manifest package rename and stays stored`() {
        val template = loadTemplate()
        val renamed = "com.muxiao.icon.p42"

        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "包名一致性检查",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1)),
            iconBytes = mapOf(1 to "X".toByteArray()),
            packageName = renamed,
        )

        val arsc = ZipUtils.readEntry(out, "resources.arsc")
            ?: error("产物缺少 resources.arsc")
        assertEquals(
            "arsc 包名必须与 manifest package 一致，否则启动器 getIdentifier 全部落空",
            renamed,
            ArscPatcher.packageName(arsc),
        )

        // STORED 压缩方式必须在重写后保留
        val zip = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(out)).use { zs ->
            generateSequence(zs.nextEntry) { zs.nextEntry }
                .filter { it.name == "resources.arsc" }
                .first()
                .method
        }
        assertEquals(
            "resources.arsc 必须 STORED（未压缩），targetSdk ≥ 30 强制要求",
            ZipEntry.STORED,
            zip,
        )
    }

    /**
     * 模板必须声明**启动器应用的包可见性**。
     *
     * 界面要显示"这张图标是给哪个应用用的"，就得查目标应用的名字。而 targetSdk ≥ 30 的
     * 应用在 Android 11+ 默认看不到其他应用：`PackageManager.getApplicationInfo()` 抛
     * `NameNotFoundException`，于是**已安装的应用全被显示成"未安装"**（真机实测踩到）。
     * 修法是清单里声明 `<queries><intent><action MAIN/></intent></queries>`：
     * 只放行"带 MAIN 入口的应用"——正是图标包要换图标的那一批，
     * 不必动 QUERY_ALL_PACKAGES 那种全局权限。
     *
     * 同时锁住抽屉开关与识别 filter 的共存约束（模板为适配尽可能多桌面，现在含
     * **多条** `<category>`）：
     * 1. `<queries>` 里**不得出现 `<category>`**：setDrawerEntryVisible 改写的是
     *  字符串池里"第一个 <category>"槽位的内容，queries 的 intent 若引用同一字符串
     *  会被连带改掉，声明随之失效（实测踩过）；
     * 2. 抽屉入口那条 `<category>` 在文件顺序上必须仍是第一个——切换只允许动它；
     * 3. 识别 filter 的 category（Apex 的 com.anddoes.launcher.THEME、Nova 的
     *  CUSTOM_ICON_PICKER 等）引用各自独立的池槽位，来回切换必须原样保留。
     */
    @Test
    fun `template declares launcher package visibility without stealing the drawer category`() {
        val template = loadTemplate()

        val manifest = AxmlReader(ZipUtils.readEntry(template, "AndroidManifest.xml")!!)
        val queries = findElement(manifest.root, "queries")
            ?: error("模板清单缺少 <queries>：图标包界面会把已安装的应用一律显示成\"未安装\"")
        assertTrue(
            "<queries> 未声明 MAIN：非启动器应用仍然不可见",
            collect(queries, "intent").any { intent ->
                intent.children.any { attrOf(it, "name") == "android.intent.action.MAIN" }
            },
        )
        assertEquals(
            "<queries> 里不能出现 <category>：字符串池会去重，抽屉开关会把它一并改掉",
            emptyList<String>(),
            collect(queries, "category").map { attrOf(it, "name").orEmpty() },
        )

        // 识别 filter 中不受抽屉开关影响的 category（若被连带改坏说明池共享了槽位）
        val untouchedCategories = listOf(
            "com.anddoes.launcher.THEME",                       // Apex 识别声明
            "com.novalauncher.category.CUSTOM_ICON_PICKER",     // Nova 选择器声明
        )

        // 隐藏抽屉入口：文档顺序第一个 <category>（抽屉）被改成 DEFAULT，
        // 识别 filter 的 category 与 action 必须原样保留
        val hidden = PackAssembler.buildUnsigned(
            template = template,
            packName = "可见性检查",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1)),
            iconBytes = mapOf(1 to "X".toByteArray()),
            showInDrawer = false,
        )
        val hiddenManifest = AxmlReader(ZipUtils.readEntry(hidden, "AndroidManifest.xml")!!)
        val hiddenCategories = collect(hiddenManifest.root, "category")
        assertEquals(
            "抽屉入口（文档顺序第一个 <category>）应被改成 DEFAULT —— 定位错了这个开关就会失效",
            "android.intent.category.DEFAULT",
            attrOf(hiddenCategories.first(), "name"),
        )
        val hiddenNames = hiddenCategories.map { attrOf(it, "name").orEmpty() }
        assertTrue(
            "识别 filter 的 category 被抽屉开关连带改坏（字符串池共享槽位）：$hiddenNames",
            hiddenNames.containsAll(untouchedCategories),
        )
        assertTrue(
            "识别 action org.adw.launcher.THEMES 不应被抽屉开关改坏",
            collect(hiddenManifest.root, "action")
                .any { attrOf(it, "name") == "org.adw.launcher.THEMES" },
        )

        // 切回显示：抽屉槽位恢复 LAUNCHER，识别 filter 依旧完好（来回切换幂等）
        val shown = PackAssembler.buildUnsigned(
            template = template,
            packName = "可见性检查",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1)),
            iconBytes = mapOf(1 to "X".toByteArray()),
            showInDrawer = true,
        )
        val shownCategories = collect(
            AxmlReader(ZipUtils.readEntry(shown, "AndroidManifest.xml")!!).root,
            "category",
        )
        assertEquals(
            "抽屉入口应恢复 LAUNCHER",
            "android.intent.category.LAUNCHER",
            attrOf(shownCategories.first(), "name"),
        )
        assertTrue(
            "切回显示后识别 filter 的 category 不应丢失：" +
                shownCategories.map { attrOf(it, "name").orEmpty() },
            shownCategories.map { attrOf(it, "name").orEmpty() }.containsAll(untouchedCategories),
        )
    }

    /**
     * 模板必须携带让主流启动器"发现图标包"的识别 filter 集。
     *
     * 历史教训（产物不被桌面识别的根因）：旧模板全用自定义 action 声明识别入口，且核心
     * 字符串写错——org.adw.ActivityStarter.THEMES 只有 Kvaesitso 额外扫描（其源码
     * AppFilterIconPackInstaller 同时扫 org.adw.launcher.THEMES / com.novalauncher.THEME /
     * org.adw.ActivityStarter.THEMES / app.lawnchair.icons.THEMED_ICON 四个 action），
     * ADW 系通行标准 org.adw.launcher.THEMES 反而没声明；Apex 的扫描是
     * MAIN + category（com.anddoes.launcher.THEME），旧模板把它写成 action，匹配不到。
     * 净效果：除 Kvaesitso 外，Nova / Apex / KISS / Lawnchair 等所有主流桌面的
     * 图标包列表都不出现产物。各启动器的扫描姿势以真实启动器源码 + 事实标准图标包
     * （Arcticons / CandyBar 系）manifest 为准，逐条核对后在模板文件头有对照表。
     */
    @Test
    fun `template declares launcher discovery filters`() {
        val manifest = AxmlReader(ZipUtils.readEntry(loadTemplate(), "AndroidManifest.xml")!!)
        val activity = findElement(manifest.root, "activity")
            ?: error("模板清单缺少 <activity>")
        val filters = collect(activity, "intent-filter")

        fun actionsOf(filter: AxmlReader.Element) =
            collect(filter, "action").mapNotNull { attrOf(it, "name") }.toSet()
        fun categoriesOf(filter: AxmlReader.Element) =
            collect(filter, "category").mapNotNull { attrOf(it, "name") }.toSet()

        /** 存在一个 filter：其 action 覆盖 [actions]、category 覆盖 [categories] */
        fun hasFilter(actions: Set<String>, categories: Set<String>) =
            filters.any { actionsOf(it).containsAll(actions) && categoriesOf(it).containsAll(categories) }

        val default = setOf("android.intent.category.DEFAULT")

        // ADW 系通行标准（启动器按 action 查询；带 DEFAULT 兼容 MATCH_DEFAULT_ONLY 场景）
        assertTrue(
            "缺少 ADW 系标准 action org.adw.launcher.THEMES —— Nova/KISS/Lawnchair 等将无法发现图标包",
            hasFilter(setOf("org.adw.launcher.THEMES"), default),
        )
        // Kvaesitso 的兼容扫描动作
        assertTrue(
            "缺少 org.adw.ActivityStarter.THEMES —— Kvaesitso 兼容扫描入口失效",
            filters.any { "org.adw.ActivityStarter.THEMES" in actionsOf(it) },
        )
        // Apex：category 型（MAIN + com.anddoes.launcher.THEME），写成 action 匹配不到
        assertTrue(
            "Apex 以 MAIN + category(com.anddoes.launcher.THEME) 扫描，必须按 category 声明",
            hasFilter(setOf("android.intent.action.MAIN"), setOf("com.anddoes.launcher.THEME")),
        )
        // Nova：一键应用 / 自定义图标选择器（Kvaesitso 亦按此 action 扫描）
        assertTrue(
            "缺少 Nova 的 com.novalauncher.THEME + CUSTOM_ICON_PICKER 组合",
            hasFilter(setOf("com.novalauncher.THEME"), setOf("com.novalauncher.category.CUSTOM_ICON_PICKER")),
        )
        // GO 桌面
        assertTrue(
            "缺少 GO 桌面识别 action com.gau.go.launcherex.theme",
            filters.any { "com.gau.go.launcherex.theme" in actionsOf(it) },
        )
        // 其余主流桌面（各自单 action）
        listOf(
            "org.adwfreak.launcher.THEMES",            // ADW.Ex
            "com.fede.launcher.THEME_ICONPACK",        // LauncherPro 系
            "com.dlto.atom.launcher.THEME",            // Atom
            "ginlemon.smartlauncher.THEMES",           // Smart Launcher
            "com.sonymobile.home.ICON_PACK",           // Sony Home
            "com.lge.launcher2.THEME",                 // LG Home
            "com.motorola.launcher.ACTION_ICON_PACK",  // Moto
            "ch.deletescape.lawnchair.ICONPACK",       // Lawnchair 1.x
            "org.adw.launcher.icons.ACTION_PICK_ICON", // ADW 手动选图标
        ).forEach { action ->
            assertTrue("缺少识别 action：$action", filters.any { action in actionsOf(it) })
        }

        // 反断言：旧模板的无效字符串不得回归（无任何启动器以它们扫描）
        val banned = setOf("com.teslacoilsw.launcher.THEME", "com.fede.launcher.THEME")
        val allActions = filters.flatMap { actionsOf(it) }.toSet()
        assertTrue(
            "出现了无启动器扫描的旧错误字符串：${allActions.intersect(banned)}",
            allActions.intersect(banned).isEmpty(),
        )
    }

    /**
     * 图标包应用必须自带界面：模板的 dex 里得有那个 Activity。
     *
     * 在此之前模板的 MainActivity 只弹一句 Toast 就 finish——用户在手机上点开图标包
     * 看不到任何内容。产物 APK 是"模板 + 资源替换"，界面类只能来自模板，
     * 所以这条断言的落点就是模板的 dex。
     *
     * 注意不能只看 classes.dex：类实际被分到了 classes2.dex，写死文件名会漏检
     * （本条断言最初就是这么红的）。
     */
    @Test
    fun `template ships the icon pack ui activity`() {
        val template = loadTemplate()
        val dexEntries = ZipUtils.entryNames(template).filter { it.matches(Regex("classes\\d*\\.dex")) }
        assertTrue("模板里没有任何 dex 条目", dexEntries.isNotEmpty())

        val descriptor = "Lcom/muxiao/icon/MainActivity;"
        val hit = dexEntries.any { containsAscii(ZipUtils.readEntry(template, it)!!, descriptor) }
        assertTrue(
            "模板 dex（${dexEntries.joinToString()}）中找不到 $descriptor —— " +
                "图标包应用会没有界面（点开即崩溃或空屏）",
            hit,
        )
    }

    /**
     * 模板不得携带 Kotlin 运行时（`kotlin/` 目录下的 `.kotlin_builtins` 条目）。
     *
     * 这些条目来自 kotlin-stdlib：模板一旦有 Kotlin 源码，它就会被打进**每个产物**，
     * 体积从 0.36MB 涨回 1.26MB（实测），而图标包运行时用不到一行 Kotlin。
     * 界面是纯 Java 实现的，本断言把"别再把 Kotlin 塞进模板"钉死。
     */
    @Test
    fun `template does not carry the kotlin runtime`() {
        val kotlinEntries = ZipUtils.entryNames(loadTemplate()).filter { it.startsWith("kotlin/") }
        assertEquals(
            "模板里出现了 Kotlin 运行时条目，每个产物体积会因此翻数倍：$kotlinEntries",
            emptyList<String>(),
            kotlinEntries,
        )
    }

    /** 纯 ASCII 子串查找（dex 里的类型描述符就是 ASCII/MUTF-8，无需引入 dex 解析库） */
    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val target = needle.toByteArray(Charsets.US_ASCII)
        if (target.isEmpty() || target.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - target.size) {
            for (j in target.indices) {
                if (haystack[i + j] != target[j]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * Activity 名在改包名后必须仍然指向同一个类。
     *
     * 模板清单曾写作 `.MainActivity`：系统按**清单包名**展开相对名，而引擎只改写
     * 清单的 package（`PackageNameRules.suggest` 派生 `com.muxiao.icon.p<id>`，这是
     * 多个图标包共存的前提）。于是 dex 里明明是 `com.muxiao.icon.MainActivity`，
     * 系统却去找 `com.muxiao.icon.pN.MainActivity` —— 图标包能装、能被启动器识别、
     * 图标也生效，唯独"点开图标包看内容"直接 ClassNotFoundException。
     * 而"点开看内容"正是这个界面的全部用途，所以这条必须用真实模板锁住。
     */
    @Test
    fun `activity name survives package rename`() {
        val derived = "com.muxiao.icon.p7"
        val out = PackAssembler.buildUnsigned(
            template = loadTemplate(),
            packName = "改名检查",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1)),
            iconBytes = mapOf(1 to "X".toByteArray()),
            showInDrawer = true,
            packageName = derived,
        )

        val manifest = AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!)
        assertEquals(
            "清单包名应被改写为项目自己的包名",
            derived,
            findElement(manifest.root, "manifest")!!
                .attributes.single { it.name == "package" }.rawValue,
        )
        assertEquals(
            "Activity 必须是全限定名：相对名会被系统按改写后的包名展开，界面必然打不开",
            "com.muxiao.icon.MainActivity",
            findElement(manifest.root, "activity")!!
                .attributes.single { it.name == "name" }.rawValue,
        )
    }

    /**
     * 版本号与版本名都必须写进产物清单。
     *
     * 版本名此前从未被引擎改写——模板 defaultConfig 是 `1.0`，于是**所有**产物在系统
     * "应用信息"里都显示 1.0：用户填了自定义版本，在设备上却完全看不到，等于没生效。
     * 两者语义不同、都要可设：versionCode 决定能否覆盖安装，versionName 只是给人看的。
     *
     * 注意 versionCode 断言用 `data` 而非 `rawValue`——[AxmlPatcher.setIntAttribute]
     * 是原地改写 typedValue.data，字符串池里那条 raw 仍指向模板旧值（aapt2 产物中
     * rawValue 与 data 本就可能不一致，系统读的是 data）。
     */
    @Test
    fun `version code and version name are both patched into the manifest`() {
        val out = PackAssembler.buildUnsigned(
            template = loadTemplate(),
            packName = "版本检查",
            versionCode = 42,
            versionName = "2.1.0",
            mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1)),
            iconBytes = mapOf(1 to "X".toByteArray()),
        )

        val manifest = findElement(
            AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!).root,
            "manifest",
        )!!
        assertEquals(
            "versionCode 应写入清单的 typedValue.data",
            42,
            manifest.attributes.single { it.name == "versionCode" }.data,
        )
        assertEquals(
            "versionName 应替换清单里的字符串（模板原值是 1.0）",
            "2.1.0",
            manifest.attributes.single { it.name == "versionName" }.rawValue,
        )
    }

    @Test
    fun `real template builds and toggles launcher category`() {
        val template = loadTemplate()
        val mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1))
        val iconBytes = mapOf(1 to "WEBP-1".toByteArray())

        val shown = PackAssembler.buildUnsigned(
            template = template,
            packName = "我的图标包",
            versionCode = 2,
            mappings = mappings,
            iconBytes = iconBytes,
            showInDrawer = true,
        )
        val manifestShown = AxmlReader(ZipUtils.readEntry(shown, "AndroidManifest.xml")!!)
        assertEquals(
            "android.intent.category.LAUNCHER",
            findCategory(manifestShown).attributes.single { it.name == "name" }.rawValue,
        )

        val hidden = PackAssembler.buildUnsigned(
            template = template,
            packName = "我的图标包",
            versionCode = 3,
            mappings = mappings,
            iconBytes = iconBytes,
            showInDrawer = false,
        )
        val manifestHidden = AxmlReader(ZipUtils.readEntry(hidden, "AndroidManifest.xml")!!)
        assertEquals(
            "android.intent.category.DEFAULT",
            findCategory(manifestHidden).attributes.single { it.name == "name" }.rawValue,
        )
    }

    /**
     * 回归：产物中 `resources.arsc` 必须保持 STORED（未压缩）且 4 字节对齐。
     * 否则 Android R+（targetSdk ≥ 30）安装会报
     * `-124: ... resources.arsc ... must be stored uncompressed and aligned on a 4-byte boundary`。
     */
    @Test
    fun `resources arsc stays stored and 4-byte aligned`() {
        val template = loadTemplate()
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "对齐检查",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1)),
            iconBytes = mapOf(1 to "WEBP-1".toByteArray()),
            showInDrawer = true,
        )

        val (method, dataStart) = localEntryInfo(out, "resources.arsc")
        assertEquals("resources.arsc 必须为 STORED（未压缩）", ZipEntry.STORED, method)
        assertEquals(
            "resources.arsc 数据起点必须 4 字节对齐（R+ 安装硬性要求）",
            0,
            dataStart % 4,
        )
    }

    /**
     * 回归（图标不切换根因）：产物中的 appfilter.xml / drawable.xml 必须是结构合法的
     * 二进制 AXML。历史上 [AxmlWriter] 曾两次写错 start 元素节点的字段：
     * 先是 headerSize=36（框架定位 ns/name 越界），修的时候又把 attributeStart 一并改成 36 ——
     * 而 attributeStart 的基准是 attrExt 起点（chunk 起点 + headerSize），正确值恒为 20。
     * 结果是 ColorOS 等启动器用框架原生解析器（nativeOpenXmlAsset）读 appfilter 时判为
     * corrupt、读不到任何映射，表现为图标包能选中但图标不切换。
     *
     * 本测试解析真实产物里的 appfilter.xml，断言 headerSize=16、attributeStart=20 等字段，
     * 并把映射表回读出来做端到端语义校验——字段正确不等于可读，两者都要过。
     */
    @Test
    fun `appfilter xml is valid binary axml with correct node fields`() {
        val template = loadTemplate()
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "AXML 校验",
            versionCode = 1,
            mappings = listOf(
                IconMapping("ComponentInfo{com.a/.A}", 1),
                IconMapping("ComponentInfo{com.b/.B}", 2),
            ),
            iconBytes = mapOf(1 to "WEBP-1".toByteArray(), 2 to "WEBP-2".toByteArray()),
            showInDrawer = true,
        )

        val appfilter = ZipUtils.readEntry(out, "res/xml/appfilter.xml")
            ?: error("产物缺少 res/xml/appfilter.xml")
        val chunks = walkChunks(appfilter)

        // 必须包含空资源表 chunk（与 aapt2 对齐，ColorOS 严格逐 chunk 校验）
        assertTrue(
            "appfilter.xml 缺少资源表 chunk",
            chunks.any { it.first == 0x0180 },
        )

        // 必须有带属性的 <item> 起点节点，且关键字段正确
        val itemStart = chunks.firstOrNull {
            it.first == 0x0102 && AxmlReader.readU16(appfilter, it.second + 28) == 2
        } ?: error("未找到带 2 属性的 <item> 起点节点")
        val (_, offset) = itemStart
        assertEquals(16, AxmlReader.readU16(appfilter, offset + 2))   // headerSize
        assertEquals(20, AxmlReader.readU16(appfilter, offset + 24))  // attributeStart（基准为 attrExt 起点）
        assertEquals(36 + 20 * 2, AxmlReader.readU32(appfilter, offset + 4)) // size（基准为 chunk 起点）

        // end 元素节点 headerSize 也必须是 16
        val endEl = chunks.firstOrNull { it.first == 0x0103 }
            ?: error("未找到 end 元素节点")
        assertEquals(16, AxmlReader.readU16(appfilter, endEl.second + 2))

        // 端到端语义校验：字段正确 ≠ 能被框架读出来，必须把映射表完整回读一遍
        val root = AxmlReader(appfilter).root
        assertEquals("resources", root.name)
        assertEquals(
            listOf(
                "ComponentInfo{com.a/.A}" to "ic_0001",
                "ComponentInfo{com.b/.B}" to "ic_0002",
            ),
            root.children.map { it.attributes[0].rawValue to it.attributes[1].rawValue },
        )
    }

    /**
     * 图标必须落在 arsc 真正引用的那个 ZIP 条目上。
     *
     * 真实模板里的图标条目是 `res/drawable-xxxhdpi-v4/ic_0001.png`——aapt2 会按源工程的
     * 限定符拼出目录名，与源工程目录不同名。历史上引擎按源目录名写入，而
     * [ZipRewriter] 对"源中不存在的条目"是**静默追加**：
     * 图标成了 arsc 不认识的新条目，原 1x1 占位图继续生效。产物能安装、图标包能被识别、
     * appfilter 能读出映射，唯独图标不生效——在启动器的"选择图标"界面里表现为
     * 看不到任何可替换的图标。
     *
     * 本用例必须用**真实模板**（而非自产假模板）跑：假模板曾长期沿用源目录名，
     * 与引擎"自产自销"地一致，因而完全测不出这个问题。
     */
    @Test
    fun `icon bytes land on the entry that arsc references`() {
        val template = loadTemplate()
        val target = "res/drawable-xxxhdpi-v4/ic_0001.png"
        assertTrue("真实模板应含槽位条目 $target", ZipUtils.hasEntry(template, target))

        val icon = "USER-ICON-BYTES".toByteArray()
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "图标落点校验",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1)),
            iconBytes = mapOf(1 to icon),
            showInDrawer = true,
        )

        assertArrayEquals("用户图标未落到 arsc 引用的条目上", icon, ZipUtils.readEntry(out, target))
        // 同编号不得存在第二个条目：那意味着图标被写进了 arsc 不认识的路径
        assertEquals(
            "出现孤儿图标条目（不会被 arsc 引用）",
            emptyList<String>(),
            ZipUtils.entryNames(out).filter { it.contains("ic_0001") && it != target },
        )
    }

    /**
     * 图标资源必须落在**密度限定符**目录里，不得是 `anydpi`。
     *
     * `anydpi` 的 `ResTable_config.density` 是 `ANY(0xFFFE = 65534)`。Android 在
     * `BitmapFactory.decodeResourceStream` 里会把它直接塞进 `opts.inDensity`，于是解码出的
     * 位图密度被标成 65534：一张 192px 的图标在 320dpi 屏幕上内禀尺寸塌成
     * `192 × 320 / 65534 ≈ 1px`，启动器把它按 1×1 绘制再放大到图标格子 ——
     * **整张图只剩平均色，看起来就是一块纯色方块**。
     *
     * 之所以长期未暴露：模板占位图恰好是 1×1 单色，"平均色"就等于它自己，看起来完全正常；
     * 只有写入真实多彩图标后才显形。实测证据：桌面渲染出的方块 RGB(110,106,103) 与产物
     * `ic_0002` 的整图平均色 (109,106,103) 吻合到 ±1。
     *
     * 现模板改为 `drawable-xxxhdpi` / `mipmap-xxxhdpi`：192px ↔ 640dpi，
     * 在 320dpi 设备上内禀尺寸 96px = 48dp，正好是启动器图标的标准尺寸。
     * 本用例把"不得回到 anydpi"锁死——目录名改回去即红。
     */
    @Test
    fun `icon resources are density-qualified rather than anydpi`() {
        val all = ZipUtils.entryNames(loadTemplate())
        val slotEntries = all.filter { it.startsWith("res/drawable-") && it.endsWith(".png") }
        val launcherEntries = all.filter { it.startsWith("res/mipmap-") && it.contains("ic_launcher") }
        assertTrue("模板应含槽位与启动器图标条目", slotEntries.isNotEmpty() && launcherEntries.isNotEmpty())

        // 红线：任何图标都不得落在 anydpi（density=ANY 会让位图内禀尺寸塌成 1px → 纯色方块）
        assertEquals(
            "图标资源落在了 anydpi 目录",
            emptyList<String>(),
            (slotEntries + launcherEntries).filter { it.contains("-anydpi") },
        )

        // 槽位占位图必须落在 xxxhdpi（核心约束：192px ↔ 640dpi ↔ 48dp）；字节由引擎在打包时
        // 覆盖为真实图标，故只校验目录密度，不校验占位图自身像素。
        assertTrue(
            "槽位占位图应位于 xxxhdpi 目录，实际：$slotEntries",
            slotEntries.all { it.contains("-xxxhdpi") },
        )

        // 启动器图标允许多密度（hdpi..xxxhdpi），不强制 xxxhdpi；仅校验其为 webp 且非 anydpi
        assertTrue(
            "启动器图标应为 webp 多密度资源，实际：$launcherEntries",
            launcherEntries.all { it.endsWith(".webp") && !it.contains("-anydpi") },
        )
    }

    /**
     * 启动器图标条目必须能被探测到并按模板实际路径替换。
     *
     * 旧实现把 `res/mipmap-anydpi-v21/ic_launcher.png` 写死在常量里，模板一改限定符就
     * **静默失配**——用户设置了图标包图标却看不到任何变化，且不会有任何报错。
     */
    @Test
    fun `launcher icon entry is discovered from the template and replaced`() {
        val template = loadTemplate()
        val launcherEntries = ZipUtils.entryNames(template).filter { it.endsWith("/ic_launcher.webp") }
        assertTrue("模板应含 ic_launcher.webp 启动器条目", launcherEntries.isNotEmpty())
        val packIcon = "PACK-ICON-BYTES".toByteArray()

        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "启动器图标校验",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1)),
            iconBytes = mapOf(1 to "X".toByteArray()),
            packIconBytes = packIcon,
            showInDrawer = true,
        )

        // 所有密度的启动器条目都须被替换（多密度模板下任一条漏换都会在对应设备上显示占位图）
        launcherEntries.forEach { entry ->
            assertArrayEquals("图标包自身图标未落到 $entry", packIcon, ZipUtils.readEntry(out, entry))
        }
        // 不得引入孤儿/重复启动器条目：产物里的启动器条目集合应与模板一致
        assertEquals(
            "启动器条目集合应与模板一致（无孤儿/重复）",
            launcherEntries.sorted(),
            ZipUtils.entryNames(out).filter { it.endsWith("/ic_launcher.webp") }.sorted(),
        )
    }

    /**
     * 逐 chunk 遍历（仅顶层），返回 (type, offset) 列表；[AxmlReader.readU16/readU32] 为伴随对象。
     */
    private fun walkChunks(bytes: ByteArray): List<Pair<Int, Int>> {
        require(bytes.size >= 8) { "不是 AXML" }
        val total = AxmlReader.readU32(bytes, 4)
        val out = ArrayList<Pair<Int, Int>>()
        var off = 8
        while (off + 8 <= total) {
            val type = AxmlReader.readU16(bytes, off)
            val size = AxmlReader.readU32(bytes, off + 4)
            require(size >= 8 && off + size <= total) { "非法 chunk @$off" }
            out.add(type to off)
            off += size
        }
        return out
    }

    /**
     * 从中央目录解析目标条目的（压缩方式, 数据起点偏移）。
     * 走中央目录而非本地头：Java ZipOutputStream 对 DEFLATED 条目使用数据描述符，
     * 本地头的尺寸字段为 0，逐条扫描本地头会错位；中央目录则含真实尺寸与本地头偏移。
     */
    private fun localEntryInfo(zip: ByteArray, name: String): Pair<Int, Int> {
        // 1) 找 End Of Central Directory（签名 PK\x05\x06），从文件尾向前扫
        val eocdSig = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        var eocd = zip.size - 22
        while (eocd >= 0) {
            if (zip[eocd] == eocdSig[0] && zip[eocd + 1] == eocdSig[1] &&
                zip[eocd + 2] == eocdSig[2] && zip[eocd + 3] == eocdSig[3]
            ) break
            eocd--
        }
        require(eocd >= 0) { "未在产物中找到 EOCD" }
        val cdOffset = u32(zip, eocd + 16)

        // 2) 遍历中央目录条目（签名 PK\x01\x02）
        var pos = cdOffset
        while (pos + 4 <= zip.size &&
            zip[pos] == 0x50.toByte() && zip[pos + 1] == 0x4B.toByte() &&
            zip[pos + 2] == 0x01.toByte() && zip[pos + 3] == 0x02.toByte()
        ) {
            val method = u16(zip, pos + 10)
            val nameLen = u16(zip, pos + 28)
            val localHeaderOffset = u32(zip, pos + 42)
            val entryName = zip.copyOfRange(pos + 46, pos + 46 + nameLen).toString(Charsets.UTF_8)
            if (entryName == name) {
                // 3) 跳到本地头读其 extra 长度（本地头 extra 长度才决定数据起点）
                val localNameLen = u16(zip, localHeaderOffset + 26)
                val localExtraLen = u16(zip, localHeaderOffset + 28)
                val dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen
                return method to dataStart
            }
            // 中央目录条目长度 = 46 + 文件名 + 扩展 + 注释
            val extraLen = u16(zip, pos + 30)
            val commentLen = u16(zip, pos + 32)
            pos += 46 + nameLen + extraLen + commentLen
        }
        error("未在产物中找到条目：$name")
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
