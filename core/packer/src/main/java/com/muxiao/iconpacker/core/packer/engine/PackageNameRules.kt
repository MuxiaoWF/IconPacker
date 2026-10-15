package com.muxiao.iconpacker.core.packer.engine

/**
 * 图标包包名校验与派生规则。
 *
 * 为什么需要它：模板 APK 的 applicationId 是固定的 [TemplateInfo.PACKAGE_NAME]，
 * 打包时若不改写清单里的 `package` 属性，所有产物在系统眼里都是同一个应用——
 * 第二个包要么把第一个覆盖掉，要么因 versionCode 更低被拒（INSTALL_FAILED_VERSION_DOWNGRADE）。
 * 让每个项目持有独立包名，才谈得上共存。
 *
 * 校验口径对齐 Android 的 `PackageParser`：
 * 仅 ASCII 字母/数字/下划线/点，至少两段，每段以字母或下划线开头，总长 ≤ 255。
 * 比系统更严一点是刻意的——系统对某些畸形包名"能装上但行为诡异"，
 * 与其运行期排查，不如在输入框就拦掉。
 */
object PackageNameRules {

    /** 包名长度上限（与 PackageParser 的校验一致） */
    const val MAX_LENGTH = 255

    /**
     * 保留包名：本工具自身与系统命名空间。
     *
     * 图标包用这些包名会导致"覆盖掉本应用"或与系统冲突，必须拒绝。
     */
    private val RESERVED = setOf(
        "android",
        "com.android",
        "com.muxiao.iconpacker",
    )

    /** 校验失败原因（文案直接面向用户，见 [message]） */
    enum class Issue(val message: String) {
        EMPTY("包名不能为空"),
        TOO_LONG("包名过长，最多 255 个字符"),
        TOO_FEW_SEGMENTS("包名至少需要两段，例如 com.example.pack"),
        BLANK_SEGMENT("包名不能以点开头或结尾，也不能有连续的点"),
        SEGMENT_START("每一段必须以字母或下划线开头"),
        INVALID_CHARS("只能使用英文字母、数字、下划线和点"),
        RESERVED("该包名已被系统或本应用占用，请换一个"),
    }

    /** 校验包名；返回 null 表示合法 */
    fun validate(name: String): Issue? {
        if (name.isBlank()) return Issue.EMPTY
        if (name.length > MAX_LENGTH) return Issue.TOO_LONG
        if (name in RESERVED) return Issue.RESERVED

        val segments = name.split('.')
        if (segments.size < 2) return Issue.TOO_FEW_SEGMENTS

        for (segment in segments) {
            if (segment.isEmpty()) return Issue.BLANK_SEGMENT
            if (!isAsciiLetter(segment[0]) && segment[0] != '_') return Issue.SEGMENT_START
            for (c in segment) {
                if (!isAsciiLetter(c) && !c.isAsciiDigit() && c != '_') return Issue.INVALID_CHARS
            }
        }
        return null
    }

    /** 便捷判定 */
    fun isValid(name: String): Boolean = validate(name) == null

    /** 校验并抛出；参数非法语义，固定 [IllegalArgumentException]（文案面向用户） */
    fun requireValid(name: String) {
        validate(name)?.let { throw IllegalArgumentException(it.message) }
    }

    /**
     * 为指定项目派生默认包名。
     *
     * 用项目 id 作后缀：id 在库内唯一且不变，因此派生的包名天然不重复，
     * 也不随项目改名而变动（包名一旦装上就与已安装应用绑定，绝不能跟着 UI 改名漂移）。
     */
    fun suggest(packId: Long): String = "${TemplateInfo.PACKAGE_NAME}.p$packId"

    private fun isAsciiLetter(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z')

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
