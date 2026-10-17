package com.muxiao.iconpacker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.muxiao.iconpacker.domain.PackVersionRules

/**
 * 图标包项目（FR-02）。
 *
 * @param versionCode **最近一次打包使用的版本号**；从未打包时为 1。它决定产物能否覆盖
 *  安装（新包的 versionCode 低于设备上已装版本会被 `INSTALL_FAILED_VERSION_DOWNGRADE`
 *  直接拒绝），因此每次打包默认自动 +1（见 [PackEntity.nextVersionCode]）。
 * @param versionName 版本名，写进清单的 `versionName`：系统"应用信息"里显示的那一行。
 *  与 [versionCode] 是两件事——前者给人看（`2.1.0`、`2026.10` 都行），后者给系统
 *  判断新旧。用户可自由编辑。
 * @param nextVersionCode 用户**显式指定**的下次打包版本号；null = 自动取 [versionCode] + 1。
 *  单独存一个字段而不是让用户直接改 [versionCode]：那样"想指定下一个版本是 10"
 *  就得填 9，语义绕。打包提交时（[PackDao.commitVersion]）用它覆盖 [versionCode]
 *  并清空，于是"指定一次"与"之后继续自动递增"可以自然衔接。
 * @param lastPackedAt 最近一次成功打包的时间戳；null = 从未打包
 * @param iconPath 图标包**自身**的启动器图标（192px PNG，应用私有目录）；
 *        null = 打包时沿用模板占位图（一个 125 字节的空白 PNG，产物图标会显示为空白方块）
 * @param showInDrawer 图标包是否出现在应用抽屉。true = 带 MAIN/LAUNCHER 入口，
 *        用户能在抽屉里看到并点开；false = 纯主题资源包，只在启动器图标样式列表里出现
 * @param packageName 产物包名，写入清单的 `package` 属性。**每个项目必须不同**，
 *  否则产物在系统里互相覆盖（详见 `PackageNameRules`）。
 *  `@ColumnInfo(defaultValue)` 的值必须与迁移里 ALTER TABLE 的 DEFAULT
 *  严格一致，否则 Room 会在打开库时判定"迁移未正确执行"而抛异常。
 *
 * 签名配置族（v7）：让产物签名可由用户逐项目自定义——用谁的密钥、v1 签名者名与
 * 签名方案组合，都随项目持久化（同一份配置可被"复制项目"带走到新项目）。
 * @param signMode 签名模式：[SIGN_MODE_DEVICE]（默认，设备 Keystore 密钥）或
 *        [SIGN_MODE_IMPORTED]（用户导入的密钥库文件）。常量族而不是 enum：
 *        Room 存 int 稳定，且取值语义写在常量 doc 里。
 * @param keystorePath 导入密钥库在私有目录的全路径（全局 `keystores/` 目录，
 *        跨项目共享——复制项目直接引用同一路径，不重复落盘）；signMode=0 时忽略
 * @param keystorePassword 密钥库打开密码。明文存于应用私有数据库，是导入密钥
 *        场景下的可接受折衷（详见 ImportedKeystoreProvider 头注释）
 * @param keystoreAlias 私钥条目别名
 * @param keyPassword 私钥密码；空 = 沿用 keystorePassword
 * @param signerName v1 签名者名（META-INF 下 .SF/.RSA 文件名）；空 = 各模式默认值
 * @param signSchemeV1/V2/V3 签名方案开关，默认全开（历史行为）
 * @param minSdkOverride 产物 minSdk 重写；**0 = 沿用模板值**（v8 哨兵，0 不可能是合法
 *        SDK 值，下限为模板基线 `MIN_SDK_FLOOR`=24）。语义是"产物能装到多老的设备"，
 *        抬高即可拦掉 INSTALL_FAILED_OLDER_SDK；写入 PackRequest 前用 takeIf 归一化
 * @param targetSdkOverride 产物 targetSdk 重写；**0 = 沿用模板值**。不得低于
 *        minSdkOverride（若两者都显式指定） */
@Entity(tableName = "packs")
data class PackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val versionCode: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
    val lastPackedAt: Long? = null,
    val iconPath: String? = null,
    val showInDrawer: Boolean = true,
    /** 默认值为模板基础包名：仅供 v4 存量数据回填使用，新建项目一律派生唯一包名 */
    @ColumnInfo(defaultValue = "'com.muxiao.icon'")
    val packageName: String = "com.muxiao.icon",
    /** 默认值与 `TemplateInfo.DEFAULT_VERSION_NAME`、模板 defaultConfig 三处必须一致 */
    @ColumnInfo(defaultValue = "'1.0'")
    val versionName: String = "1.0",
    val nextVersionCode: Int? = null,
    @ColumnInfo(defaultValue = "0")
    val signMode: Int = SIGN_MODE_DEVICE,
    val keystorePath: String? = null,
    val keystorePassword: String? = null,
    val keystoreAlias: String? = null,
    val keyPassword: String? = null,
    val signerName: String? = null,
    @ColumnInfo(defaultValue = "1")
    val signSchemeV1: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val signSchemeV2: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val signSchemeV3: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val minSdkOverride: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val targetSdkOverride: Int = 0,
) {
    /**
     * 下次打包将使用的版本号：用户显式指定的优先，否则在最近版本上自动 +1。
     *
     * 放在实体上而不是包一层工具函数，是为了让"下次会打出版本几"这件事**只有一个来源**：
     * 打包（[PackRepository.prepareBuild]）与界面预告（项目卡片、版本输入框）读的是同一个值，
     * 不会出现"界面显示 v5、实际打出 v6"这类只能靠打包两次才能发现的错位。
 */
    val upcomingVersionCode: Int
        get() = nextVersionCode ?: PackVersionRules.autoNext(versionCode)

    companion object {
        /** 设备端生成密钥（AndroidKeyStore，不可导出） */
        const val SIGN_MODE_DEVICE = 0

        /** 用户导入的密钥库文件（跨设备同签名身份） */
        const val SIGN_MODE_IMPORTED = 1
    }
}

/**
 * 项目签名配置的纯值视图（UI ↔ [PackEntity] ↔ 打包引擎之间的传输对象）。
 *
 * 空串与 null 的语义：`signerName` 空表示"用该模式默认签名者名"，
 * 其余导入参数仅在 [signMode] 为 `SIGN_MODE_IMPORTED` 时有意义。
 */
data class PackSignConfig(
    val signMode: Int = PackEntity.SIGN_MODE_DEVICE,
    val keystorePath: String = "",
    val keystorePassword: String = "",
    val keystoreAlias: String = "",
    val keyPassword: String = "",
    val signerName: String = "",
    val schemeV1: Boolean = true,
    val schemeV2: Boolean = true,
    val schemeV3: Boolean = true,
)

/** 读取项目的签名配置（编辑对话框回填与"改动检测"共用同一视图，避免字段散抄） */
fun PackEntity.toSignConfig(): PackSignConfig = PackSignConfig(
    signMode = signMode,
    keystorePath = keystorePath.orEmpty(),
    keystorePassword = keystorePassword.orEmpty(),
    keystoreAlias = keystoreAlias.orEmpty(),
    keyPassword = keyPassword.orEmpty(),
    signerName = signerName.orEmpty(),
    schemeV1 = signSchemeV1,
    schemeV2 = signSchemeV2,
    schemeV3 = signSchemeV3,
)

/**
 * 一条应用映射：组件 → 图标槽位（生成 appfilter.xml 的条目，docs/pack-engine.md）。
 *
 * @param component `ComponentInfo{包名/活动类}`，必须是默认启动 Activity
 */
@Entity(
    tableName = "mappings",
    foreignKeys = [
        ForeignKey(
            entity = PackEntity::class,
            parentColumns = ["id"],
            childColumns = ["packId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("packId"),
        Index(value = ["packId", "component"], unique = true),
    ],
)
data class MappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packId: Long,
    val component: String,
    val slot: Int,
)

/**
 * 图标池中的一张图标。
 *
 * @param slot 槽位号 1..1024，对应模板资源 ic_%04d（分配规则见 [PackRepository]）
 * @param sourcePath 私有目录中的**原图**文件路径（导入时拷贝，永不修改）
 * @param originalName **当前可得的最佳匹配键**，而非单纯的原始文件名：
 *  ZIP 导入时由 `ZipIconPlanner.matchKeyOf` 推导（文件名是包名 → 包名；
 *  路径里有包名目录 → 该目录；都不成立才用文件名），照片选择器导入时同样按此规则处理。
 *  它同时是自动匹配（AppMatcher / ImportBinder）的输入与图标库的显示名——
 *  `com.tencent.mm/icon.png` 显示成 `com.tencent.mm` 比显示 `icon` 有用得多。
 *  字段名沿用历史命名，未随语义改名以避免无谓的数据库迁移。
 * @param editedPath 编辑产物路径（FR-05：形状/背景/缩放合成后的 WebP）；
 *        为空表示使用原图。编辑非破坏性：原图保留，可随时还原。
 */
@Entity(
    tableName = "icon_assets",
    foreignKeys = [
        ForeignKey(
            entity = PackEntity::class,
            parentColumns = ["id"],
            childColumns = ["packId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("packId"),
        Index(value = ["packId", "slot"], unique = true),
    ],
)
data class IconAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packId: Long,
    val slot: Int,
    val sourcePath: String,
    val originalName: String,
    val addedAt: Long,
    val editedPath: String? = null,
)
