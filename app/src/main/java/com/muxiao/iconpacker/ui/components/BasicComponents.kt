package com.muxiao.iconpacker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Spacing

/**
 * 全应用统一的 Scaffold 骨架：标题 + 可选返回键 + 右侧操作 + Snackbar + FAB。
 *
 * 此前 5 个 Screen 手写同构模板（TopAppBar / borrowed padding / SnackbarHost 各摆一遍），
 * 收口到一处后各页面只关心内容本身。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    }
                },
                actions = actions,
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        floatingActionButton = { floatingActionButton?.invoke() },
        bottomBar = { bottomBar?.invoke() },
        content = content,
    )
}

/**
 * 对话框内容的标准滚动容器：内容超出可用高度时自动可滚，
 * 上限取屏高的 75%——替代此前散落的 360/420/460dp 固定值，
 * 小屏与横屏下不再溢出，大屏下也不再浪费空间。
 */
@Composable
fun ScrollableDialogContent(
    modifier: Modifier = Modifier,
    contentSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .heightIn(max = maxHeight),
        verticalArrangement = Arrangement.spacedBy(contentSpacing),
    ) {
        content()
    }
}

/** 通知卡的语义级别；颜色槽位统一从 colorScheme 派生 */
enum class NoticeKind { Info, Warning, Error, Success }

/**
 * 通知/结果卡：BuildScreen 5 张同构卡片与 Onboarding 引导卡的统一载体。
 * 可带一个动作按钮（如"前往设置"/"返回"）。
 */
@Composable
fun NoticeCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    kind: NoticeKind = NoticeKind.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = when (kind) {
        NoticeKind.Error ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        NoticeKind.Success ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        NoticeKind.Warning ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        NoticeKind.Info ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.first, contentColor = colors.second),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.size(Spacing.xs))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = colors.second,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.size(Spacing.sm))
                TextButton(onClick = onAction) { Text(actionLabel) }            }
        }
    }
}

/** 一个可选色块：36dp 圆 + 选中加深描边；透明项在圆心标」示文字 */
data class ColorSwatch(val color: Int, val label: String = "")

/**
 * core 色板色值 → 本地化色名资源 id（core 侧的中文色名仅作内部标识；
 * 色值是稳定的引擎常量，按值映射即可与 core 精确对齐）。返回 0 表示无对应映射。
 */
fun colorSwatchLabelRes(color: Int): Int = when (color) {
    android.graphics.Color.TRANSPARENT -> R.string.color_none
    0xFFF5F7FA.toInt() -> R.string.color_paper_white
    0xFF212121.toInt() -> R.string.color_ink_black
    0xFF1565C0.toInt() -> R.string.color_brand_blue
    0xFF00897B.toInt() -> R.string.color_teal
    0xFFF9A825.toInt() -> R.string.color_amber
    0xFFE53935.toInt() -> R.string.color_vermilion
    android.graphics.Color.WHITE -> R.string.color_white
    0xFFBDBDBD.toInt() -> R.string.color_light_gray
    else -> 0
}

/**
 * 色板行：编辑器背景/边框色与"统一形状"对话框共用同一实现。
 * [entries] 中 [ColorSwatch.color] 为 android.graphics.Color 值（与引擎常量同源）。
 */
@Composable
fun ColorSwatchRow(
    entries: List<ColorSwatch>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        entries.forEach { swatch ->
            val isSelected = selected == swatch.color
            val transparent = swatch.color == android.graphics.Color.TRANSPARENT
            Box(
                modifier = Modifier
                    .size(IconSize.swatch)
                    .clip(CircleShape)
                    .background(if (transparent) Color.White else Color(swatch.color))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(swatch.color) },
                contentAlignment = Alignment.Center,
            ) {
                if (transparent) {
                    Text(
                        text = swatch.label.ifEmpty { stringResource(R.string.color_none) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 列表/网格空态：内容整体居中（外层 Box 一个对齐点，内层不再重复 align）。
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    fillSize: Boolean = true,
) {
    Box(
        modifier = if (fillSize) {
            modifier.fillMaxSize().padding(Spacing.xl)
        } else {
            modifier.padding(Spacing.xl)
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 状态行：图标 + 文案的横排三态/四态行（引导页检测状态等场景） */
@Composable
fun StatusRow(
    icon: ImageVector,
    iconTint: Color,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = iconTint)
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 分组标题：仅文本，不带副作用;段落间距由调用方用布局令牌控制 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier,
    )
}

/** 开关/勾选 + 双行说明的标准行：表单里的"抽屉可见性""使用导入密钥库"等场景共用 */
@Composable
fun ToggleInfoRow(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(modifier = Modifier.width(Spacing.sm))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
