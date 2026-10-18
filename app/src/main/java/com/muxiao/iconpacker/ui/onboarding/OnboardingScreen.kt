package com.muxiao.iconpacker.ui.onboarding

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.data.LauncherCompat
import com.muxiao.iconpacker.data.LauncherSupport
import com.muxiao.iconpacker.ui.components.AppDivider
import com.muxiao.iconpacker.ui.components.ListRow
import com.muxiao.iconpacker.ui.components.SectionCard
import com.muxiao.iconpacker.ui.components.StatusBadge
import com.muxiao.iconpacker.ui.components.Tone
import com.muxiao.iconpacker.ui.components.asString
import com.muxiao.iconpacker.ui.theme.BrandInk
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Layout
import com.muxiao.iconpacker.ui.theme.LocalAppPalette
import com.muxiao.iconpacker.ui.theme.Radius
import com.muxiao.iconpacker.ui.theme.Spacing

/**
 * 首次启动引导（FR-01）。
 *
 * 验收对应：识别已安装兼容启动器（1）、跳转默认应用设置（2）、
 * 可跳过且不重复弹出（3，跳过标记见 [OnboardingPrefs]）。
 *
 * 版式：这是唯一一条"没有上级页面"的路由，所以不再套顶栏，改成
 * 品牌头（应用图标 + 名称）→ 引导卡 → 启动器检测 → 固定底栏主操作。
 * 旧版把三个按钮竖着铺满、重要性相同的，主次完全靠阅读顺序区分。
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
    val palette = LocalAppPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.page),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Layout.pageHorizontal)
                .padding(top = Spacing.xxl, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Layout.cardGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- 品牌头：居中，图标 72dp；应用名与副标题同列居中，不与正文抢层级 ----
            // 底板取启动图标自身的墨绿，前景用 drawable-*dpi 的 PNG：
            // mipmap/ic_launcher 在 API 26+ 是 adaptive-icon XML，painterResource 不支持
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(BrandInk),
                contentAlignment = Alignment.Center,
            ) {
                // 前景 PNG 自带 108dp 画布的安全边距，直接按 64dp 画会显得很小；
                // 放大到 96dp 由 72dp 的底板裁掉多余透明边，图形刚好占满底板
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
            }
            Spacer(modifier = Modifier.size(Spacing.sm))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                // 这里没有 Surface 提供默认内容色，不显式给色会继承黑色
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onboarding_brand_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(Spacing.sm))

            Text(
                text = stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard(
                title = stringResource(R.string.onboarding_prereq_title),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_prereq_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 当前启动器状态：状态徽标取代三行彩色文字判断 ----
            SectionCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.onboarding_detected_count, state.availableLaunchers.size),
                trailing = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.onboarding_redetect),
                        )
                    }
                },
            ) {
                if (state.checking) {
                    CheckingRow()
                } else {
                    CurrentLauncherStatus(state)
                    state.currentGuidance?.let { guidance ->
                        Spacer(modifier = Modifier.size(Spacing.xs))
                        Text(
                            text = guidance.asString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.availableLaunchers.isNotEmpty()) {
                    Spacer(modifier = Modifier.size(Spacing.sm))
                    AppDivider()
                    state.availableLaunchers.forEachIndexed { index, launcher ->
                        LauncherListRow(launcher)
                        if (index != state.availableLaunchers.lastIndex) {
                            AppDivider(startIndent = Spacing.xs)
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.onboarding_skip_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        }

        // ---- 固定底栏：进入应用为主操作，设置默认启动器为次操作 ----
        Surface(color = palette.card, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                AppDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Layout.pageHorizontal, vertical = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    LauncherCompat(context).defaultLauncherSettingsIntent(),
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Layout.touchTarget),
                    ) { Text(stringResource(R.string.onboarding_set_default_launcher), maxLines = 1) }
                    Button(
                        onClick = onFinish,
                        modifier = Modifier
                            .weight(0.8f)
                            .heightIn(min = Layout.touchTarget),
                    ) { Text(stringResource(R.string.onboarding_enter_app), maxLines = 1) }
                }
            }
        }
    }
}

/** 当前启动器一行：图标 + 文案 + 语义徽标 */
@Composable
private fun CurrentLauncherStatus(state: OnboardingUiState) {
    val supported = state.currentSupportLevel == LauncherSupport.SupportLevel.SUPPORTED
    val limited = state.currentSupportLevel == LauncherSupport.SupportLevel.LIMITED
    val tone = when {
        state.currentLauncherLabel == null -> Tone.Neutral
        supported -> Tone.Success
        limited -> Tone.Warning
        else -> Tone.Neutral
    }
    val icon = when {
        state.currentLauncherLabel == null -> Icons.Default.Info
        supported -> Icons.Default.CheckCircle
        limited -> Icons.Default.Warning
        else -> Icons.Default.Info
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when (tone) {
                Tone.Success -> LocalAppPalette.current.success
                Tone.Warning -> LocalAppPalette.current.warning
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(IconSize.inline),
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = when (state.currentSupportLevel) {
                LauncherSupport.SupportLevel.SUPPORTED ->
                    stringResource(R.string.onboarding_status_supported, state.currentLauncherLabel.orEmpty())

                LauncherSupport.SupportLevel.LIMITED ->
                    stringResource(R.string.onboarding_status_limited, state.currentLauncherLabel.orEmpty())

                else ->
                    if (state.currentLauncherLabel == null) {
                        stringResource(R.string.onboarding_status_none)
                    } else {
                        stringResource(R.string.onboarding_status_unknown,
                            state.currentLauncherLabel
                        )
                    }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        StatusBadge(
            text = stringResource(
                when {
                    supported -> R.string.launcher_badge_supported
                    limited -> R.string.launcher_badge_limited
                    else -> R.string.launcher_badge_unknown
                },
            ),
            tone = tone,
        )
    }
}

/** 检测中行：小进度圈 + 文案 */
@Composable
private fun CheckingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(IconSize.progressSmall), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(stringResource(R.string.onboarding_checking), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 单个启动器行：名称 + 支持级别徽标（支持 / 需尝试 / 未知） */
@Composable
private fun LauncherListRow(launcher: LauncherCompat.LauncherInfo) {
    val tone = when (launcher.supportLevel) {
        LauncherSupport.SupportLevel.SUPPORTED -> Tone.Success
        LauncherSupport.SupportLevel.LIMITED -> Tone.Warning
        LauncherSupport.SupportLevel.UNKNOWN -> Tone.Neutral
    }
    ListRow(
        title = (launcher.nameRes?.let { stringResource(it) } ?: launcher.label) +
            if (launcher.isCurrent) stringResource(R.string.launcher_current_suffix) else "",
        trailing = {
            StatusBadge(
                text = stringResource(
                    when (launcher.supportLevel) {
                        LauncherSupport.SupportLevel.SUPPORTED -> R.string.launcher_badge_supported
                        LauncherSupport.SupportLevel.LIMITED -> R.string.launcher_badge_limited
                        LauncherSupport.SupportLevel.UNKNOWN -> R.string.launcher_badge_unknown
                    },
                ),
                tone = tone,
            )
        },
    )
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
