package com.muxiao.iconpacker.ui.packlist

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.core.packer.engine.MIN_SDK_FLOOR
import com.muxiao.iconpacker.core.packer.engine.PackageNameRules
import com.muxiao.iconpacker.core.packer.engine.TemplateInfo
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.PackEntity
import com.muxiao.iconpacker.data.PackSignConfig
import com.muxiao.iconpacker.domain.PackVersionRules
import com.muxiao.iconpacker.domain.messageRes
import com.muxiao.iconpacker.ui.components.AppChip
import com.muxiao.iconpacker.ui.components.FormSection
import com.muxiao.iconpacker.ui.components.ToggleInfoRow
import com.muxiao.iconpacker.ui.components.UiText
import com.muxiao.iconpacker.ui.components.asString
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Layout
import com.muxiao.iconpacker.ui.theme.Spacing

/** SDK 输入区间的上层只防误填（如把年份写进去），无引擎语义 */
private const val MAX_SDK = 99

/**
 * 新建/编辑项目对话框共用的表单状态（FR-02 全字段 + v7 签名配置）。
 *
 * 之前"新建"只有名称 + 图标两个字段，而编辑对话框可以改版本/包名/抽屉可见性——
 * 新建表单的能力被编辑表单吊着打。抽出的动机：两个对话框必须呈现**同一份字段
 * 集合**，否则任何新增字段都会重演一次"编辑能改、新建不能选"的错位。
 * 字段改动只更新内存，保存时由各自的对话框一次性落库。
 */
@Stable
class PackForm(source: PackEntity? = null, defaults: AppSettings.State? = null) {

    var name by mutableStateOf(source?.name ?: "")
    var packageName by mutableStateOf(source?.packageName ?: "")
    var versionName by mutableStateOf(
        source?.versionName
            ?: defaults?.versionName?.takeIf { it.isNotBlank() }
            ?: TemplateInfo.DEFAULT_VERSION_NAME,
    )

    /** 版本号原始输入；新建留空 = 从 1 起自动递增，编辑预填"下次将使用的值" */
    var versionCodeInput by mutableStateOf(source?.upcomingVersionCode?.toString() ?: "")
    var showInDrawer by mutableStateOf(source?.showInDrawer ?: true)

    /** 新建流程临时选中的启动器图标（尚未落库；编辑流程的图标走即时回调） */
    var iconUri by mutableStateOf<Uri?>(null)

    /**
     * 签名身份：编辑以库里的配置为准，新建才吃"设置 → 签名身份"的默认值。
     *
     * 默认值只带"方式 + 文件 + 别名"，密码永远为空——密码是可复用凭据，
     * 不写进 SharedPreferences（见 AppSettings.State.importedKeystore 的说明）。
     */
    var importedKey by mutableStateOf(
        if (source != null) {
            source.signMode == PackEntity.SIGN_MODE_IMPORTED
        } else {
            defaults?.importedKeystore == true && defaults.keystorePath.isNotBlank()
        },
    )
    var keystorePath by mutableStateOf(
        if (source != null) source.keystorePath.orEmpty() else defaults?.keystorePath.orEmpty(),
    )
    var keystorePassword by mutableStateOf(source?.keystorePassword ?: "")
    var keystoreAlias by mutableStateOf(
        if (source != null) source.keystoreAlias.orEmpty() else defaults?.keystoreAlias.orEmpty(),
    )
    var keyPassword by mutableStateOf(source?.keyPassword ?: "")
    var signerName by mutableStateOf(source?.signerName ?: defaults?.signerName ?: "")
    var schemeV1 by mutableStateOf(source?.signSchemeV1 ?: defaults?.schemeV1 ?: true)
    var schemeV2 by mutableStateOf(source?.signSchemeV2 ?: defaults?.schemeV2 ?: true)
    var schemeV3 by mutableStateOf(source?.signSchemeV3 ?: defaults?.schemeV3 ?: true)

    /** 产物 SDK 重写原始输入；留空 = 沿用模板值（库里以 0 哨兵存储，v8） */
    var minSdkInput by mutableStateOf(source?.minSdkOverride?.takeIf { it > 0 }?.toString() ?: "")
    var targetSdkInput by mutableStateOf(source?.targetSdkOverride?.takeIf { it > 0 }?.toString() ?: "")

    val trimmedName: String get() = name.trim()
    val trimmedPackageName: String get() = packageName.trim()
    val trimmedVersionName: String get() = versionName.trim()
    val versionCodeParsed: PackVersionRules.Parsed get() = PackVersionRules.parseCode(versionCodeInput)

    /** 空 = 新建时自动派生（编辑对话框预填实际包名，不会为空）；非空则走完整校验 */
    val packageNameIssue: PackageNameRules.Issue?
        get() = trimmedPackageName.takeIf { it.isNotEmpty() }?.let { PackageNameRules.validate(it) }

    val versionNameIssue: PackVersionRules.Issue? get() = PackVersionRules.validateName(trimmedVersionName)

    /** 校验结果以 UiText 承载（资源 id + 参数），文案由 UI 层按当前语言解析 */
    val versionCodeIssue: UiText?
        get() = if (versionCodeParsed is PackVersionRules.Parsed.Invalid) {
            UiText(PackVersionRules.Issue.CODE_NOT_POSITIVE.messageRes)
        } else {
            null
        }

    /** 签名配置校验：编译进表单（而非等到仓库层），保存按钮可以直接联动禁用 */
    val signIssue: UiText?
        get() = when {
            importedKey && keystorePath.isBlank() -> UiText(R.string.form_sign_needs_file)
            importedKey && keystoreAlias.isBlank() -> UiText(R.string.form_sign_needs_alias)
            !(schemeV1 || schemeV2 || schemeV3) -> UiText(R.string.form_sign_needs_scheme)
            else -> null
        }

    /** minSdk 重写折算值；null = 未填（沿用模板），非数字由 [minSdkIssue] 报告 */
    val minSdkParsed: Int? get() = minSdkInput.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

    /** targetSdk 重写折算值；null = 未填（沿用模板） */
    val targetSdkParsed: Int? get() = targetSdkInput.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

    val minSdkIssue: UiText?
        get() = when (val min = minSdkParsed) {
            null -> null
            !in MIN_SDK_FLOOR..MAX_SDK -> UiText(R.string.err_min_sdk_range, MIN_SDK_FLOOR, MAX_SDK)
            else -> null
        }

    val targetSdkIssue: UiText?
        get() {
            val target = targetSdkParsed ?: return null
            val min = minSdkParsed
            return when {
                target !in MIN_SDK_FLOOR..MAX_SDK ->
                    UiText(R.string.err_target_sdk_range, MIN_SDK_FLOOR, MAX_SDK)
                min != null && target < min -> UiText(R.string.err_target_below_min)
                else -> null
            }
        }

    val isValid: Boolean
        get() = trimmedName.isNotEmpty() &&
            packageNameIssue == null &&
            versionNameIssue == null &&
            versionCodeIssue == null &&
            signIssue == null &&
            minSdkIssue == null &&
            targetSdkIssue == null

    fun toSignConfig(): PackSignConfig = PackSignConfig(
        signMode = if (importedKey) PackEntity.SIGN_MODE_IMPORTED else PackEntity.SIGN_MODE_DEVICE,
        keystorePath = keystorePath.trim(),
        keystorePassword = keystorePassword,
        keystoreAlias = keystoreAlias.trim(),
        keyPassword = keyPassword,
        signerName = signerName.trim(),
        schemeV1 = schemeV1,
        schemeV2 = schemeV2,
        schemeV3 = schemeV3,
    )

    /** 密钥库文件选择成功后的回填（路径为私有目录中的真实文件） */
    fun applyKeystoreImport(path: String) {
        importedKey = true
        keystorePath = path
    }

    /**
     * 只清空选中的密钥库文件，保留"导入密钥库"开关与已填的别名/密码。
     *
     * 选错文件是局部问题，不该顺带把签名方式退回设备密钥、并抹掉已输入的凭据；
     * 要整个退回设备签名，用户直接关掉上面的开关即可。
     */
    fun clearKeystoreFile() {
        keystorePath = ""
    }
}

/** 新建对话框提交的完整表单（签名字段独立成组，仓库层一次性校验落库） */
data class PackDraft(
    val name: String,
    val packageName: String?,
    val versionName: String,
    val versionCodeInput: String,
    val showInDrawer: Boolean,
    val iconUri: Uri?,
    val sign: PackSignConfig,
    /** 产物 SDK 重写；null = 未填（沿用模板值，落库为 0 哨兵） */
    val minSdkOverride: Int?,
    val targetSdkOverride: Int?,
)

fun PackForm.toDraft(): PackDraft = PackDraft(
    name = trimmedName,
    packageName = trimmedPackageName.takeIf { it.isNotEmpty() },
    versionName = trimmedVersionName,
    versionCodeInput = versionCodeInput,
    showInDrawer = showInDrawer,
    iconUri = iconUri,
    sign = toSignConfig(),
    minSdkOverride = minSdkParsed,
    targetSdkOverride = targetSdkParsed,
)

/**
 * 项目全字段表单（新建与编辑对话框共用主体）。
 *
 * 图标区走回调（编辑流程的图标是即时落库操作，无法贴进"保存才写库"的模型）；
 * 签名区是纯表单字段。所有校验即时反馈，[PackForm.isValid] 控制保存按钮。
 *
 * 布局：统一 Column(spacedBy)，字段一律 fillMaxWidth——TextField 的默认最小
 * 宽度是"按内容伸缩"，不铺满会和周边控件错位。
 *
 * @param installedVersionCode 设备上已安装产物的版本号（编辑对话框用于降级警告）
 * @param currentPackVersionCode 项目当前版本号（编辑对话框用于"低于上次打包"警告）
 */
@Composable
fun PackFormFields(
    form: PackForm,
    onPickKeystore: () -> Unit,
    onClearKeystore: () -> Unit,
    modifier: Modifier = Modifier,
    iconContent: (@Composable () -> Unit)? = null,
    onPickIcon: (() -> Unit)? = null,
    onClearIcon: (() -> Unit)? = null,
    installedVersionCode: Long? = null,
    currentPackVersionCode: Int? = null,
) {
    // 分区之间 24dp、组内 12dp；层级由"间距 + 发丝线 + 标题"承担，不再堆卡片
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Layout.sectionGap),
    ) {
        FormSection(
            title = stringResource(R.string.form_section_basic),
            // 首段不画线：顶栏已经给出了页面边界
            showDivider = false,
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { form.name = it },
                label = { Text(stringResource(R.string.form_pack_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.versionName,
                onValueChange = { form.versionName = it },
                label = { Text(stringResource(R.string.form_version_name)) },
                singleLine = true,
                isError = form.versionNameIssue != null,
                supportingText = {
                    Text(
                        text = form.versionNameIssue?.messageRes
                            ?.let { stringResource(it) }
                            ?: stringResource(R.string.form_version_name_hint),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            VersionCodeField(
                form = form,
                installedVersionCode = installedVersionCode,
                currentPackVersionCode = currentPackVersionCode,
            )
            OutlinedTextField(
                value = form.packageName,
                onValueChange = { form.packageName = it },
                label = { Text(stringResource(R.string.form_package_name_label)) },
                singleLine = true,
                isError = form.packageNameIssue != null,
                supportingText = {
                    Text(
                        text = form.packageNameIssue?.messageRes()
                            ?.let { stringResource(it) }
                            ?: stringResource(R.string.form_package_name_hint),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 图标单独成段：它和上面的名称/包名不是一类东西，混在字段流里会被当成"又一个输入项"
        if (iconContent != null) {
            FormSection(
                title = stringResource(R.string.form_section_icon),
                hint = stringResource(R.string.form_section_optional),
            ) {
                PackIconField(
                    iconContent = iconContent,
                    onPickIcon = onPickIcon,
                    onClearIcon = onClearIcon,
                )
            }
        }

        FormSection(title = stringResource(R.string.form_section_build)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = form.minSdkInput,
                    onValueChange = { form.minSdkInput = it.filter(Char::isDigit).take(3) },
                    label = { Text("minSdk") },
                    singleLine = true,
                    isError = form.minSdkIssue != null,
                    supportingText = {
                        val issue = form.minSdkIssue
                        Text(
                            text = issue?.asString()
                                ?: stringResource(R.string.form_sdk_min_hint, MIN_SDK_FLOOR),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.targetSdkInput,
                    onValueChange = { form.targetSdkInput = it.filter(Char::isDigit).take(3) },
                    label = { Text("targetSdk") },
                    singleLine = true,
                    isError = form.targetSdkIssue != null,
                    supportingText = {
                        val issue = form.targetSdkIssue
                        Text(
                            text = issue?.asString()
                                ?: stringResource(R.string.form_sdk_target_hint),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            ToggleInfoRow(
                title = stringResource(R.string.form_show_in_drawer),
                subtitle = if (form.showInDrawer) {
                    stringResource(R.string.form_show_in_drawer_on)
                } else {
                    stringResource(R.string.form_show_in_drawer_off)
                },
                leading = {
                    Checkbox(
                        checked = form.showInDrawer,
                        onCheckedChange = { form.showInDrawer = it },
                    )
                },
            )
        }

        FormSection(title = stringResource(R.string.form_sign_section)) {
            ToggleInfoRow(
                title = stringResource(R.string.form_import_keystore),
                subtitle = if (form.importedKey) {
                    stringResource(R.string.form_import_keystore_on)
                } else {
                    stringResource(R.string.form_import_keystore_off)
                },
                leading = {
                    Switch(checked = form.importedKey, onCheckedChange = { form.importedKey = it })
                },
            )
            if (form.importedKey) {
                // 与设置页"签名身份"里的密钥库行同构：左文件信息、右操作。
                // 旧版是"一行文件名 + 另起一行两个文字按钮"，既不满宽也不成组
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_keystore_file),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            // 私有目录全路径对用户没有意义，只回显文件名
                            text = form.keystorePath.takeIf { it.isNotBlank() }
                                ?.let { java.io.File(it).name }
                                ?: stringResource(R.string.settings_keystore_not_chosen),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 选错文件时能退回去：只清空表单里的引用，不删私有目录里的副本
                    // （其它项目/设置页可能还在用它）
                    if (form.keystorePath.isNotBlank()) {
                        TextButton(
                            onClick = onClearKeystore,
                            contentPadding = PaddingValues(horizontal = Spacing.sm),
                        ) { Text(stringResource(R.string.common_clear)) }
                    }
                    TextButton(onClick = onPickKeystore) {
                        Text(
                            stringResource(
                                if (form.keystorePath.isBlank()) {
                                    R.string.form_pick_keystore
                                } else {
                                    R.string.settings_keystore_replace
                                },
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = form.keystorePassword,
                    onValueChange = { form.keystorePassword = it },
                    label = { Text(stringResource(R.string.form_keystore_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.keystoreAlias,
                    onValueChange = { form.keystoreAlias = it },
                    label = { Text(stringResource(R.string.form_key_alias)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.keyPassword,
                    onValueChange = { form.keyPassword = it },
                    label = { Text(stringResource(R.string.form_key_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = form.signerName,
                onValueChange = { form.signerName = it },
                label = { Text(stringResource(R.string.form_signer_name)) },
                singleLine = true,
                supportingText = {
                    Text(
                        text = stringResource(R.string.form_signer_name_hint),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.form_schemes),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AppChip(
                    selected = form.schemeV1,
                    label = "v1·JAR",
                    onClick = { form.schemeV1 = !form.schemeV1 },
                )
                AppChip(
                    selected = form.schemeV2,
                    label = "v2",
                    onClick = { form.schemeV2 = !form.schemeV2 },
                )
                AppChip(
                    selected = form.schemeV3,
                    label = "v3",
                    onClick = { form.schemeV3 = !form.schemeV3 },
                )
            }
            val signIssue = form.signIssue
            if (signIssue != null) {
                Text(
                    text = signIssue.asString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 启动器图标行：缩略图 + 状态说明 + 操作，三者同属一组。
 *
 * 归类靠**结构**而不是靠像素偏移：操作放进文字列内部，左边界由 Column 决定，
 * 与上方状态文字天然对齐。之前的写法是把操作另起一行、再手算
 * "缩略图宽 + 间距"的缩进——缩略图尺寸一变就错位，且那一行看起来像
 * 页面级的又一条目，正是"和上面混为一谈"的来源。
 *
 * 操作的两个文字按钮去掉水平内边距：不加的话按钮文字会比状态文字再右偏 12dp，
 * 视觉上又不齐；按钮高度仍由 M3 的 48dp 触控下限保证。
 */
@Composable
private fun PackIconField(
    iconContent: @Composable () -> Unit,
    onPickIcon: (() -> Unit)?,
    onClearIcon: (() -> Unit)?,
) {
    // 是否已设置由 onClearIcon 的存在与否表达，与调用方（form.iconUri / pack.iconPath）
    // 保持单一事实来源，不额外传 boolean 造成两处判断可能不一致
    val hasIcon = onClearIcon != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        // 顶对齐 + 与缩略图等高的文字块：两行说明和图标共用同一条中轴。
        // 若按旧写法让整行居中，居中的是"说明 + 按钮"这一整块（约 100dp），
        // 图标只有 52dp，就会被推到两行之间偏下的位置——看起来像配在"选择图片"旁边，
        // 而不是在解释这个图标。
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = if (onPickIcon != null) {
                Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onPickIcon)
            } else {
                Modifier
            },
            contentAlignment = Alignment.Center,
        ) {
            iconContent()
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            // 文字区至少与缩略图同高并垂直居中，超长（大字号/英文折三行）时自然撑开
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = IconSize.thumb),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = if (hasIcon) {
                        stringResource(R.string.form_icon_set_subtitle)
                    } else {
                        stringResource(R.string.form_icon_unset_subtitle)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 用 FlowRow 保证英文（"Change image" + "Remove icon"）在窄屏不溢出
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (onPickIcon != null) {
                    TextButton(
                        onClick = onPickIcon,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = Layout.touchTarget),
                    ) {
                        Text(
                            stringResource(
                                if (hasIcon) R.string.form_change_image else R.string.form_pick_image,
                            ),
                        )
                    }
                }
                if (onClearIcon != null) {
                    TextButton(
                        onClick = onClearIcon,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = Layout.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(IconSize.inline),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(
                            text = stringResource(R.string.form_remove_icon),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** 版本号字段 + 降级/回退警告（把编辑对话框原有的三条提示原样搬进来共用） */
@Composable
private fun VersionCodeField(
    form: PackForm,
    installedVersionCode: Long?,
    currentPackVersionCode: Int?,
) {
    val parsed = form.versionCodeParsed
    val explicit = (parsed as? PackVersionRules.Parsed.Explicit)?.code
    OutlinedTextField(
        value = form.versionCodeInput,
        onValueChange = { form.versionCodeInput = it.filter(Char::isDigit).take(10) },
        label = { Text(stringResource(R.string.form_version_code_label)) },
        singleLine = true,
        isError = form.versionCodeIssue != null,
        supportingText = {
            val issue = form.versionCodeIssue
            when {
                issue != null -> Text(
                    text = issue.asString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                explicit != null && installedVersionCode != null && explicit <= installedVersionCode -> Text(
                    text = stringResource(
                        R.string.form_version_code_warn_installed,
                        installedVersionCode,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                explicit != null && currentPackVersionCode != null && explicit <= currentPackVersionCode -> Text(
                    text = stringResource(
                        R.string.form_version_code_warn_last_pack,
                        currentPackVersionCode,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    text = stringResource(
                        R.string.form_version_code_preview,
                        nextVersionPreview(form, currentPackVersionCode),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        // 少了这一行就会退到 M3 的 280dp 默认最小宽度，比同列其它字段短 40dp
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "下次打包将使用 v 几"的预览值：显式指定的优先，否则在当前版本号上自动 +1 */
private fun nextVersionPreview(form: PackForm, current: Int?): Int =
    (form.versionCodeParsed as? PackVersionRules.Parsed.Explicit)?.code
        ?: PackVersionRules.autoNext(current ?: 1)
