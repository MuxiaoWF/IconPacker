package com.muxiao.iconpacker.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.data.LauncherCompat
import com.muxiao.iconpacker.data.LauncherSupport
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.StatusRow
import com.muxiao.iconpacker.ui.components.asString
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Spacing

/**
 * 首次启动引导（FR-01）。
 *
 * 验收对应：识别已安装兼容启动器（1）、跳转默认应用设置（2）、
 * 可跳过且不重复弹出（3，跳过标记见 [OnboardingPrefs]）。
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(
        factory = OnboardingViewModel.factory(LocalContext.current),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AppScaffold(title = stringResource(R.string.onboarding_title)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyLarge,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_prereq_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_prereq_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // 当前启动器状态（检测中 / 未检测到 / 支持 / 部分支持 / 未能确认）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    when {
                        state.checking -> CheckingRow()
                        else -> StatusRow(
                            icon = when {
                                state.currentLauncherLabel == null -> Icons.Default.Info
                                state.currentSupportLevel == LauncherSupport.SupportLevel.SUPPORTED ->
                                    Icons.Default.CheckCircle
                                state.currentSupportLevel == LauncherSupport.SupportLevel.LIMITED ->
                                    Icons.Default.Warning
                                else -> Icons.Default.Info
                            },
                            iconTint = when {
                                state.currentLauncherLabel == null -> MaterialTheme.colorScheme.outline
                                state.currentSupportLevel == LauncherSupport.SupportLevel.SUPPORTED ->
                                    MaterialTheme.colorScheme.primary
                                state.currentSupportLevel == LauncherSupport.SupportLevel.LIMITED ->
                                    MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline
                            },
                            text = when (state.currentSupportLevel) {
                                LauncherSupport.SupportLevel.SUPPORTED ->
                                    stringResource(
                                        R.string.onboarding_status_supported,
                                        state.currentLauncherLabel.orEmpty(),
                                    )
                                LauncherSupport.SupportLevel.LIMITED ->
                                    stringResource(
                                        R.string.onboarding_status_limited,
                                        state.currentLauncherLabel.orEmpty(),
                                    )
                                else ->
                                    if (state.currentLauncherLabel == null) {
                                        stringResource(R.string.onboarding_status_none)
                                    } else {
                                        stringResource(
                                            R.string.onboarding_status_unknown,
                                            state.currentLauncherLabel.orEmpty(),
                                        )
                                    }
                            },
                        )
                    }
                    state.currentGuidance?.let { guidance ->
                        Text(
                            text = guidance.asString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 已检测到的启动器
            Text(
                text = stringResource(R.string.onboarding_detected_count, state.availableLaunchers.size),
                style = MaterialTheme.typography.titleSmall,
            )
            state.availableLaunchers.forEach { launcher ->
                LauncherListRow(launcher)
            }

            // 三个动作统一铺满宽度：主操作（设启动器）在先，随后是检测与进入应用，
            // 视觉层级靠顺序与描边样式区分
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            LauncherCompat(context).defaultLauncherSettingsIntent(),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_set_default_launcher)) }
            OutlinedButton(
                onClick = viewModel::refresh,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_redetect)) }
            OutlinedButton(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_enter_app)) }
            Text(
                text = stringResource(R.string.onboarding_skip_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 检测中行：小进度圈 + 文案 */
@Composable
private fun CheckingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(IconSize.progressSmall))
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(stringResource(R.string.onboarding_checking), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 单个启动器行：名称 + 支持级别徽标（支持 / 需尝试 / 未知） */
@Composable
private fun LauncherListRow(launcher: LauncherCompat.LauncherInfo) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (launcher.nameRes?.let { stringResource(it) } ?: launcher.label) +
                if (launcher.isCurrent) {
                    stringResource(R.string.launcher_current_suffix)
                } else {
                    ""
                },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        val badge = when (launcher.supportLevel) {
            LauncherSupport.SupportLevel.SUPPORTED ->
                stringResource(R.string.launcher_badge_supported)
            LauncherSupport.SupportLevel.LIMITED ->
                stringResource(R.string.launcher_badge_limited)
            LauncherSupport.SupportLevel.UNKNOWN ->
                stringResource(R.string.launcher_badge_unknown)
        }
        val color = when (launcher.supportLevel) {
            LauncherSupport.SupportLevel.SUPPORTED -> MaterialTheme.colorScheme.primary
            LauncherSupport.SupportLevel.LIMITED -> MaterialTheme.colorScheme.tertiary
            LauncherSupport.SupportLevel.UNKNOWN -> MaterialTheme.colorScheme.outline
        }
        Text(text = badge, style = MaterialTheme.typography.labelSmall, color = color)
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
            .edit {
                putBoolean(KEY_SHOWN, true)
            }
    }
}
