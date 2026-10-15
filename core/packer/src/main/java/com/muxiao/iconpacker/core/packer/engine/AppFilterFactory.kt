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

    /** 与模板模块 tools/gen_template_placeholders.py 的 SLOTS 保持一致 */
    const val MAX_SLOTS = 1024
}
