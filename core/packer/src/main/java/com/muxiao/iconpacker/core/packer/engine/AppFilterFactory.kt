package com.muxiao.iconpacker.core.packer.engine

import com.muxiao.iconpacker.core.packer.axml.AttrValue
import com.muxiao.iconpacker.core.packer.axml.AxmlAttr
import com.muxiao.iconpacker.core.packer.axml.AxmlElement

/**
 * appfilter.xml / drawable.xml 文档构造（纯 Kotlin，可 JVM 单测）。
 *
 * 命名规范集中在此：槽位号 n ↔ 资源名 ic_%04d ↔ APK 内条目文件名 `ic_%04d.png`
 * （模板 arsc 预声明 1024 个，见 template 模块）。
 *
 * **本类不提供"条目完整路径"**：所在目录（`res/drawable-xxxhdpi-v4/` 之类）由 aapt2
 * 依据源工程的限定符编译决定，必须由 `PackAssembler` 从模板条目实际探测——
 * 这里再放一份常量就等于给同一事实留两个来源，模板一改限定符便会静默失配（已发生过两次）。
 */
object AppFilterFactory {

    const val SLOT_PREFIX = "ic_"
    private const val SLOT_DIGITS = 4

    /** 图标条目扩展名 */
    const val SLOT_SUFFIX = ".png"

    /** 槽位号 → 资源名（ic_0001 ~ ic_1024） */
    fun slotName(slot: Int): String {
        require(slot in 1..MAX_SLOTS) { "槽位号必须在 1..$MAX_SLOTS：$slot" }
        return SLOT_PREFIX + slot.toString().padStart(SLOT_DIGITS, '0')
    }

    /** 槽位号 → APK 内条目文件名（不含目录） */
    fun slotFileName(slot: Int): String = slotName(slot) + SLOT_SUFFIX

    /**
     * appfilter.xml：`<resources>` 根 + 每条映射一个 `<item component drawable>`。
     * 组件名必须是默认启动 Activity 的 `ComponentInfo{包名/活动类}`（docs/pack-engine.md）。
     */
    fun buildAppFilter(mappings: List<IconMapping>): AxmlElement =
        AxmlElement(
            name = "resources",
            children = mappings.map { mapping ->
                AxmlElement(
                    name = "item",
                    attributes = listOf(
                        AxmlAttr("component", AttrValue.Str(mapping.component)),
                        AxmlAttr("drawable", AttrValue.Str(slotName(mapping.slot))),
                    ),
                )
            },
        )

    /**
     * drawable.xml：`<resources>` 根 + 每个槽位一个 `<item drawable>`。
     * 传入全部已导入图标（含未映射的）——启动器的"手动选图标"选择器枚举此文件，
     * 用户之后可对任意应用手动指派。
     */
    fun buildDrawableList(slots: Collection<Int>): AxmlElement =
        AxmlElement(
            name = "resources",
            children = slots.distinct().sorted().map { slot ->
                AxmlElement(
                    name = "item",
                    attributes = listOf(AxmlAttr("drawable", AttrValue.Str(slotName(slot)))),
                )
            },
        )

    /**
     * appmap.xml：CandyBar/Blueprint 生态的组件映射表（Atom Launcher 等启动器消费）。
     *
     * 格式自真实图标包模板核对（zixpo/candybar、jahirfiquitiva/Blueprint 的
     * `app/src/main/res/xml/appmap.xml`）：
     * ```
     * <appmap>
     *     <item class="com.example.app.MainActivity" name="drawable_name" />
     * </appmap>
     * ```
     * 语义字段是**类名**而非 appfilter 那种 `ComponentInfo{}` 整串：`class` 是目标
     * 活动的完整类名，`name` 是图标包内的资源名。注意生态里没有 componentmap.xml——
     * Blueprint 官方 wiki 要求的映射组件只有 appmap（曾计划生成 componentmap，
     * 经全生态核实后确认该文件不存在，见 docs/features.md）。
     *
     * 条目归一化：`ComponentInfo{pkg/.MainActivity}` 展开为 `pkg.MainActivity`，
     * 相对类名以包名为前缀（这是 appfilter 允许的短写，启动器解析时同样展开）。
     * 无法解析出包名/类名的条目直接丢弃——Atom launcher 对残缺条目不报错但不生效，
     * 写进去不如不写。
     */
    fun buildAppMap(mappings: List<IconMapping>): AxmlElement =
        AxmlElement(
            name = "appmap",
            children = mappings.mapNotNull { mapping ->
                activityClassOf(mapping.component)?.let { klass ->
                    AxmlElement(
                        name = "item",
                        attributes = listOf(
                            AxmlAttr("class", AttrValue.Str(klass)),
                            AxmlAttr("name", AttrValue.Str(slotName(mapping.slot))),
                        ),
                    )
                }
            },
        )

    /**
     * 从 `ComponentInfo{包名/活动类}` 提取完整活动类名。
     *
     * appfilter 的 component 三种写法（各图标包写法不一，见 IconPackManifest）：
     * `ComponentInfo{pkg/cls}` / `pkg/cls`（部分厂商省略前缀）/ `pkg/`（缺活动类）。
     * 相对类名（`.cls`）按包名展开；仅给包名的条目没有类名可写，返回 null。
     */
    private fun activityClassOf(component: String): String? {
        val inner = component.trim()
            .trimStart('{')
            .removePrefix(COMPONENT_INFO_PREFIX)
            .trimStart('{')
            .removeSuffix("}")
            .trim()
        val pkg = inner.substringBefore('/').trim()
        val cls = inner.substringAfter('/', "").trim()
        if (pkg.isEmpty() || cls.isEmpty()) return null
        return if (cls.startsWith(".")) pkg + cls else cls
    }

    private const val COMPONENT_INFO_PREFIX = "ComponentInfo"

    /** 与模板模块 tools/gen_template_placeholders.py 的 SLOTS 保持一致 */
    const val MAX_SLOTS = 1024
}
