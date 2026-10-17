package com.muxiao.iconpacker.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.IconFileStore
import com.muxiao.iconpacker.data.LocaleMode
import com.muxiao.iconpacker.data.ThemeMode
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.SectionHeader
import com.muxiao.iconpacker.ui.theme.Spacing

/**
 * 全局默认值设置页。
 *
 * 定位："新建项目时预填什么"的口径，不属于任何项目（对比项目编辑对话框）。
 * 写即生效（SharedPreferences），返回列表页后新建表单与导出建议名即时跟随。
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

    AppScaffold(title = stringResource(R.string.settings_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Card {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    SectionHeader(stringResource(R.string.settings_section_appearance))
                    // 立即生效：AppRoot 收集状态即时换肤
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.themeMode == mode,
                                onClick = { settings.setThemeMode(mode) },
                                label = {
                                    Text(
                                        text = when (mode) {
                                            ThemeMode.FollowSystem ->
                                                stringResource(R.string.theme_mode_follow_system)
                                            ThemeMode.Light ->
                                                stringResource(R.string.theme_mode_light)
                                            ThemeMode.Dark ->
                                                stringResource(R.string.theme_mode_dark)
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Text(
                        text = when (state.themeMode) {
                            ThemeMode.FollowSystem ->
                                stringResource(R.string.settings_theme_desc_follow)
                            ThemeMode.Light ->
                                stringResource(R.string.settings_theme_desc_light)
                            ThemeMode.Dark ->
                                stringResource(R.string.settings_theme_desc_dark)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    SectionHeader(stringResource(R.string.settings_section_language))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FilterChip(
                            selected = state.localeMode == LocaleMode.FollowSystem,
                            onClick = { switchLocale(settings, context, LocaleMode.FollowSystem) },
                            label = { Text(stringResource(R.string.settings_language_system)) },
                        )
                        FilterChip(
                            selected = state.localeMode == LocaleMode.Chinese,
                            onClick = { switchLocale(settings, context, LocaleMode.Chinese) },
                            label = { Text("中文") },
                        )
                        FilterChip(
                            selected = state.localeMode == LocaleMode.English,
                            onClick = { switchLocale(settings, context, LocaleMode.English) },
                            label = { Text("English") },
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_language_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    SectionHeader(stringResource(R.string.settings_section_defaults))
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
                    Text(
                        text = stringResource(R.string.settings_default_schemes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FilterChip(
                            selected = state.schemeV1,
                            onClick = { settings.setSchemeV1(!state.schemeV1) },
                            label = { Text("v1·JAR") },
                        )
                        FilterChip(
                            selected = state.schemeV2,
                            onClick = { settings.setSchemeV2(!state.schemeV2) },
                            label = { Text("v2") },
                        )
                        FilterChip(
                            selected = state.schemeV3,
                            onClick = { settings.setSchemeV3(!state.schemeV3) },
                            label = { Text("v3") },
                        )
                    }
                    if (!(state.schemeV1 || state.schemeV2 || state.schemeV3)) {
                        Text(
                            text = stringResource(R.string.settings_schemes_all_off),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    SectionHeader(stringResource(R.string.settings_section_artifact_template))
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
            }

            Text(
                text = stringResource(R.string.settings_footer_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
