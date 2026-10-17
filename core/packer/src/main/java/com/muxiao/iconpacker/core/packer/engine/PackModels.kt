package com.muxiao.iconpacker.core.packer.engine

import com.muxiao.iconpacker.core.packer.sign.SigningKeyProvider
import com.muxiao.iconpacker.core.packer.sign.SigningSchemes

/** 一条映射：应用组件 → 图标槽位号（槽位号 n 对应模板资源 ic_%04d 格式名） */
data class IconMapping(
    val component: String,
    val slot: Int,
)

/** 槽位图标的来源文件（已导入图标池中的文件路径） */
data class SlotIcon(
    val slot: Int,
    val sourcePath: String,
)

/** SDK 重写的下限：与模板 minSdk（template/build.gradle.kts defaultConfig）一致 */
const val MIN_SDK_FLOOR = 24

/** 一次打包请求（不可变输入，打包过程不修改） */
data class PackRequest(
    /** 图标包名称（写入清单 label，即启动器里显示的名字） */
    val packName: String,
    /** 版本号，决定能否覆盖安装（必须不小于设备上已装版本） */
    val versionCode: Int,
    /**
     * 版本名（写入清单 versionName，即系统"应用信息"里显示的版本）。
     * 与 [versionCode] 是两件事：前者给人看（`2.1.0`、`2026.10` 都行），
     * 后者给系统判断新旧。此前引擎只改 versionCode，所有产物的版本名都停在模板的
     * `1.0`——用户设置了自定义版本却在系统里看不见，等于没生效。
     */
    val versionName: String = TemplateInfo.DEFAULT_VERSION_NAME,
    /** 组件 → 槽位 映射，生成 appfilter.xml */
    val mappings: List<IconMapping>,
    /** 槽位 → 图标文件，全部进入 drawable.xml（含未映射的，供启动器手动选图标） */
    val icons: List<SlotIcon>,
    /** 模板 APK 路径（assets 中的 template.apk） */
    val templateApkPath: String,
    /** 产物输出路径 */
    val outputPath: String,
    /** 设备端签名密钥提供者 */
    val signingKeyProvider: SigningKeyProvider,
    /**
     * 图标包自身的启动器图标（已编码好的 192px PNG 字节）。
     * null = 沿用模板占位图。放在这里而非给路径，是因为 [PackAssembler] 是纯 Kotlin、
     * 不碰 Android 的图片解码，编码必须由调用方（app 模块）完成。
     */
    val packIconBytes: ByteArray? = null,
    /**
     * 图标包是否出现在应用抽屉。true = 带 MAIN/LAUNCHER 入口（用户可在抽屉里看到
     * 并点开）；false = 纯主题资源包，只在启动器的图标样式列表里出现。
     */
    val showInDrawer: Boolean = true,
    /**
     * 产物包名，写入清单的 `package` 属性。
     * 默认沿用模板包名（等价于旧行为：同一时间设备上只能装一个本工具产出的图标包，
     * 重复打包走覆盖升级）。**多项目共存必须为每个项目传不同值**——否则第二个产物
     * 要么覆盖掉第一个，要么因 versionCode 更低被系统拒绝安装。
     * 规则与派生见 [PackageNameRules]。
     */
    val packageName: String = TemplateInfo.PACKAGE_NAME,
    /**
     * 要启用的 APK 签名方案组合。默认三方案同签（历史行为）；用户可在项目
     * 签名设置里按需开关（个别启动器/平台对某些方案组合有兼容癖好时）。
     */
    val signingSchemes: SigningSchemes = SigningSchemes(),
    /**
     * 产物 minSdk 重写（写入清单 `<uses-sdk android:minSdkVersion>`；null = 沿用模板值）。
     * 设备上装不上 minSdk 过低的包（`INSTALL_FAILED_OLDER_SDK`），这里让用户抬高
     * 项目的实际生效门槛；校验见 [createPack]/表单（minSdk ≥ 24，与模板一致）。
     * 模板缺 `<uses-sdk>` 时降级保持模板原值（不阻断打包）。
     */
    val overrideMinSdk: Int? = null,
    /**
     * 产物 targetSdk 重写（写入清单 `<uses-sdk android:targetSdkVersion>`；null = 沿用模板值）。
     * targetSdk 影响运行时行为开关（如 scoped storage），个别启动器生态对高 targetSdk
     * 的图标包解析有兼容差异，用户可按需下调。不得小于 minSdk。
     */
    val overrideTargetSdk: Int? = null,
) {
    init {
        require(packName.isNotBlank()) { "图标包名称不能为空" }
        require(versionCode > 0) { "versionCode 必须为正整数（覆盖安装依赖递增）" }
        require(versionName.isNotBlank()) { "版本名不能为空（清单里的 versionName 不允许空串）" }
        require(mappings.isNotEmpty()) { "至少需要一条映射" }
        require(icons.isNotEmpty()) { "至少需要一个图标" }
        require(signingSchemes.v1 || signingSchemes.v2 || signingSchemes.v3) {
            "至少需要启用一种签名方案"
        }
        require(overrideMinSdk == null || overrideMinSdk >= MIN_SDK_FLOOR) {
            "minSdk 重写不得低于 $MIN_SDK_FLOOR（模板基线）"
        }
        require(overrideTargetSdk == null || overrideMinSdk == null || overrideTargetSdk >= overrideMinSdk) {
            "targetSdk 重写不得低于 minSdk 重写"
        }
        PackageNameRules.requireValid(packageName)
        val slots = mappings.map { it.slot }.toSet()
        val provided = icons.map { it.slot }.toSet()
        require(slots.all { it in provided }) {
            "映射引用了未提供文件的槽位：${slots - provided}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PackRequest

        if (versionCode != other.versionCode) return false
        if (showInDrawer != other.showInDrawer) return false
        if (packName != other.packName) return false
        if (versionName != other.versionName) return false
        if (mappings != other.mappings) return false
        if (icons != other.icons) return false
        if (templateApkPath != other.templateApkPath) return false
        if (outputPath != other.outputPath) return false
        if (signingKeyProvider != other.signingKeyProvider) return false
        if (!packIconBytes.contentEquals(other.packIconBytes)) return false
        if (packageName != other.packageName) return false
        if (signingSchemes != other.signingSchemes) return false
        if (overrideMinSdk != other.overrideMinSdk) return false
        if (overrideTargetSdk != other.overrideTargetSdk) return false

        return true
    }

    override fun hashCode(): Int {
        var result = versionCode
        result = 31 * result + showInDrawer.hashCode()
        result = 31 * result + packName.hashCode()
        result = 31 * result + versionName.hashCode()
        result = 31 * result + mappings.hashCode()
        result = 31 * result + icons.hashCode()
        result = 31 * result + templateApkPath.hashCode()
        result = 31 * result + outputPath.hashCode()
        result = 31 * result + signingKeyProvider.hashCode()
        result = 31 * result + (packIconBytes?.contentHashCode() ?: 0)
        result = 31 * result + packageName.hashCode()
        result = 31 * result + signingSchemes.hashCode()
        result = 31 * result + overrideMinSdk.hashCode()
        result = 31 * result + overrideTargetSdk.hashCode()
        return result
    }
}

/** 打包步骤（进度回调的 step 参数；顺序即执行顺序） */
enum class PackStep { PREPARE, MANIFEST, AXML, ICONS, ZIP, SIGN, DONE }

/** 打包结果 */
sealed interface PackResult {
    data class Success(
        val outputPath: String,
        val sizeBytes: Long,
        /** 签名者主题摘要（如 CN=IconPacker Device Signer） */
        val signerSubject: String,
        /** 产物版本码（导出命名与版本对比用） */
        val versionCode: Int,
        /** 产物版本名（写进清单的 versionName，结果页展示用） */
        val versionName: String = TemplateInfo.DEFAULT_VERSION_NAME,
    /**
         * 产物包名。回显给调用方用于安装状态查询——多项目共存时每个产物包名不同，
         * 不能再拿 [TemplateInfo.PACKAGE_NAME] 去问"装没装上"。
     */
        val packageName: String = TemplateInfo.PACKAGE_NAME,
    ) : PackResult

    data class Failure(
        val step: PackStep,
        val message: String,
        val cause: Throwable? = null,
    ) : PackResult
}

/** 进度回调：(当前步骤, 步骤内当前进度, 步骤内总数)。ICONS 步骤逐图标回调，其余为 0/1 二值。 */
typealias PackProgress = (PackStep, Int, Int) -> Unit
