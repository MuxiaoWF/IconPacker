package com.muxiao.iconpacker.ui.settings

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.IconFileStore
import com.muxiao.iconpacker.data.ThemeMode
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.SectionHeader
import com.muxiao.iconpacker.ui.theme.Spacing

/**
 * 全局默认值设置页。
 *
 * 定位："新建项目时预填什么"的口径，不属于任何项目（对比项目编辑对话框）。
 * 写即生效（SharedPreferences），返回列表页后新建表单与导出建议名即时跟随。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val state by settings.state.collectAsStateWithLifecycle()

    AppScaffold(title = "设置", onBack = onBack) { innerPadding ->
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
                    SectionHeader("外观")
                    // 立即生效：AppRoot 收集状态即时换肤
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.themeMode == mode,
                                onClick = { settings.setThemeMode(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Text(
                        text = when (state.themeMode) {
                            ThemeMode.FollowSystem -> "界面随系统的深/浅色设置自动切换"
                            ThemeMode.Light -> "固定使用浅色界面"
                            ThemeMode.Dark -> "固定使用深色界面"
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
                    SectionHeader("新建项目默认值")
                    OutlinedTextField(
                        value = state.versionName,
                        onValueChange = settings::setVersionName,
                        label = { Text("默认版本名") },
                        singleLine = true,
                        supportingText = { Text("留空则用内置默认（1.0）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.signerName,
                        onValueChange = settings::setSignerName,
                        label = { Text("默认签名者名") },
                        singleLine = true,
                        supportingText = { Text("v1 签名条目使用的文件名；留空则用内置默认") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(text = "默认签名方案", style = MaterialTheme.typography.bodySmall)
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
                            text = "已全部关闭：新建设置保存时会被要求至少保留一种签名方案，" +
                                "「创建」按钮将处于禁用状态",
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
                    SectionHeader("导出产物文件名模板")
                    OutlinedTextField(
                        value = state.artifactTemplate,
                        onValueChange = settings::setArtifactTemplate,
                        label = { Text("文件名模板") },
                        singleLine = true,
                        supportingText = {
                            Text("占位符：{name} 项目名 · {version} 版本名 · {code} 版本号")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "预览：${IconFileStore.suggestedArtifactName(state.artifactTemplate, "我的主题", "1.2.3", 7)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "说明：这些只是「新建时的预填值」，已建项目各自的配置不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
