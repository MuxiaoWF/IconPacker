package com.muxiao.iconpacker.domain

/**
 * 图标包版本规则（FR-02 补充）。
 *
 * 版本有两层，含义完全不同，UI 上必须分成两个输入项而不是一个：
 * - **版本号（versionCode）**——整数，系统用它判断新旧。新包的号**低于**设备上已装版本时，
 *  安装器会直接以 `INSTALL_FAILED_VERSION_DOWNGRADE` 拒绝，所以它默认每次打包自动 +1；
 *  用户也可以显式指定（例如想接着线下发布的号继续排）。
 * - **版本名（versionName）**——字符串，纯粹给人看：系统"应用信息"里显示的那一行，
 *  `2.1.0`、`2026.10`、`内测3` 都行。
 *
 * 纯函数，可 JVM 单测。
 */
object PackVersionRules {

    /** 版本名长度上限：清单里的一条字符串，没必要更长 */
    const val MAX_NAME_LENGTH = 64

    /**
     * 版本号上限。
     *
     * Android 的 versionCode 是 int32，且 Google Play 的上限是 2_100_000_000。
     * 这里取 2_000_000_000 并**留出递增余量**——真正会出问题的不是"填得太大"，
     * 而是自动递增在边界上溢出成负数（负号会被打包请求直接拒绝，
     * 表现成"打包按钮点了没反应"，极难定位）。
     */
    const val MAX_CODE = 2_000_000_000

    /** 校验失败原因（文案直接面向用户） */
    enum class Issue(val message: String) {
        NAME_EMPTY("版本名不能为空"),
        NAME_TOO_LONG("版本名最多 $MAX_NAME_LENGTH 个字符"),
        CODE_NOT_POSITIVE("版本号必须是大于 0 的整数"),
        CODE_TOO_LARGE("版本号过大，最大 $MAX_CODE"),
    }

    /** 校验版本名；返回 null 表示合法 */
    fun validateName(name: String): Issue? = when {
        name.isBlank() -> Issue.NAME_EMPTY
        name.length > MAX_NAME_LENGTH -> Issue.NAME_TOO_LONG
        else -> null
    }

    /**
     * 校验版本号；返回 null 表示合法。
     *
     * `null` 是合法输入——它表示"不显式指定、按上次 +1 自动递增"，
     * 不是"用户填了个空值"。
     */
    fun validateCode(code: Int?): Issue? = when {
        code == null -> null
        code <= 0 -> Issue.CODE_NOT_POSITIVE
        code > MAX_CODE -> Issue.CODE_TOO_LARGE
        else -> null
    }

    /**
     * 自动递增的下一个版本号。
     *
     * 到 [MAX_CODE] 后原地不动而不是 +1 溢出成负数：宁可让用户显式指定一个新号，
     * 也不产出一个负数版本号。
     */
    fun autoNext(current: Int): Int = if (current >= MAX_CODE) MAX_CODE else current + 1

    /**
     * 解析界面输入的版本号。
     *
     * 空白 → [Parsed.Auto]（回到自动递增）；数字且合法 → [Parsed.Explicit]；
     * 其余 → [Parsed.Invalid]。把"留空"和"填错"分开，是因为二者的界面反馈完全不同：
     * 留空是常态（绝大多数用户不会去动版本号），填错必须立刻报错且禁止保存。
     */
    fun parseCode(input: String): Parsed {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Parsed.Auto
        val code = trimmed.toIntOrNull() ?: return Parsed.Invalid
        return if (validateCode(code) == null) Parsed.Explicit(code) else Parsed.Invalid
    }

    sealed interface Parsed {
        /** 留空：不显式指定，按上次版本 +1 */
        data object Auto : Parsed

        /** 用户显式填写的版本号 */
        data class Explicit(val code: Int) : Parsed

        /** 非法输入（非数字、非正数、超上限） */
        data object Invalid : Parsed
    }
}
