package com.muxiao.iconpacker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Layout
import com.muxiao.iconpacker.ui.theme.LocalAppPalette
import com.muxiao.iconpacker.ui.theme.Spacing

/* ============================================================================
 * 统一组件层
 *
 * 设计约束（所有页面共用，不在页面里各自发挥）：
 * 1. 容器只有两级：页面底色 + 内容卡片；卡片内不再套卡片。
 * 2. 居中一律靠容器对齐点：Box/Row 的 contentAlignment / verticalAlignment 负责，
 *    不用手写 Spacer 推位置（推动的居中在字体缩放时会跑偏）。
 * 3. 图形尺寸统一走 [IconSize]，且"视觉尺寸"与"容器尺寸"分离：
 *    缩略图定容器、图片填充容器，避免大图撑破行高。
 * 4. 可点区域不小于 [Layout.touchTarget]（48dp）。
 * ========================================================================== */

/**
 * 全应用统一的 Scaffold 骨架：标题（可带副标题）+ 可选返回键 + 右侧操作 + Snackbar + FAB + 底栏。
 *
 * 顶栏与页面同色、无阴影：滚动时靠内容自身的分隔与卡片区分层级，
 * 避免顶栏出现一道突兀的色带。底栏统一做 navigationBarsPadding——
 * 应用已开启 edge-to-edge，底栏不处理 inset 会被手势条压住。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val palette = LocalAppPalette.current
    Scaffold(
        modifier = modifier,
        containerColor = palette.page,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, maxLines = 1, style = MaterialTheme.typography.titleLarge)
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.page,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        floatingActionButton = { floatingActionButton?.invoke() },
        bottomBar = {
            if (bottomBar != null) {
                Surface(color = palette.card, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Column(modifier = Modifier.navigationBarsPadding()) {
                        HorizontalDivider(thickness = Layout.hairline, color = palette.divider)
                        bottomBar()
                    }
                }
            }
        },
        content = content,
    )
}

/**
 * 对话框内容的标准滚动容器：内容超出可用高度时自动可滚，
 * 上限取屏高的 75%——替代散落的 360/420/460dp 固定值，
 * 小屏与横屏下不再溢出，大屏下也不再浪费空间。
 */
@Composable
// suppression：Dialog 组合中的 LocalWindowInfo.containerSize 指对话框窗口容器而非屏幕，
// 屏高比例的语义只能取活动 Configuration 的 screenHeightDp
@Suppress("ConfigurationScreenWidthHeight")
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

/**
 * 内容卡片：可选标题 / 说明 / 尾部操作 + 内容槽位。
 *
 * 这是全应用唯一的内容容器。卡片之间靠 12dp 间隔分层，
 * 卡片内部靠 12dp 节奏分组——不再用"卡片套卡片"表达分组。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Layout.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = palette.card,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(Layout.hairline, palette.divider),
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = title, style = MaterialTheme.typography.titleSmall)
                            if (description != null) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (trailing != null) {
                        Box(contentAlignment = Alignment.CenterEnd) { trailing() }
                    }
                }
                Spacer(modifier = Modifier.size(Spacing.md))
            }
            content()
        }
    }
}

/** 分隔线：卡片内的行分隔，左侧缩进避开图标，视觉上与文字列对齐 */
@Composable
fun AppDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    val palette = LocalAppPalette.current
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent),
        thickness = Layout.hairline,
        color = palette.divider,
    )
}

/** 状态语义档位：彩色只给徽标，正文一律中性色 */
enum class Tone { Neutral, Primary, Success, Warning, Error }

/**
 * 状态徽标：胶囊 + 小字 + 可选图标。
 * 把"有未提交修改 / 已是最新 / 未映射"这类状态从彩色文字改成语义色块，
 * 文字色只出现在有意义的少数位置。
 */
@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    icon: ImageVector? = null,
) {
    val palette = LocalAppPalette.current
    val (container, content) = when (tone) {
        // Neutral 要有可辨认的底：页面底色与 cardMuted 太接近，徽标会退化成漂浮的文字
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Primary ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Tone.Success -> palette.successContainer to palette.onSuccessContainer
        Tone.Warning -> palette.warningContainer to palette.onWarningContainer
        Tone.Error ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
    }
}

/** 通知卡的语义级别；颜色槽位统一从 [LocalAppPalette] / colorScheme 派生 */
enum class NoticeKind { Info, Warning, Error, Success }

/**
 * 通知卡：图标 + 标题 + 正文 + 可选动作。
 *
 * 与 [StatusBadge] 的分工：徽标是"状态"，通知卡是"需要阅读并可能采取动作的提示"。
 * 前者一行内解决，后者才值得占一张卡。
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
    val palette = LocalAppPalette.current
    val (container, content, icon) = when (kind) {
        NoticeKind.Error -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Warning,
        )

        NoticeKind.Warning -> Triple(palette.warningContainer, palette.onWarningContainer, Icons.Filled.Warning)
        NoticeKind.Success -> Triple(palette.successContainer, palette.onSuccessContainer, Icons.Filled.CheckCircle)
        NoticeKind.Info -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Filled.Info,
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 图标与首行标题对齐（padding 补足 titleSmall 的行高差），不整体居中——
        // 多行正文时整体居中会让图标悬在半空
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.padding(top = 1.dp).size(IconSize.inline),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = content)
            Spacer(modifier = Modifier.size(Spacing.xs))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.85f),
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.size(Spacing.xs))
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = content),
                ) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** 指标项：数值在上、标签在下，用于把"一句话里的三个数字"拆成可扫读的块 */
data class MetricItem(val label: String, val value: String)

/**
 * 指标条：等分列 + 细竖线分隔。
 * 列内上下居中、数值与标签左对齐同一条基线，避免长短数值造成的重心漂移。
 */
@Composable
fun MetricStrip(items: List<MetricItem>, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.md)
                        .width(Layout.hairline)
                        .heightIn(min = 28.dp)
                        .background(palette.divider),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 列表/网格空态：图标 + 标题 + 说明 + 主操作，整体居中。
 *
 * 旧版只有一行文字，用户看到"还没有图标包"却不知道下一步点哪；
 * 这里把下一步动作直接放进空态。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    fillSize: Boolean = true,
) {
    val palette = LocalAppPalette.current
    Box(
        modifier = if (fillSize) {
            modifier.fillMaxSize().padding(Spacing.xl)
        } else {
            modifier.padding(Spacing.xl)
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(palette.cardMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(modifier = Modifier.size(Spacing.xs))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.size(Spacing.xs))
                androidx.compose.material3.Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/** 通用列表行：可选前置图形 + 两行文字 + 尾部内容 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Layout.rowMinHeight)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(contentAlignment = Alignment.Center) { leading() }
            Spacer(modifier = Modifier.width(Spacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(Spacing.sm))
            Box(contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

/**
 * 选中态芯片：统一走品牌容器色。
 * M3 FilterChip 默认选中色取 secondaryContainer，会让选中态呈现青绿，
 * 与品牌蓝形成两套强调色；此处统一收口。
 */
@Composable
fun AppChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = if (selected) {
            null
        } else {
            BorderStroke(Layout.hairline, palette.divider)
        },
        modifier = modifier,
    )
}

/**
 * 缩略图容器：固定容器尺寸 + 内容居中 + 圆角裁切。
 *
 * 所有"图标 / 图片"都必须经过这里——图片尺寸不再由内容决定，
 * 行高与网格因此稳定，也不会出现"某张图特别大"的重心偏移。
 */
@Composable
fun ThumbnailBox(
    size: Dp,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(palette.thumbnail),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** 一个可选色块：36dp 圆 + 选中描边；透明项在圆心标"无" */
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
 *
 * 视觉直径 36dp、触控容器 48dp——色块本身小，但可点区域不小于无障碍下限；
 * 每行 8dp 间距，按 FlowRow 自动换行，窄屏不挤压。
 */
@Composable
fun ColorSwatchRow(
    entries: List<ColorSwatch>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val palette = LocalAppPalette.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        entries.forEach { swatch ->
            val isSelected = selected == swatch.color
            val transparent = swatch.color == android.graphics.Color.TRANSPARENT
            val label = swatch.label.ifEmpty { stringResource(R.string.color_none) }
            Box(
                modifier = Modifier
                    .size(Layout.touchTarget)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onSelect(swatch.color) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(IconSize.swatch)
                        .clip(CircleShape)
                        .background(if (transparent) palette.card else Color(swatch.color))
                        .border(
                            width = if (isSelected) 2.dp else Layout.hairline,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                palette.divider
                            },
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (transparent) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 分组标题：卡片内的小标题，仅文本；段落间距由调用方用布局令牌控制 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * 表单分区（不套卡片）：靠「分隔线 + 标题 + 间距差」建立层级。
 *
 * 为什么不用卡片：一份表单放四张卡就是"一叠小盒子"，卡内卡外都是留白，
 * 滚动时既占高度又看不出哪些字段属于同一件事。这里把归类交给三件事：
 * 1. 分区之间 24dp、组内字段 12dp —— 只靠间距差就能读出边界；
 * 2. 分区之间一条发丝线，线的上方留白远大于下方，视觉上"线属于下一段"；
 * 3. 分区标题 14sp 中等字重，与浮在输入框上的 12sp 字段标签不同级；
 *    可选分区在标题右侧写"可选"，而不是把"（可选）"塞进每个字段名。
 *
 * @param hint 标题右侧的补充说明（如"可选"、"留空沿用模板"）
 * @param showDivider 首个分区通常传 false——页面顶部已有顶栏划出边界
 */
@Composable
fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) {
            HorizontalDivider(
                thickness = Layout.hairline,
                color = palette.divider,
                modifier = Modifier.padding(bottom = Spacing.md),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hint != null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content,
        )
    }
}

/** 开关/勾选 + 双行说明的标准行：表单里的"抽屉可见性""使用导入密钥库"等场景共用 */
@Composable
fun ToggleInfoRow(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
) {
    Row(
        // 必须撑满：不 fillMaxWidth 时 Row 按内容收缩，说明文字会在半途换行，
        // 右侧留出一块与其它字段不对齐的空白
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.rowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) { leading() }
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
