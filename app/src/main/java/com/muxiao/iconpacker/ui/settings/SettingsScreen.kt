package com.muxiao.iconpacker.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.ServiceLocator
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.IconFileStore
import com.muxiao.iconpacker.data.LocaleMode
import com.muxiao.iconpacker.data.ThemeMode
import com.muxiao.iconpacker.ui.components.AppChip
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.SectionCard
import com.muxiao.iconpacker.ui.theme.Layout
import com.muxiao.iconpacker.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.io.File

/**
 * 全局默认值设置页。
 *
 * 定位："新建项目时预填什么"的口径，不属于任何项目（对比项目编辑对话框）。
 * 写即生效（SharedPreferences），返回列表页后新建表单与导出建议名即时跟随。
 *
 * 版式：每组一张内容卡（外观 / 语言 / 默认值 / 签名身份 / 导出模板），
 * 卡内选项一律 FlowRow 换行——旧版三枚芯片塞在 Row 里，窄屏或英文界面会溢出。
 *
 * 签名身份卡：可选"设备生成密钥"或"导入密钥库"。选导入时只预填
 * **文件 + 别名**，密码不在设置里保存——它是可复用凭据，写进明文 prefs
 * 等于给之后所有项目配一把通用钥匙；密码在新建/编辑表单里输入。
 *
 * 语言卡：切换 [LocaleMode] 后重建 Activity，attachBaseContext 重新包裹生效；
 * 中文/English 两项以各自语言自名（不随界面语言翻译）。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val state by settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 取文案走 LocalResources：LocalContext.current.getString 不随配置变化刷新，
    // 系统语言/字号在运行中改变后会拿到旧值（lint: LocalContextGetResourceValueCall）
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }

    // 密钥库文件导入：与项目编辑走同一条通道（复制进私有目录，SAF Uri 不落库）
    val keystorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            runCatching { ServiceLocator.repository.importKeystoreFile(uri) }
                .onSuccess { path ->
                    settings.setKeystorePath(path)
                    settings.setImportedKeystore(true)
                    importing = false
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.settings_keystore_imported, File(path).name),
                    )
                }
                .onFailure {
                    importing = false
                    snackbarHostState.showSnackbar(
                        resources.getString(
                            R.string.settings_keystore_import_failed,
                            it.message.orEmpty(),
                        ),
                    )
                }
        }
    }

    AppScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Layout.pageHorizontal, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Layout.cardGap),
        ) {
            SectionCard(title = stringResource(R.string.settings_section_appearance)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        AppChip(
                            selected = state.themeMode == mode,
                            label = stringResource(
                                when (mode) {
                                    ThemeMode.FollowSystem -> R.string.theme_mode_follow_system
                                    ThemeMode.Light -> R.string.theme_mode_light
                                    ThemeMode.Dark -> R.string.theme_mode_dark
                                },
                            ),
                            onClick = { settings.setThemeMode(mode) },
                        )
                    }
                }
                Spacer(modifier = Modifier.size(Spacing.sm))
                Text(
                    text = stringResource(
                        when (state.themeMode) {
                            ThemeMode.FollowSystem -> R.string.settings_theme_desc_follow
                            ThemeMode.Light -> R.string.settings_theme_desc_light
                            ThemeMode.Dark -> R.string.settings_theme_desc_dark
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = stringResource(R.string.settings_section_language)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    AppChip(
                        selected = state.localeMode == LocaleMode.FollowSystem,
                        label = stringResource(R.string.settings_language_system),
                        onClick = { switchLocale(settings, context, LocaleMode.FollowSystem) },
                    )
                    AppChip(
                        selected = state.localeMode == LocaleMode.Chinese,
                        label = "中文",
                        onClick = { switchLocale(settings, context, LocaleMode.Chinese) },
                    )
                    AppChip(
                        selected = state.localeMode == LocaleMode.English,
                        label = "English",
                        onClick = { switchLocale(settings, context, LocaleMode.English) },
                    )
                }
                Spacer(modifier = Modifier.size(Spacing.sm))
                Text(
                    text = stringResource(R.string.settings_language_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = stringResource(R.string.settings_section_defaults)) {
                OutlinedTextField(
                    value = state.versionName,
                    onValueChange = settings::setVersionName,
                    label = { Text(stringResource(R.string.settings_default_version_name)) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.settings_default_version_name_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- 签名身份：设备密钥 / 导入密钥库 + 方案 + 签名者名 ----
            SectionCard(title = stringResource(R.string.settings_section_signing)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    AppChip(
                        selected = !state.importedKeystore,
                        label = stringResource(R.string.settings_sign_mode_device),
                        onClick = { settings.setImportedKeystore(false) },
                    )
                    AppChip(
                        selected = state.importedKeystore,
                        label = stringResource(R.string.settings_sign_mode_imported),
                        onClick = { settings.setImportedKeystore(true) },
                    )
                }
                Spacer(modifier = Modifier.size(Spacing.sm))
                Text(
                    text = stringResource(
                        if (state.importedKeystore) {
                            R.string.settings_sign_mode_imported_desc
                        } else {
                            R.string.settings_sign_mode_device_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.importedKeystore) {
                    Spacer(modifier = Modifier.size(Spacing.md))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_keystore_file),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = state.keystorePath.takeIf { it.isNotBlank() }
                                    ?.let { File(it).name }
                                    ?: stringResource(R.string.settings_keystore_not_chosen),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = { if (!importing) keystorePicker.launch("*/*") },
                            enabled = !importing,
                            modifier = Modifier.heightIn(min = Layout.touchTarget),
                        ) {
                            Text(
                                stringResource(
                                    if (state.keystorePath.isBlank()) {
                                        R.string.settings_keystore_pick
                                    } else {
                                        R.string.settings_keystore_replace
                                    },
                                ),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.size(Spacing.sm))
                    OutlinedTextField(
                        value = state.keystoreAlias,
                        onValueChange = settings::setKeystoreAlias,
                        label = { Text(stringResource(R.string.settings_keystore_alias)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.size(Spacing.sm))
                    Text(
                        text = stringResource(R.string.settings_keystore_password_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.keystorePath.isNotBlank()) {
                        Spacer(modifier = Modifier.size(Spacing.xs))
                        TextButton(
                            onClick = { settings.clearKeystore() },
                            contentPadding = PaddingValues(horizontal = Spacing.xs),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_keystore_clear),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.size(Spacing.md))
                OutlinedTextField(
                    value = state.signerName,
                    onValueChange = settings::setSignerName,
                    label = { Text(stringResource(R.string.settings_default_signer_name)) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.settings_default_signer_name_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(Spacing.md))
                Text(
                    text = stringResource(R.string.settings_default_schemes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(Spacing.sm))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    AppChip(
                        selected = state.schemeV1,
                        label = "v1·JAR",
                        onClick = { settings.setSchemeV1(!state.schemeV1) },
                    )
                    AppChip(
                        selected = state.schemeV2,
                        label = "v2",
                        onClick = { settings.setSchemeV2(!state.schemeV2) },
                    )
                    AppChip(
                        selected = state.schemeV3,
                        label = "v3",
                        onClick = { settings.setSchemeV3(!state.schemeV3) },
                    )
                }
                if (!(state.schemeV1 || state.schemeV2 || state.schemeV3)) {
                    Spacer(modifier = Modifier.size(Spacing.sm))
                    Text(
                        text = stringResource(R.string.settings_schemes_all_off),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionCard(title = stringResource(R.string.settings_section_artifact_template)) {
                OutlinedTextField(
                    value = state.artifactTemplate,
                    onValueChange = settings::setArtifactTemplate,
                    label = { Text(stringResource(R.string.settings_artifact_template)) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.settings_artifact_template_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(Spacing.sm))
                Text(
                    text = stringResource(
                        R.string.settings_artifact_preview,
                        IconFileStore.suggestedArtifactName(
                            state.artifactTemplate,
                            stringResource(R.string.settings_artifact_preview_sample),
                            "1.2.3",
                            7,
                        ),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.settings_footer_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }
    }
}

/** 落盘语言选择并重建 Activity：attachBaseContext 阶段才注入 Configuration */
private fun switchLocale(settings: AppSettings, context: android.content.Context, mode: LocaleMode) {
    if (settings.state.value.localeMode == mode) return
    settings.setLocaleMode(mode)
    (context as? Activity)?.recreate()
}
