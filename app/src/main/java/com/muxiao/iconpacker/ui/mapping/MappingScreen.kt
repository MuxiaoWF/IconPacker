package com.muxiao.iconpacker.ui.mapping

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.EmptyState
import com.muxiao.iconpacker.ui.components.ScrollableDialogContent
import com.muxiao.iconpacker.ui.components.asString
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Spacing
import com.muxiao.iconpacker.ui.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 待指派图标的目标；[from] 非空表示这是"改包名"：那条映射将从 [from] 改挂到 [component] */
private data class AssignTarget(val component: String, val from: String?)

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
private fun dialogListHeight(): Dp =
    (LocalConfiguration.current.screenHeightDp * 0.5f).dp

/**
 * 映射工作台（FR-04）+ 图标导入（FR-03）+ 图标库反查。
 *
 * 双 Tab：应用（搜索 + 映射）／图标库（网格 + "供哪个 App 使用"反查）。
 * 顶部导入通道与映射进度两个 Tab 共用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingScreen(
    packId: Long,
    onBuild: () -> Unit,
    onBack: () -> Unit,
    onEditIcon: (Int) -> Unit,
    viewModel: MappingViewModel = viewModel(factory = MappingViewModel.factory(packId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
            snackbarHostState.showSnackbar(it.map { part -> part.asString(context) }.joinToString(msgSeparator))
            viewModel.consumeMessage()
        }
    }

    AppScaffold(
        title = state.packName.ifBlank { stringResource(R.string.mapping_title_fallback) },
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::autoMatch) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_auto_match))
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 导入通道 + 进度（两个 Tab 共用）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    label = { Text(stringResource(R.string.mapping_import_album)) },
                )
                AssistChip(
                    onClick = { zipPicker.launch(arrayOf("application/zip")) },
                    label = { Text(stringResource(R.string.mapping_import_zip)) },
                )
                Spacer(modifier = Modifier.weight(1f))
                if (state.importing) {
                    CircularProgressIndicator(modifier = Modifier.size(IconSize.progressSmall))
                }
            }

            // 映射进度与打包入口
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.mapping_progress,
                        state.mappedCount,
                        state.apps.size,
                        state.icons.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onBuild, enabled = state.mappedCount > 0) {
                    Text(stringResource(R.string.common_build))
                }
            }

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
                            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            label = { Text(stringResource(R.string.mapping_search_apps)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        // 映射的目标不必来自列表：图标包里可能有本机没装的应用，
                        // 也可以手动把一个包名指到自己想要的图标上
                        TextButton(onClick = { targetDraft = TargetDraft("", null) }) {
                            Text(stringResource(R.string.mapping_add_target))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        FilterChip(
                            selected = state.onlyUnmapped,
                            onClick = { viewModel.setOnlyUnmapped(!state.onlyUnmapped) },
                            label = { Text(stringResource(R.string.mapping_only_unmapped)) },
                        )
                        if (state.onlyUnmapped && state.visibleApps.isEmpty()) {
                            Text(
                                text = stringResource(R.string.mapping_all_mapped_done),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                    }
                    if (state.loading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            items(state.visibleApps, key = { it.component }) { app ->
                                AppRowItem(
                                    app = app,
                                    icons = state.icons,
                                    onClick = { pickingApp = AssignTarget(app.component, null) },
                                    onEditTarget = { targetDraft = TargetDraft(app.packageName, app) },
                                )
                            }
                        }
                    }
                }

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.mapping_icon_count, state.icons.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { showShapeBatch = true },
                            enabled = state.icons.isNotEmpty() && !state.importing,
                        ) { Text(stringResource(R.string.mapping_unify_shape)) }
                    }
                    if (state.icons.isEmpty() && !state.importing) {
                        // 空状态引导：新项目最容易在"导入"这一步停顿——把两条导入通道
                        // 的入口明示在库面板里（顶部 chips 本就有，这里只是再指一次路）
                        EmptyState(
                            message = stringResource(R.string.mapping_icons_empty),
                        )
                    } else {
                        IconLibraryTab(
                            icons = state.icons,
                            iconTargets = state.iconTargets,
                            onIconClick = { detailIcon = it },
                        )
                    }
                }
            }
        }
    }

    pickingApp?.let { target ->
        IconPickerDialog(
            icons = state.icons,
            targetPackage = MappingTarget.packageOf(target.component),
            onSelect = { slot ->
                viewModel.assign(target.component, slot, target.from)
                pickingApp = null
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

    Card(onClick = onClick) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconThumb(packageName = app.packageName)
            Spacer(modifier = Modifier.size(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                // 包名可点即改：已映射的行才提供入口——没有映射时"改包名"无从谈起，
                // 那种情况该走上面的「添加映射」
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (app.assignedSlot != null) {
                        Modifier.clickable(onClick = onEditTarget)
                            .padding(vertical = Spacing.xs, horizontal = Spacing.xs)
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // 已指派图标预览；未映射时以文字占位，保持行高稳定
            Box(
                modifier = Modifier
                    .size(IconSize.row)
                    .clip(MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                if (assignedIcon != null) {
                    IconImage(
                        path = assignedIcon.editedPath ?: assignedIcon.sourcePath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.mapping_app_unmapped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    Box(
        modifier = Modifier
            .size(IconSize.row)
            .clip(MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
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
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    columns = GridCells.Adaptive(88.dp),
                    modifier = Modifier.height(dialogListHeight()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(icons.size, key = { index -> icons[index].id }) { index ->
                        val icon = icons[index]
                        // 单元格整体居中：条目不再贴左，网格观感均匀
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { onSelect(icon.slot) },
                            contentAlignment = Alignment.Center,
                        ) {
                            IconImage(
                                path = icon.editedPath ?: icon.sourcePath,
                                contentDescription = icon.originalName,
                                modifier = Modifier
                                    .padding(Spacing.xs)
                                    .size(64.dp),
                            )
                        }
                    }
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
