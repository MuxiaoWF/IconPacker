package com.muxiao.iconpacker.ui.packlist

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.muxiao.iconpacker.core.packer.engine.MIN_SDK_FLOOR
import com.muxiao.iconpacker.core.packer.engine.PackageNameRules
import com.muxiao.iconpacker.core.packer.engine.TemplateInfo
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.PackEntity
import com.muxiao.iconpacker.data.PackSignConfig
import com.muxiao.iconpacker.domain.PackVersionRules

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

    var importedKey by mutableStateOf(source?.signMode == PackEntity.SIGN_MODE_IMPORTED)
    var keystorePath by mutableStateOf(source?.keystorePath ?: "")
    var keystorePassword by mutableStateOf(source?.keystorePassword ?: "")
    var keystoreAlias by mutableStateOf(source?.keystoreAlias ?: "")
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

    val versionCodeIssue: String?
        get() = if (versionCodeParsed is PackVersionRules.Parsed.Invalid) {
            PackVersionRules.Issue.CODE_NOT_POSITIVE.message
        } else {
            null
        }

    /** 签名配置校验：编译进表单（而非等到仓库层），保存按钮可以直接联动禁用 */
    val signIssue: String?
        get() = when {
            importedKey && keystorePath.isBlank() -> "导入密钥库需先选择密钥库文件"
            importedKey && keystoreAlias.isBlank() -> "导入密钥库需填写密钥别名"
            !(schemeV1 || schemeV2 || schemeV3) -> "至少保留一种签名方案"
            else -> null
        }

    /** minSdk 重写折算值；null = 未填（沿用模板），非数字由 [minSdkIssue] 报告 */
    val minSdkParsed: Int? get() = minSdkInput.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

    /** targetSdk 重写折算值；null = 未填（沿用模板） */
    val targetSdkParsed: Int? get() = targetSdkInput.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

    val minSdkIssue: String?
        get() = when (val min = minSdkParsed) {
            null -> null
            !in MIN_SDK_FLOOR..MAX_SDK -> "minSdk 需在 $MIN_SDK_FLOOR～$MAX_SDK 之间"
            else -> null
        }

    val targetSdkIssue: String?
        get() {
            val target = targetSdkParsed ?: return null
            val min = minSdkParsed
            return when {
                target !in MIN_SDK_FLOOR..MAX_SDK -> "targetSdk 需在 $MIN_SDK_FLOOR～$MAX_SDK 之间"
                min != null && target < min -> "targetSdk 不得低于 minSdk"
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

    fun clearKeystore() {
        importedKey = false
        keystorePath = ""
        keystorePassword = ""
        keystoreAlias = ""
        keyPassword = ""
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
    Column(modifier = modifier) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { form.name = it },
            label = { Text("图标包名称") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = form.versionName,
            onValueChange = { form.versionName = it },
            label = { Text("版本名") },
            singleLine = true,
            isError = form.versionNameIssue != null,
            supportingText = {
                Text(
                    text = form.versionNameIssue?.message
                        ?: "显示在系统的应用信息里；版本号与版本名是两件事",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        VersionCodeField(
            form = form,
            installedVersionCode = installedVersionCode,
            currentPackVersionCode = currentPackVersionCode,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = form.packageName,
            onValueChange = { form.packageName = it },
            label = { Text("包名（可选，留空自动生成）") },
            singleLine = true,
            isError = form.packageNameIssue != null,
            supportingText = {
                Text(
                    text = form.packageNameIssue?.message
                        ?: ("每个图标包需要独立包名才能在系统里共存；改包名后要重新打包安装，" +
                            "旧包名会作为独立应用留在设备上（可自行卸载）"),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "产物 SDK（可选，留空沿用模板）",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.minSdkInput,
                onValueChange = { form.minSdkInput = it.filter(Char::isDigit).take(3) },
                label = { Text("minSdk") },
                singleLine = true,
                isError = form.minSdkIssue != null,
                supportingText = {
                    val issue = form.minSdkIssue
                    Text(
                        text = issue ?: "留空沿用模板（$MIN_SDK_FLOOR）；抬高可拦掉装不上的老机型",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
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
                        text = issue ?: "留空沿用模板；一般不带 minSdk 单独抬高",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = form.showInDrawer,
                onCheckedChange = { form.showInDrawer = it },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = "在应用抽屉显示", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (form.showInDrawer) {
                        "抽屉里能看到这个图标包，点开是启用提示"
                    } else {
                        "只作为主题资源，不占抽屉位置"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (iconContent != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "图标（在启动器里显示）" + if (onPickIcon == null) "，可选" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                iconContent()
                Spacer(modifier = Modifier.width(12.dp))
                if (onPickIcon != null) TextButton(onClick = onPickIcon) { Text("选择图片") }
                if (onClearIcon != null) TextButton(onClick = onClearIcon) { Text("清除") }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "签名设置",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = form.importedKey, onCheckedChange = { form.importedKey = it })
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = "使用导入的密钥库", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (form.importedKey) {
                        "用你自己的密钥签名，所有设备产出的图标包可用同一身份互相覆盖"
                    } else {
                        "默认：密钥在本机 Keystore 生成，不可导出"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (form.importedKey) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = form.keystorePath.ifBlank { "尚未选择密钥库文件" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onPickKeystore) { Text("选择密钥库文件") }
                if (form.keystorePath.isNotBlank()) {
                    TextButton(onClick = onClearKeystore) { Text("清除") }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = form.keystorePassword,
                onValueChange = { form.keystorePassword = it },
                label = { Text("密钥库密码") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = form.keystoreAlias,
                onValueChange = { form.keystoreAlias = it },
                label = { Text("密钥别名") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = form.keyPassword,
                onValueChange = { form.keyPassword = it },
                label = { Text("私钥密码（留空则与密钥库密码相同）") },
                singleLine = true,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = form.signerName,
            onValueChange = { form.signerName = it },
            label = { Text("签名者名（可选）") },
            singleLine = true,
            supportingText = {
                Text(
                    text = "v1 签名条目的文件名；留空则用默认值",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "签名方案", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.schemeV1,
                onClick = { form.schemeV1 = !form.schemeV1 },
                label = { Text("v1·JAR") },
            )
            FilterChip(
                selected = form.schemeV2,
                onClick = { form.schemeV2 = !form.schemeV2 },
                label = { Text("v2") },
            )
            FilterChip(
                selected = form.schemeV3,
                onClick = { form.schemeV3 = !form.schemeV3 },
                label = { Text("v3") },
            )
        }
        val signIssue = form.signIssue
        if (signIssue != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = signIssue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
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
        label = { Text("下次打包版本号（留空自动 +1）") },
        singleLine = true,
        isError = form.versionCodeIssue != null,
        supportingText = {
            val issue = form.versionCodeIssue
            when {
                issue != null -> Text(
                    text = issue,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                explicit != null && installedVersionCode != null && explicit <= installedVersionCode -> Text(
                    text = "低于设备上已安装的 v$installedVersionCode，覆盖安装会被系统拒绝（需先卸载）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                explicit != null && currentPackVersionCode != null && explicit <= currentPackVersionCode -> Text(
                    text = "低于上次打包的 v$currentPackVersionCode，装回旧版本同样会被拒绝",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    text = "下次打包将使用 v${nextVersionPreview(form, currentPackVersionCode)}；清空则回到自动递增",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
}

/** "下次打包将使用 v 几"的预览值：显式指定的优先，否则在当前版本号上自动 +1 */
private fun nextVersionPreview(form: PackForm, current: Int?): Int =
    (form.versionCodeParsed as? PackVersionRules.Parsed.Explicit)?.code
        ?: PackVersionRules.autoNext(current ?: 1)
