package com.muxiao.iconpacker.ui.mapping

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.data.AppInfoProvider
import com.muxiao.iconpacker.data.IconAssetEntity
import com.muxiao.iconpacker.domain.ImportBinder
import com.muxiao.iconpacker.domain.MappingTarget
import com.muxiao.iconpacker.ui.IconImage
import com.muxiao.iconpacker.ui.components.AppDivider
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.EmptyState
import com.muxiao.iconpacker.ui.components.MetricItem
import com.muxiao.iconpacker.ui.components.MetricStrip
import com.muxiao.iconpacker.ui.components.ScrollableDialogContent
import com.muxiao.iconpacker.ui.components.SectionCard
import com.muxiao.iconpacker.ui.components.StatusBadge
import com.muxiao.iconpacker.ui.components.ThumbnailBox
import com.muxiao.iconpacker.ui.components.Tone
import com.muxiao.iconpacker.ui.components.asString
import com.muxiao.iconpacker.ui.icons.AppIcons
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Layout
import com.muxiao.iconpacker.ui.theme.LocalAppPalette
import com.muxiao.iconpacker.ui.theme.Motion
import com.muxiao.iconpacker.ui.theme.Spacing
import com.muxiao.iconpacker.ui.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 待指派图标的目标；[from] 非空表示这是"改包名"：那条映射将从 [from] 改挂到 [component] */
private data class AssignTarget(val component: String, val from: String?)

/**
 * 惯性阶段头卡的收缩倍率。
 *
 * 拖拽时头卡与手指 1:1 收；松手后的惯性按 1.5 倍收，让它在列表继续滚之前先走完——
 * 否则快甩一次会停在"半张卡挂在列表上方"的中间态，看起来像布局坏了。
 */
private const val FLING_COLLAPSE_BOOST = 1.5f

/**
 * 包名编辑对话框的初值；[from] 非空表示编辑既有映射。
 *
 * [iconSlot] 非空表示这次编辑**从图标库发起**（图标详情里点某条目标 / 添加映射）：
 * 槽位此刻已知，确认后直接落到这张图标上，不再走"选图标"那一步。
 */
private data class TargetDraft(
    val initial: String,
    val from: AppRow?,
    val iconSlot: Int? = null,
)

/** 对话框内列表的自适应高度：屏高的 50%（替代固定 320dp，大小屏都合适） */
@Composable
// suppression：同 ScrollableDialogContent——Dialog 内 containerSize 语义不适用
@Suppress("ConfigurationScreenWidthHeight")
private fun dialogListHeight(): Dp =
    (LocalConfiguration.current.screenHeightDp * 0.5f).dp

/**
 * 映射工作台（FR-04）+ 图标导入（FR-03）+ 图标库反查。
 *
 * 双 Tab：应用（搜索 + 映射）／图标库（网格 + "供哪个 App 使用"反查）。
 * 顶部导入通道与映射进度两个 Tab 共用。
 */
@Composable
fun MappingScreen(
    packId: Long,
    onBuild: () -> Unit,
    onBack: () -> Unit,
    onEditIcon: (Int) -> Unit,
    viewModel: MappingViewModel = viewModel(factory = MappingViewModel.factory(packId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val brokenCount by viewModel.brokenCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pickingApp by remember { mutableStateOf<AssignTarget?>(null) }
    var targetDraft by remember { mutableStateOf<TargetDraft?>(null) }
    var detailIcon by remember { mutableStateOf<IconAssetEntity?>(null) }
    var showShapeBatch by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    // 照片选择器（免存储权限）
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(48),
    ) { uris: List<Uri> ->
        viewModel.importFromUris(uris.map { it to queryDisplayName(context, it) })
    }
    // ZIP 选择器
    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { viewModel.importFromZip(it) }
    }

    val msgSeparator = stringResource(R.string.mapping_msg_separator)
    LaunchedEffect(state.message) {
        state.message?.takeIf { it.isNotEmpty() }?.let {
            snackbarHostState.showSnackbar(it.joinToString(msgSeparator) { part ->
                part.asString(
                    context
                )
            })
            viewModel.consumeMessage()
        }
    }

    AppScaffold(
        title = state.packName.ifBlank { stringResource(R.string.mapping_title_fallback) },
        // 筛选改到顶栏后只剩一个图标，光靠着色不足以说明"现在是否在筛"，
        // 用副标题把状态和筛选后的数量明说出来（顶栏高度固定，开关切换不会引起跳动）
        subtitle = if (state.onlyUnmapped) {
            if (selectedTab == 0) {
                stringResource(R.string.mapping_subtitle_unmapped_apps, state.visibleApps.size)
            } else {
                stringResource(R.string.mapping_subtitle_unused_icons, state.visibleIcons.size)
            }
        } else {
            null
        },
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        actions = {
            // 筛选与刷新并列：两者都作用于"当前列表的读法"，放在一起；
            // 打包是产物级操作，独立在最右，不与它们混排
            IconToggleButton(
                checked = state.onlyUnmapped,
                onCheckedChange = { viewModel.setOnlyUnmapped(it) },
            ) {
                Box(
                    modifier = Modifier
                        .size(IconSize.actionChip)
                        .clip(CircleShape)
                        .background(
                            if (state.onlyUnmapped) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                Color.Transparent
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.FilterFunnel,
                        // 两栏的"未映射"不是一回事：应用是没指派图标，图标是没被引用，
                        // 无障碍朗读按当前栏给词，与副标题一致
                        contentDescription = stringResource(
                            if (selectedTab == 0) {
                                R.string.mapping_only_unmapped
                            } else {
                                R.string.mapping_only_unused
                            },
                        ),
                        modifier = Modifier.size(IconSize.actionGlyph),
                        tint = if (state.onlyUnmapped) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(onClick = viewModel::autoMatch) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_auto_match))
            }
            // 主操作固定在顶栏：旧版夹在"进度文字"右侧，随页面滚动离开视口，
            // 用户在长列表里要一路滑回顶部才能打包
            Button(
                onClick = onBuild,
                enabled = state.mappedCount > 0,
                contentPadding = PaddingValues(horizontal = Spacing.lg),
                modifier = Modifier.padding(end = Spacing.md),
            ) {
                Text(stringResource(R.string.common_build))
            }
        },
    ) { innerPadding ->
        // 头卡随列表收起。不能在滚动容器外面直接改高度：每帧改布局却不接管位移，
        // 手势会和布局互相拉扯（内容被推两次）。这里用嵌套滚动把位移真正吃下来——
        // 上滑先把头卡收掉，列表回到顶部后，多余的位移才用来把头卡放回来。
        var collapsedPx by remember { mutableFloatStateOf(0f) }
        var headerPx by remember { mutableIntStateOf(0) }
        val collapseConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f) return Offset.Zero
                    val remaining = headerPx - collapsedPx
                    if (remaining <= 0f) return Offset.Zero
                    val step = -available.y
                    // 松手后的惯性（SideEffect）按 1.5 倍折算：头卡比列表先走完，
                    // 否则一次快甩会留下"半张卡挂在列表上方"的尴尬中间态。
                    val weight = if (source == NestedScrollSource.SideEffect) FLING_COLLAPSE_BOOST else 1f
                    val before = collapsedPx
                    collapsedPx = (collapsedPx + step * weight).coerceAtMost(headerPx.toFloat())
                    // 只声明与实际收卡量对应的位移：不能吃超过 available，多出来要还给列表
                    return Offset(0f, -((collapsedPx - before) / weight).coerceAtMost(step))
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // 只有手指下拽才把头卡放回来：惯性滚到顶时自动弹开会很突兀
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    // 子列表一点都没吃掉（已经在顶部），剩下的位移才归头卡
                    if (available.y <= 0f || consumed.y != 0f || collapsedPx <= 0f) return Offset.Zero
                    val delta = available.y.coerceAtMost(collapsedPx)
                    collapsedPx -= delta
                    return Offset(0f, delta)
                }
            }
        }
        // 换栏等于换了一份列表（新列表在顶部），头卡跟着回到展开态
        LaunchedEffect(selectedTab) { collapsedPx = 0f }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(collapseConnection),
        ) {
            CollapsingHeader(collapsedPx = collapsedPx) {
                MappingHeaderCard(
                    appsCount = state.apps.size,
                    mappedCount = state.mappedCount,
                    iconsCount = state.icons.size,
                    brokenCount = brokenCount,
                    importing = state.importing,
                    onImportAlbum = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onImportZip = { zipPicker.launch(arrayOf("application/zip")) },
                    // 完整高度在布局阶段回填，收起量按它计算
                    onHeightMeasured = { if (it != headerPx) headerPx = it },
                )
            }

            // 筛选已收进顶栏，不再独占横带；状态与筛选后的数量由副标题承担
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.mapping_tab_apps, state.apps.size)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.mapping_tab_icons, state.icons.size)) },
                )
            }

            when (selectedTab) {
                0 -> Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Layout.pageHorizontal, end = Spacing.md, top = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = stringResource(R.string.mapping_search_apps),
                            modifier = Modifier.weight(1f),
                        )
                        // 映射的目标不必来自列表：图标包里可能有本机没装的应用，
                        // 也可以手动把一个包名指到自己想要的图标上
                        IconButton(onClick = { targetDraft = TargetDraft("", null) }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.mapping_add_target),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    when {
                        state.loading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }

                        // 筛选后没有结果时，空态要区分"真的没有"和"被筛掉了"，
                        // 否则用户看到"没有匹配的应用"会以为列表坏了
                        state.visibleApps.isEmpty() -> EmptyState(
                            title = if (state.onlyUnmapped && state.apps.isNotEmpty()) {
                                stringResource(R.string.mapping_apps_all_mapped_title)
                            } else {
                                stringResource(R.string.mapping_apps_empty)
                            },
                            body = if (state.onlyUnmapped && state.apps.isNotEmpty()) {
                                stringResource(R.string.mapping_all_mapped_done)
                            } else {
                                null
                            },
                            icon = Icons.Default.Search,
                        )

                        // 应用列表改为"裸行 + 分隔线"：几十行各套一张灰卡会让整屏失去呼吸，
                        // 这里的信息结构是单维的（应用 → 图标），行比卡片更合适
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = Layout.pageHorizontal,
                                end = Layout.pageHorizontal,
                                bottom = Spacing.xl,
                            ),
                        ) {
                            itemsIndexed(state.visibleApps, key = { _, app -> app.component }) { index, app ->
                                AppRowItem(
                                    app = app,
                                    icons = state.icons,
                                    onClick = { pickingApp = AssignTarget(app.component, null) },
                                    onEditTarget = { targetDraft = TargetDraft(app.packageName, app) },
                                )
                                if (index != state.visibleApps.lastIndex) {
                                    AppDivider(startIndent = IconSize.appRow + Spacing.md)
                                }
                            }
                        }
                    }
                }

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Layout.pageHorizontal, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // 这里只报总数；筛选后的数量由顶栏副标题给（「只看未使用 · N 个图标」），
                            // 两个数分处两地但含义不同，不在同一行重复一遍
                            text = stringResource(R.string.mapping_icon_count, state.icons.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { showShapeBatch = true },
                            // 统一外形作用于整库，不受筛选影响，故按总数判断可用性
                            enabled = state.icons.isNotEmpty() && !state.importing,
                        ) { Text(stringResource(R.string.mapping_unify_shape)) }
                    }
                    when {
                        state.icons.isEmpty() && !state.importing -> {
                            // 空状态引导：新项目最容易在"导入"这一步停顿——把两条导入通道
                            // 的入口明示在库面板里（顶部卡片本就有，这里只是再指一次路）
                            EmptyState(
                                title = stringResource(R.string.mapping_icons_empty_title),
                                body = stringResource(R.string.mapping_icons_empty),
                                icon = Icons.Default.Add,
                            )
                        }

                        state.visibleIcons.isEmpty() -> EmptyState(
                            title = stringResource(R.string.mapping_icons_all_used_title),
                            body = stringResource(R.string.mapping_icons_all_used_body),
                            icon = Icons.Default.Check,
                        )

                        else -> IconLibraryTab(
                            icons = state.visibleIcons,
                            iconTargets = state.iconTargets,
                            onIconClick = { detailIcon = it },
                        )
                    }
                }
            }
        }
    }

    pickingApp?.let { target ->
        val assignedSlot = state.apps.firstOrNull { it.component == target.component }?.assignedSlot
        IconPickerDialog(
            icons = state.icons,
            targetPackage = MappingTarget.packageOf(target.component),
            assignedSlot = assignedSlot,
            onSelect = { slot ->
                viewModel.assign(target.component, slot, target.from)
                pickingApp = null
            },
            // 指派错了要能退回"未映射"，否则只能再挑一张把错的盖掉
            onClear = if (assignedSlot != null) {
                {
                    viewModel.unassign(target.component)
                    pickingApp = null
                }
            } else {
                null
            },
            onDismiss = { pickingApp = null },
        )
    }

    // 包名编辑：可直接输入，也可从本机应用列表里选（见 [MappingTargetDialog]）
    targetDraft?.let { draft ->
        MappingTargetDialog(
            draft = draft,
            apps = state.apps,
            onConfirm = { component ->
                val slot = draft.iconSlot
                if (slot != null) {
                    // 从图标库发起：槽位已知，一步到位（新增 = setMapping，改写 = remapComponent 保槽位）
                    viewModel.assign(component, slot, draft.from?.component)
                } else {
                    pickingApp = AssignTarget(component, draft.from?.component)
                }
                targetDraft = null
            },
            onDismiss = { targetDraft = null },
        )
    }

    detailIcon?.let { icon ->
        IconDetailDialog(
            icon = icon,
            targets = state.iconTargets[icon.slot].orEmpty(),
            onAddTarget = { targetDraft = TargetDraft("", null, icon.slot) },
            onEditTarget = { target ->
                // 已安装：回填包名最省事（解析时一律换成设备真实启动组件）；
                // 未安装：必须带上活动类才可能解析成功，回填现有的组件写法
                targetDraft = TargetDraft(
                    initial = if (target.installed) {
                        target.packageName
                    } else {
                        MappingTarget.editableOf(target.component)
                    },
                    from = target,
                    iconSlot = icon.slot,
                )
            },
            onRemoveTarget = { target -> viewModel.unassign(target.component) },
            onEdit = {
                onEditIcon(icon.slot)
                detailIcon = null
            },
            onDelete = {
                viewModel.deleteIcon(icon.slot)
                detailIcon = null
            },
            onDismiss = { detailIcon = null },
        )
    }

    if (showShapeBatch) {
        ShapeBatchDialog(
            onApply = { shape, color, overwriteEdited ->
                viewModel.batchApplyShape(shape, color, overwriteEdited)
                showShapeBatch = false
            },
            onDismiss = { showShapeBatch = false },
        )
    }
}

@Composable
private fun AppRowItem(
    app: AppRow,
    icons: List<IconAssetEntity>,
    onClick: () -> Unit,
    onEditTarget: () -> Unit,
) {
    val assignedIcon = icons.firstOrNull { it.slot == app.assignedSlot }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconThumb(packageName = app.packageName)
        Spacer(modifier = Modifier.size(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 包名可点即改：整行 36dp 高的热区，比一个 14dp 的铅笔图标可靠得多；
            // 只有已映射的行才提供入口——没有映射时"改包名"无从谈起
            Row(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .then(
                        if (app.assignedSlot != null) {
                            Modifier.clickable(onClick = onEditTarget)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.assignedSlot != null) {
                    Spacer(modifier = Modifier.size(Spacing.xs))
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.cd_edit_target),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(Spacing.sm))
        // 右侧固定槽位：有图标放图标，无图标放状态徽标。两者都居中对齐，
        // 于是整列的右边界是一条直线，滚动时不会左右跳
        if (assignedIcon != null) {
            ThumbnailBox(size = IconSize.appRow, shape = MaterialTheme.shapes.small) {
                IconImage(
                    path = assignedIcon.editedPath ?: assignedIcon.sourcePath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            StatusBadge(
                text = stringResource(R.string.mapping_app_unmapped),
                tone = Tone.Neutral,
            )
        }
    }
}

/** 应用图标缩略图（列表与选择器共用）；解码放到 IO 线程，列表滚动不卡主线程 */
@Composable
private fun AppIconThumb(packageName: String) {
    val context = LocalContext.current
    val appInfoProvider = remember(context) { AppInfoProvider(context) }
    var appIcon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        appIcon = withContext(Dispatchers.IO) {
            runCatching { appInfoProvider.loadAppIcon(packageName)?.toImageBitmap() }.getOrNull()
        }
    }
    ThumbnailBox(size = IconSize.appRow, shape = MaterialTheme.shapes.small) {
        val bitmap: ImageBitmap? = appIcon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * 圆形进度环：把"已映射 3 / 36"从一句文字变成一个可一眼估量的图形。
 *
 * 环内只放百分比（两位数字），标签由上方卡片标题承担——
 * 44dp 的环里塞"3/36"会让字号缩到 9sp 以下。
 */
@Composable
private fun ProgressRing(progress: Float, modifier: Modifier = Modifier) {
    val target = progress.coerceIn(0f, 1f)
    val palette = LocalAppPalette.current
    val primary = MaterialTheme.colorScheme.primary
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(Motion.NORMAL),
        label = "mapping-progress",
    )
    Box(modifier = modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = palette.divider,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = primary,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "${(target * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 可收起的头部容器。
 *
 * 子项按自身完整高度测量（不受"当前可见高度"影响），再只把可见高度报给父布局，
 * 内容以底边对齐向上移出——观感是整块被顶栏吃掉，而不是从下往上被裁掉半张卡。
 */
@Composable
private fun CollapsingHeader(
    collapsedPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val visible = (placeable.height - collapsedPx).roundToInt().coerceIn(0, placeable.height)
        layout(placeable.width, visible) {
            placeable.place(0, visible - placeable.height)
        }
    }
}

/**
 * 进度头卡：环形进度与三个数字同排，导入入口在下一行。
 *
 * 旧版是"标题 + 环 / 指标条 / 导入"三行、166dp，占掉屏幕近 1/5，
 * 而它承载的信息只有三个数字和两个入口。合成两行后约 112dp；
 * 往上一滑还能整块收起（见 [CollapsingHeader]），列表因此能拿到全部高度。
 */
@Composable
private fun MappingHeaderCard(
    appsCount: Int,
    mappedCount: Int,
    iconsCount: Int,
    brokenCount: Int,
    importing: Boolean,
    onImportAlbum: () -> Unit,
    onImportZip: () -> Unit,
    onHeightMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        // 先量后加边距：收起时把卡片上下的 8dp 一起带走，不留一道白缝
        modifier = modifier
            .onSizeChanged { onHeightMeasured(it.height) }
            .padding(horizontal = Layout.pageHorizontal, vertical = Spacing.sm),
        contentPadding = PaddingValues(horizontal = Layout.cardPadding, vertical = Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = if (appsCount == 0) 0f else mappedCount.toFloat() / appsCount,
            )
            Spacer(modifier = Modifier.size(Spacing.md))
            MetricStrip(
                items = listOf(
                    MetricItem(stringResource(R.string.mapping_metric_apps), appsCount.toString()),
                    MetricItem(stringResource(R.string.mapping_metric_mapped), mappedCount.toString()),
                    MetricItem(stringResource(R.string.mapping_metric_icons), iconsCount.toString()),
                ),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.size(Spacing.md))
        // 残缺预警提前到映射页：这些映射在打包页也会被告警，但那时用户已经
        // 离开了"能立刻改"的上下文；在这里看见就能顺手处理
        if (brokenCount > 0) {
            Text(
                text = stringResource(R.string.mapping_broken_warn, brokenCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.size(Spacing.sm))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = onImportAlbum,
                label = { Text(stringResource(R.string.mapping_import_album)) },
            )
            AssistChip(
                onClick = onImportZip,
                label = { Text(stringResource(R.string.mapping_import_zip)) },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (importing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(IconSize.progressSmall),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * 轻量搜索框：44dp 高、胶囊底、无外框。
 *
 * 旧版用 OutlinedTextField：浮动标签让控件高 56dp 且视觉重量与"标题"相当，
 * 在工具型页面里它不该比内容更抢眼。
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            // 页面底色与 cardMuted 几乎同色，只填色等于没有边界——补一圈发丝描边
            .background(palette.card)
            .border(Layout.hairline, palette.divider, CircleShape)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.inline),
        )
        Spacer(modifier = Modifier.size(Spacing.sm))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.common_clear),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 编辑映射的目标包名（FR-04）。
 *
 * 两条通道并列，是因为它们覆盖的场景不同：
 * - **手输**：知道自己要哪个包名，不必在几百个应用里翻；
 * - **从应用列表选**：只记得图标长什么样，用列表更快，还能顺带看到包名。
 *
 * 无论哪条通道，下方都实时回显**最终采用的组件**——已安装的应用一律以设备上的
 * 真实启动组件为准（见 [MappingTarget]），不把这一步藏起来，用户才能确信
 * "我填的东西真的生效了"。
 */
@Composable
private fun MappingTargetDialog(
    draft: TargetDraft,
    apps: List<AppRow>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(draft.initial) }
    var pickingFromList by remember { mutableStateOf(false) }

    val installed = remember(apps) {
        apps.map { ImportBinder.AppItem(it.component, it.packageName) }
    }
    val resolution = remember(input, installed) { MappingTarget.resolve(input, installed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (draft.from == null) {
                    stringResource(R.string.mapping_add_target)
                } else {
                    stringResource(R.string.dialog_edit_target_title)
                },
            )
        },
        text = {
            ScrollableDialogContent {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.common_label_package_name)) },
                    placeholder = { Text("com.example.app") },
                    singleLine = true,
                    isError = resolution is MappingTarget.Resolution.Invalid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(Spacing.sm))
                TextButton(
                    onClick = { pickingFromList = true },
                    enabled = apps.isNotEmpty(),
                ) { Text(stringResource(R.string.mapping_pick_from_apps)) }

                when (resolution) {
                    is MappingTarget.Resolution.Invalid -> Text(
                        text = stringResource(resolution.messageRes, *resolution.args.toTypedArray()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is MappingTarget.Resolution.Resolved -> Column {
                        Text(
                            text = stringResource(R.string.mapping_resolve_target, resolution.component),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!resolution.installed) {
                            Text(
                                text = stringResource(R.string.mapping_target_not_installed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val from = draft.from
                        if (from != null && from.component != resolution.component) {
                            Text(
                                text = stringResource(
                                    R.string.mapping_retarget_warn,
                                    resolution.packageName,
                                    from.packageName,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val resolved = resolution as? MappingTarget.Resolution.Resolved
            TextButton(
                onClick = { resolved?.let { onConfirm(it.component) } },
                enabled = resolved != null,
            ) { Text(stringResource(R.string.mapping_next_step)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )

    if (pickingFromList) {
        InstalledAppPickerDialog(
            apps = apps,
            onSelect = { app ->
                // 回填包名而不是整串 ComponentInfo：输入框里读起来清爽，
                // 且解析结果与直接手输包名完全一致
                input = app.packageName
                pickingFromList = false
            },
            onDismiss = { pickingFromList = false },
        )
    }
}

/** 本机已安装应用选择器（可搜索） */
@Composable
private fun InstalledAppPickerDialog(
    apps: List<AppRow>,
    onSelect: (AppRow) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mapping_pick_app)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.common_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(Spacing.sm))
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mapping_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.md),
                    )
                } else {
                    // 高度跟随屏高的 50%：大屏不再固定 320dp 浪费，小屏不会顶爆对话框
                    LazyColumn(
                        modifier = Modifier.height(dialogListHeight()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        items(filtered, key = { it.component }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(app) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIconThumb(packageName = app.packageName)
                                Spacer(modifier = Modifier.size(Spacing.md))
                                Column {
                                    Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun IconPickerDialog(
    icons: List<IconAssetEntity>,
    targetPackage: String,
    assignedSlot: Int?,
    onSelect: (Int) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val palette = LocalAppPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.mapping_pick_icon_title, icons.size))
                // 显示目标，是为了让"改包名"的第二步不至于失去上下文
                Text(
                    text = targetPackage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            if (icons.isEmpty()) {
                Text(stringResource(R.string.mapping_icons_pool_empty))
            } else {
                LazyVerticalGrid(
                    // 固定 3 列：自适应列数会随对话框宽度与字号在 3/4 列之间抖动，
                    // 3 列时每格约 96dp、图标 72dp，一眼数得清、点击目标也够大
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.height(dialogListHeight()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(icons.size, key = { index -> icons[index].id }) { index ->
                        val icon = icons[index]
                        // 当前已映射的那一格加主色描边 + 角标：误选之后要先看清"现在挂的是哪张"
                        val current = assignedSlot != null && icon.slot == assignedSlot
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.medium)
                                .background(palette.cardMuted)
                                // 浅色下 cardMuted 与对话框面色几乎一致，补一圈发丝边才看得出格子边界
                                .border(
                                    if (current) 2.dp else Layout.hairline,
                                    if (current) MaterialTheme.colorScheme.primary else palette.divider,
                                    MaterialTheme.shapes.medium,
                                )
                                .clickable { onSelect(icon.slot) },
                            contentAlignment = Alignment.Center,
                        ) {
                            IconImage(
                                path = icon.editedPath ?: icon.sourcePath,
                                contentDescription = icon.originalName,
                                modifier = Modifier.fillMaxSize().padding(Spacing.md),
                            )
                            if (current) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(Spacing.xs)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(
                                            R.string.mapping_pick_icon_current,
                                        ),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        // 左右分置：清除映射是"退出这条映射"，与"取消"不同，给它独立的位置与错误色
        dismissButton = onClear?.let {
            {
                TextButton(onClick = it) {
                    Text(
                        text = stringResource(R.string.mapping_clear_assignment),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 从 Uri 读取显示名（导入记录用） */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "icon"
    }.getOrDefault("icon")
