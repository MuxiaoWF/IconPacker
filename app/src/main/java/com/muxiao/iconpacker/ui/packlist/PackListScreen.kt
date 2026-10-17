package com.muxiao.iconpacker.ui.packlist

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.IconFileStore
import com.muxiao.iconpacker.data.PackEntity
import com.muxiao.iconpacker.data.toSignConfig
import com.muxiao.iconpacker.domain.PackVersionRules
import com.muxiao.iconpacker.ui.IconImage
import com.muxiao.iconpacker.ui.sampleSizeFor
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.EmptyState
import com.muxiao.iconpacker.ui.components.ScrollableDialogContent
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Spacing

/**
 * 项目列表（FR-02）+ 产物管理（FR-08）。
 *
 * 每项展示：版本与打包状态（未打包 / 有未提交修改 / 已打包）、
 * 安装状态徽标（与设备上已安装的产物版本对比）、导出与删除操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackListScreen(
    onOpenPack: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PackListViewModel = viewModel(factory = PackListViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // 新建：表单整体提升到 Screen（对话框只负责呈现与提交），启动器图标与
    // 密钥库文件的 SAF 结果要跨 Activity 生命周期回填表单，必须放在对话框外
    var createForm by remember { mutableStateOf<PackForm?>(null) }
    var pendingDelete by remember { mutableStateOf<PackEntity?>(null) }
    var pendingExport by remember { mutableStateOf<PackEntity?>(null) }
    var pendingBackupExport by remember { mutableStateOf<PackEntity?>(null) }
    var pendingEdit by remember { mutableStateOf<PackEntity?>(null) }
    // 编辑：对话框打开时以当前项目为初值建一份表单副本；保存时统一按差异写库
    var pendingEditForm by remember { mutableStateOf<PackForm?>(null) }

    // 编辑对话框内选启动器图标：Photo Picker 免存储权限（FR-03 同款通道）
    val packIconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val form = pendingEditForm
        val pack = pendingEdit
        if (uri != null && form != null && pack != null) viewModel.setPackIcon(pack, uri)
    }

    // 新建对话框内选启动器图标（同款免权限通道）；Uri 仅临时持有，落库后由引擎编码
    val createIconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> createForm?.iconUri = uri }

    /**
     * 密钥库文件选择（新建/编辑统一走此通道）：选择 => 立即落盘到私有目录 =>
     * 路径回填表单。SAF Uri 只在本次授权内可读，不能把 Uri 存表单留到打包再读。
     */
    val createKeystorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val form = createForm ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            viewModel.importKeystoreFile(uri) { result ->
                result.fold(
                    onSuccess = { form.applyKeystoreImport(it) },
                    onFailure = { viewModel.postMessage("无法读取密钥库文件：${it.message}") },
                )
            }
        }
    }
    val editKeystorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val form = pendingEditForm ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            viewModel.importKeystoreFile(uri) { result ->
                result.fold(
                    onSuccess = { form.applyKeystoreImport(it) },
                    onFailure = { viewModel.postMessage("无法读取密钥库文件：${it.message}") },
                )
            }
        }
    }

    // 导出：SAF 建文档（无需存储权限），文件名建议带版本
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri ->
        val pack = pendingExport
        if (uri != null && pack != null) {
            viewModel.exportLatestArtifact(pack, uri)
        }
        pendingExport = null
    }

    // 导出项目备份 ZIP（v8）
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val pack = pendingBackupExport
        if (uri != null && pack != null) viewModel.exportProjectBackup(pack, uri)
        pendingBackupExport = null
    }

    // 导入项目备份 ZIP（v8）：完成即跳转新项目的映射工作台
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importProjectBackup(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 创建后直达映射工作台（新项目已落库，可直接开始导入图标；中断也无损，下次从列表继续）
    LaunchedEffect(state.createdPackId) {
        state.createdPackId?.let { packId ->
            viewModel.consumeCreated()
            onOpenPack(packId)
        }
    }

    AppScaffold(
        title = "我的图标包",
        snackbarHostState = snackbarHostState,
        actions = {
            TextButton(
                onClick = {
                    backupImportLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed"),
                    )
                },
                enabled = !state.importingBackup,
            ) { Text(if (state.importingBackup) "导入中" else "导入") }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { createForm = PackForm(defaults = settings) }) {
                Icon(Icons.Default.Add, contentDescription = "新建图标包")
            }
        },
    ) { innerPadding ->
        if (state.packs.isEmpty() && !state.loading) {
            EmptyState(
                message = "还没有图标包。\n点击右下角按钮创建第一个，" +
                    "然后导入图标、映射到应用、一键打包。",
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.packs, key = { it.id }) { pack ->
                    PackItem(
                        pack = pack,
                        status = state.statusOf(pack),
                        installLabel = state.installLabelOf(pack),
                        exporting = state.exportingPackId == pack.id,
                        backing = state.backupBusyPackId == pack.id,
                        onClick = { onOpenPack(pack.id) },
                        onEdit = {
                            pendingEdit = pack
                            pendingEditForm = PackForm(pack)
                        },
                        onDuplicate = { viewModel.duplicatePack(pack) },
                        onExport = { pendingExport = pack },
                        onExportBackup = { pendingBackupExport = pack },
                        onDelete = { pendingDelete = pack },
                    )
                }
            }
        }
    }

    // 新建（FR-02 全字段表单）：名称/版本/包名/抽屉可见性/图标/签名配置一次填齐
    createForm?.let { form ->
        CreatePackDialog(
            form = form,
            onPickIcon = { createIconPicker.launch("image/*") },
            onClearIcon = { form.iconUri = null },
            onPickKeystore = { createKeystorePicker.launch("*/*") },
            onClearKeystore = { form.clearKeystore() },
            onDismiss = { createForm = null },
            onCreate = {
                viewModel.createPack(form.toDraft())
                createForm = null
            },
        )
    }

    // 编辑项目：与新建共用同一份 PackFormFields——兩边字段集合永远一致
    if (pendingEdit != null && pendingEditForm != null) {
        val form = pendingEditForm!!
        val pack = pendingEdit!!
        EditPackDialog(
            pack = pack,
            form = form,
            installedVersionCode = state.installedVersions[pack.packageName],
            onPickIcon = { packIconPicker.launch("image/*") },
            onClearIcon = { viewModel.clearPackIcon(pack) },
            onPickKeystore = { editKeystorePicker.launch("*/*") },
            onDismiss = {
                pendingEdit = null
                pendingEditForm = null
            },
            onSave = {
                if (form.trimmedName != pack.name) viewModel.renamePack(pack, form.trimmedName)
                if (form.showInDrawer != pack.showInDrawer) {
                    viewModel.setShowInDrawer(pack, form.showInDrawer)
                }
                // 与旧版编辑对话框同一语义：改动判定以"库里存没存显式号"为基准
                val versionChanged = form.trimmedVersionName != pack.versionName ||
                    (form.versionCodeParsed as? PackVersionRules.Parsed.Explicit)?.code
                        ?.takeIf { it != pack.upcomingVersionCode } != pack.nextVersionCode
                if (versionChanged) {
                    viewModel.setVersionInfo(pack, form.trimmedVersionName, form.versionCodeInput)
                }
                if (form.trimmedPackageName != pack.packageName) {
                    viewModel.setPackageName(pack, form.trimmedPackageName)
                }
                if (form.toSignConfig() != pack.toSignConfig()) {
                    viewModel.setSigningConfig(pack, form.toSignConfig())
                }
                // SDK 重写 diff 写库（v8）：表单 null = 沿用模板，库里以 0 哨兵比较
                val formMin = form.minSdkParsed ?: 0
                val formTarget = form.targetSdkParsed ?: 0
                if (formMin != pack.minSdkOverride || formTarget != pack.targetSdkOverride) {
                    viewModel.setBuildSdk(pack, form.minSdkParsed, form.targetSdkParsed)
                }
                pendingEdit = null
                pendingEditForm = null
            },
        )
    }

    // 删除二次确认（FR-02 验收 2）
    pendingDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除图标包") },
            text = { Text("将删除「${pack.name}」及其全部映射、图标与打包产物，且不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePack(pack)
                        pendingDelete = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // 导出文件名建议由全局模板折算（设置页可自定义占位符；占位符缺失时字段直接拼接）
    pendingExport?.let { pack ->
        LaunchedEffect(pack.id) {
            exportLauncher.launch(
                IconFileStore.suggestedArtifactName(
                    template = settings.artifactTemplate,
                    packName = pack.name,
                    versionName = pack.versionName,
                    versionCode = pack.versionCode,
                ),
            )
        }
    }

    pendingBackupExport?.let { pack ->
        LaunchedEffect(pack.id) {
            backupExportLauncher.launch("${pack.name}-备份.zip")
        }
    }
}

/**
 * 项目卡片。编辑是"→ 编辑对话框"的单入口；复制/导出/删除是即时操作。
 *
 * 行内只保留一个"更多"入口，编辑/导出/复制/备份/删除全部收进溢出菜单：
 * 窄屏四连 IconButton 会把标题列挤到不可读；图标缩略图前置，扫一眼即可辨认项目。
 */
@Composable
private fun PackItem(
    pack: PackEntity,
    status: PackStatus,
    installLabel: String,
    exporting: Boolean,
    backing: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onExportBackup: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(onClick = onClick) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackIconThumbnail(path = pack.iconPath)
            Spacer(modifier = Modifier.size(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                // 长名称截断为两行：名称后仍能一眼看到状态行
                Text(
                    text = pack.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 版本名（用户可编辑）与版本号分开摆：前者给人看，后者决定能否覆盖安装
                Text(
                    text = "版本 ${pack.versionName}（v${pack.versionCode}）· " + when (status) {
                        PackStatus.NEVER_PACKED -> "未打包"
                        PackStatus.DIRTY -> "有未提交修改"
                        PackStatus.PACKED -> "已打包"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status) {
                        PackStatus.DIRTY -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 用户显式指定过下次版本号时如实预告，否则只能打包后才知道"这次打的到底是 v 几"
                if (pack.nextVersionCode != null) {
                    Text(
                        text = "下次打包 v${pack.upcomingVersionCode}（已指定）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    text = installLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                // 包名直接摆出来：多个图标包共存时，用户靠它区分"哪一个是哪一个"，
                // 也便于核对"改包名后是否真的换了系统身份"
                Text(
                    text = pack.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (exporting || backing) {
                CircularProgressIndicator(modifier = Modifier.size(IconSize.progressSmall))
            } else {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑名称与图标") },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出产物") },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                            enabled = pack.lastPackedAt != null,
                        )
                        DropdownMenuItem(
                            text = { Text("复制项目") },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出项目备份（ZIP，不含密钥库）") },
                            onClick = {
                                menuExpanded = false
                                onExportBackup()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 编辑项目对话框：与新建共用 [PackFormFields] 全字段表单。
 *
 * save 走屏幕回调的差异写库（图标与抽屉开关等"即时生效"操作除外）；版本降级
 * 警告、包名/版本校验等逻辑全部内嵌在表单字段里。
 */
@Composable
private fun EditPackDialog(
    pack: PackEntity,
    form: PackForm,
    installedVersionCode: Long?,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onPickKeystore: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (form: PackForm) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑图标包") },
        text = {
            ScrollableDialogContent {
                PackFormFields(
                    form = form,
                    installedVersionCode = installedVersionCode,
                    currentPackVersionCode = pack.versionCode,
                    onPickKeystore = onPickKeystore,
                    onClearKeystore = { form.clearKeystore() },
                    iconContent = { PackIconThumbnail(path = pack.iconPath) },
                    onPickIcon = onPickIcon,
                    onClearIcon = if (pack.iconPath != null) onClearIcon else null,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(form) }, enabled = form.isValid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 启动器图标缩略图；未设置时显示占位提示。
 * 支持两种来源：已落库的文件路径（走 [IconImage] 采样解码）或
 * 新建时临时选中的 [Uri]（尚未落库，就地按缩略图尺寸采样解码）。
 */
@Composable
private fun PackIconThumbnail(path: String? = null, uri: Uri? = null) {
    val context = LocalContext.current
    val maxPx = with(LocalDensity.current) { IconSize.thumb.roundToPx() }
    val bitmap = remember(path, uri) {
        when {
            path != null -> null
            uri != null -> runCatching {
                // 两段读取：先只读尺寸定采样率，再按采样解码，避免整图入内存
                val bounds = android.graphics.BitmapFactory.Options()
                    .apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0) {
                    null
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(
                            it,
                            null,
                            android.graphics.BitmapFactory.Options().apply {
                                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
                            },
                        )
                    }
                }
            }.getOrNull()
            else -> null
        }
    }
    when {
        path != null -> IconImage(
            path = path,
            contentDescription = "图标包图标",
            modifier = Modifier.size(IconSize.thumb),
        )

        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "图标包图标",
            modifier = Modifier.size(IconSize.thumb),
        )

        else -> Box(
            modifier = Modifier
                .size(IconSize.thumb)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "未设",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 新建项目对话框（FR-02 全字段版）：与编辑共用 [PackFormFields]，
 * 新增字段不再出现"编辑能改、新建不能选"的错位。创建校验集中在仓库层，
 * 表单的即时校验只为提前拦住用户、让保存按钮的表达准确。
 */
@Composable
private fun CreatePackDialog(
    form: PackForm,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onPickKeystore: () -> Unit,
    onClearKeystore: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建图标包") },
        text = {
            ScrollableDialogContent {
                PackFormFields(
                    form = form,
                    onPickKeystore = onPickKeystore,
                    onClearKeystore = onClearKeystore,
                    iconContent = { PackIconThumbnail(uri = form.iconUri) },
                    onPickIcon = onPickIcon,
                    onClearIcon = if (form.iconUri != null) onClearIcon else null,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate, enabled = form.isValid) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
