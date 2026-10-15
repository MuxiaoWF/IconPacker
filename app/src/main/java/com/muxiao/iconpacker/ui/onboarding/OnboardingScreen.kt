package com.muxiao.iconpacker.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxiao.iconpacker.data.LauncherCompat
import com.muxiao.iconpacker.data.LauncherSupport

/**
 * 首次启动引导（FR-01）。
 *
 * 验收对应：识别已安装兼容启动器（1）、跳转默认应用设置（2）、
 * 可跳过且不重复弹出（3，跳过标记见 [OnboardingPrefs]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(
        factory = OnboardingViewModel.factory(LocalContext.current),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("欢迎使用 IconPacker") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "IconPacker 让你上传自有图标，一键打包成可安装的图标包 APK。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "使用前提：兼容的启动器",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "多数 OEM 系统启动器（Pixel Launcher、One UI Home、MIUI 等）" +
                            "使用自有主题机制，不直接应用标准第三方图标包；但 ColorOS 11+ 已支持" +
                            "第三方图标包。最稳妥的方式是安装一个支持图标包的启动器" +
                            "（如 Nova、Lawnchair、Apex），并在其图标样式设置里启用本应用生成的图标包。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 当前启动器状态（三态：支持 / 部分支持 / 未确认）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            state.checking -> {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("正在检测…", style = MaterialTheme.typography.bodyMedium)
                            }
                            state.currentLauncherLabel == null -> {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("未检测到默认启动器", style = MaterialTheme.typography.bodyMedium)
                            }
                            state.currentSupportLevel == LauncherSupport.SupportLevel.SUPPORTED -> {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "当前启动器「${state.currentLauncherLabel}」支持图标包",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            state.currentSupportLevel == LauncherSupport.SupportLevel.LIMITED -> {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "当前启动器「${state.currentLauncherLabel}」可能支持图标包（需在启动器设置中应用）",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            else -> {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "未能确认「${state.currentLauncherLabel ?: "当前启动器"}」是否支持图标包，建议安装 Nova / Lawnchair",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    if (state.currentGuidance != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.currentGuidance!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 已检测到的启动器
            Text(
                text = "已检测到 ${state.availableLaunchers.size} 个启动器",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            state.availableLaunchers.forEach { launcher ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = launcher.label + if (launcher.isCurrent) "（当前）" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val badge = when (launcher.supportLevel) {
                        LauncherSupport.SupportLevel.SUPPORTED -> "支持图标包"
                        LauncherSupport.SupportLevel.LIMITED -> "需尝试"
                        LauncherSupport.SupportLevel.UNKNOWN -> "未知"
                    }
                    val color = when (launcher.supportLevel) {
                        LauncherSupport.SupportLevel.SUPPORTED -> MaterialTheme.colorScheme.primary
                        LauncherSupport.SupportLevel.LIMITED -> MaterialTheme.colorScheme.tertiary
                        LauncherSupport.SupportLevel.UNKNOWN -> MaterialTheme.colorScheme.outline
                    }
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                LauncherCompat(context).defaultLauncherSettingsIntent(),
                            )
                        }
                    },
                ) { Text("设置默认启动器") }
                OutlinedButton(onClick = viewModel::refresh) { Text("重新检测") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text("进入应用")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "暂不设置也能进入应用；之后随时可以在系统设置里更换默认启动器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 引导完成标记（一次性，进程重启不重复弹出） */
object OnboardingPrefs {
    private const val PREFS_NAME = "onboarding"
    private const val KEY_SHOWN = "shown"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOWN, false)

    fun setDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOWN, true)
            .apply()
    }
}
