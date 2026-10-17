package com.muxiao.iconpacker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.IconFileStore

/**
 * 全局默认值设置页。
 *
 * 定位："新建项目时预填什么"的口径，不属于任何项目（对比项目编辑对话框）。
 * 写即生效（SharedPreferences），返回列表页后新建表单与导出建议名即时跟随。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val state by settings.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingSection("新建项目默认值") {
                OutlinedTextField(
                    value = state.versionName,
                    onValueChange = settings::setVersionName,
                    label = { Text("默认版本名") },
                    singleLine = true,
                    supportingText = { Text("留空则用内置默认（1.0）") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.signerName,
                    onValueChange = settings::setSignerName,
                    label = { Text("默认签名者名") },
                    singleLine = true,
                    supportingText = { Text("v1 签名条目使用的文件名；留空则用内置默认") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "默认签名方案", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }

            SettingSection("导出产物文件名模板") {
                OutlinedTextField(
                    value = state.artifactTemplate,
                    onValueChange = settings::setArtifactTemplate,
                    label = { Text("文件名模板") },
                    singleLine = true,
                    supportingText = {
                        Text("占位符：{name} 项目名 · {version} 版本名 · {code} 版本号")
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "预览：${IconFileStore.suggestedArtifactName(state.artifactTemplate, "我的主题", "1.2.3", 7)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "说明：这些只是「新建时的预填值」，已建项目各自的配置不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}
