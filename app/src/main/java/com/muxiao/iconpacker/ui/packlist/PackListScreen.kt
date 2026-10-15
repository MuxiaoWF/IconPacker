package com.muxiao.iconpacker.ui.packlist

import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxiao.iconpacker.core.packer.engine.PackageNameRules
import com.muxiao.iconpacker.data.PackEntity
import com.muxiao.iconpacker.domain.PackVersionRules

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
    viewModel: PackListViewModel = viewModel(factory = PackListViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PackEntity?>(null) }
    var pendingExport by remember { mutableStateOf<PackEntity?>(null) }
    var pendingEdit by remember { mutableStateOf<PackEntity?>(null) }

    // 编辑对话框内选启动器图标：Photo Picker 免存储权限（FR-03 同款通道）
    val packIconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val pack = pendingEdit
        if (uri != null && pack != null) viewModel.setPackIcon(pack, uri)
    }

    // 新建对话框内选启动器图标（同款免权限通道）；Uri 仅临时持有，落库后由引擎编码
    var createIconUri by remember { mutableStateOf<Uri?>(null) }
    val createIconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) createIconUri = uri }

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

    Scaffold(
        topBar = { TopAppBar(title = { Text("我的图标包") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建图标包")
            }
        },
    ) { innerPadding ->
        if (state.packs.isEmpty() && !state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有图标包。\n点击右下角按钮创建第一个，然后导入图标、映射到应用、一键打包。",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.packs, key = { it.id }) { pack ->
                    PackItem(
                        pack = pack,
                        status = state.statusOf(pack),
                        installLabel = state.installLabelOf(pack),
                        exporting = state.exportingPackId == pack.id,
                        onClick = { onOpenPack(pack.id) },
                        onEdit = { pendingEdit = pack },
                        onExport = { pendingExport = pack },
                        onDelete = { pendingDelete = pack },
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreatePackDialog(
            iconUri = createIconUri,
            onPickIcon = { createIconPicker.launch("image/*") },
            onClearIcon = { createIconUri = null },
            onDismiss = {
                showCreate = false
                createIconUri = null
            },
            onCreate = { name ->
                viewModel.createPack(name, createIconUri)
                showCreate = false
                createIconUri = null
            },
        )
    }

    // 编辑项目：名称 + 版本 + 包名 + 图标包自身的启动器图标（FR-02）
    pendingEdit?.let { pack ->
        EditPackDialog(
            pack = pack,
            // 与设备上已安装的产物版本对比，用于提示"这个号会装不上"
            installedVersionCode = state.installedVersions[pack.packageName],
            onDismiss = { pendingEdit = null },
            onPickIcon = { packIconPicker.launch("image/*") },
            onClearIcon = { viewModel.clearPackIcon(pack) },
            onShowInDrawerChange = { viewModel.setShowInDrawer(pack, it) },
            onRename = { name ->
                viewModel.renamePack(pack, name)
                pendingEdit = null
            },
            onSetPackageName = { viewModel.setPackageName(pack, it) },
            onSetVersion = { versionName, versionCodeInput ->
                viewModel.setVersionInfo(pack, versionName, versionCodeInput)
                pendingEdit = null
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

    // 导出文件名建议（带版本号，便于多版本管理；磁盘最新产物即当前 versionCode）
    pendingExport?.let { pack ->
        LaunchedEffect(pack.id) {
            exportLauncher.launch("IconPack-${pack.name}-v${pack.versionCode}.apk")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackItem(
    pack: PackEntity,
    status: PackStatus,
    installLabel: String,
    exporting: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = pack.name, style = MaterialTheme.typography.titleMedium)
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
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑名称与图标")
            }
            if (exporting) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            } else {
                IconButton(onClick = onExport, enabled = pack.lastPackedAt != null) {
                    Icon(Icons.Default.Share, contentDescription = "导出产物")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

/**
 * 编辑项目（FR-02）：名称 + 版本 + 包名 + 图标包自身的启动器图标。
 *
 * 图标是产物 APK 在启动器"图标主题"列表里显示的那个图标；不设置则沿用模板的
 * 125 字节占位 PNG，所有项目看起来都是同一个空白方块。
 *
 * 版本拆成两栏，因为它们是两个语义：
 * - **版本名**给人看，字符串随意（`2.1.0`、`2026.10`），写进清单 versionName；
 * - **下次打包版本号**给系统判断新旧，默认自动在最近版本上 +1（输入框预填自动值，
 *  不动它就什么都不改）。填一个低于已装版本的号，覆盖安装会被系统直接拒绝——
 *  所以这里就地给出警告，而不是等安装失败再排查。
 *
 * @param installedVersionCode 设备上已安装产物的版本号；null = 未安装（无须提示降级）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPackDialog(
    pack: PackEntity,
    installedVersionCode: Long?,
    onDismiss: () -> Unit,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onShowInDrawerChange: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onSetPackageName: (String) -> Unit,
    onSetVersion: (versionName: String, versionCodeInput: String) -> Unit,
) {
    var name by remember(pack.id) { mutableStateOf(pack.name) }
    var packageName by remember(pack.id) { mutableStateOf(pack.packageName) }
    var versionName by remember(pack.id) { mutableStateOf(pack.versionName) }
    // 预填"下次打包将使用的版本号"：显式指定过就用它，否则是自动递增的下一个
    var versionCodeInput by remember(pack.id) {
        mutableStateOf(pack.upcomingVersionCode.toString())
    }

    val trimmedPackage = packageName.trim()
    // 实时校验：非法包名会让产物装不上，不能让用户带着它点保存
    val packageIssue = PackageNameRules.validate(trimmedPackage)
    val packageChanged = trimmedPackage != pack.packageName

    val trimmedVersionName = versionName.trim()
    val versionNameIssue = PackVersionRules.validateName(trimmedVersionName)
    val versionCodeParsed = PackVersionRules.parseCode(versionCodeInput)
    val versionCodeIssue = if (versionCodeParsed is PackVersionRules.Parsed.Invalid) {
        PackVersionRules.Issue.CODE_NOT_POSITIVE.message
    } else {
        null
    }
    val nextVersionCode = when (versionCodeParsed) {
        PackVersionRules.Parsed.Auto -> pack.upcomingVersionCode
        is PackVersionRules.Parsed.Explicit -> versionCodeParsed.code
        PackVersionRules.Parsed.Invalid -> null
    }
    val versionChanged = trimmedVersionName != pack.versionName ||
        (when (versionCodeParsed) {
            PackVersionRules.Parsed.Auto -> null
            is PackVersionRules.Parsed.Explicit -> versionCodeParsed.code.takeIf {
                it != pack.upcomingVersionCode
            }
            PackVersionRules.Parsed.Invalid -> null
        }) != pack.nextVersionCode

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑图标包") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("图标包名称") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = versionName,
                    onValueChange = { versionName = it },
                    label = { Text("版本名") },
                    singleLine = true,
                    isError = versionNameIssue != null,
                    supportingText = {
                        Text(
                            text = versionNameIssue?.message
                                ?: "显示在系统的应用信息里；版本号与版本名是两件事",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = versionCodeInput,
                    onValueChange = { versionCodeInput = it.filter(Char::isDigit).take(10) },
                    label = { Text("下次打包版本号") },
                    singleLine = true,
                    isError = versionCodeIssue != null,
                    supportingText = {
                        when {
                            versionCodeIssue != null -> Text(
                                text = versionCodeIssue,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            // 低于已装版本：系统会以 INSTALL_FAILED_VERSION_DOWNGRADE 拒绝安装，
                            // 这个错在系统提示里只写包名不写原因，必须在填写时就拦住
                            nextVersionCode != null && installedVersionCode != null &&
                                nextVersionCode <= installedVersionCode -> Text(
                                text = "低于设备上已安装的 v$installedVersionCode，覆盖安装会被系统拒绝（需先卸载）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            nextVersionCode != null && nextVersionCode <= pack.versionCode -> Text(
                                text = "低于上次打包的 v${pack.versionCode}，装回旧版本同样会被拒绝",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Text(
                                text = "下次打包将使用 v$nextVersionCode；清空则回到自动递增",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("包名") },
                    singleLine = true,
                    isError = packageIssue != null,
                    supportingText = {
                        Text(
                            text = packageIssue?.message
                                ?: "每个图标包需要独立包名才能在系统里共存；改包名后要重新打包安装，" +
                                "旧包名会作为独立应用留在设备上（可自行卸载）",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "图标（在启动器里显示）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PackIconThumbnail(path = pack.iconPath)
                    Spacer(modifier = Modifier.width(12.dp))
                    TextButton(onClick = onPickIcon) { Text("选择图片") }
                    if (pack.iconPath != null) {
                        TextButton(onClick = onClearIcon) { Text("清除") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = pack.showInDrawer,
                        onCheckedChange = onShowInDrawerChange,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = "在应用抽屉显示", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (pack.showInDrawer) {
                                "抽屉里能看到这个图标包，点开是启用提示"
                            } else {
                                "只作为主题资源，不占抽屉位置"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRename(name)
                    if (versionChanged) onSetVersion(trimmedVersionName, versionCodeInput)
                    // 有改动才写库，避免每次编辑都触发一次无谓的占用检查与状态刷新
                    if (packageChanged) onSetPackageName(trimmedPackage)
                },
                enabled = name.isNotBlank() && packageIssue == null &&
                    versionNameIssue == null && versionCodeIssue == null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 启动器图标缩略图；未设置时显示占位提示。
 * 支持两种来源：已落库的文件路径（编辑时）或新建时临时选中的 [Uri]（尚未落库）。
 */
@Composable
private fun PackIconThumbnail(path: String? = null, uri: Uri? = null) {
    val context = LocalContext.current
    val bitmap = remember(path, uri) {
        when {
            uri != null -> runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            path != null -> android.graphics.BitmapFactory.decodeFile(path)
            else -> null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "图标包图标",
            modifier = Modifier.size(48.dp),
        )
        return
    }
    Box(
        modifier = Modifier
            .size(48.dp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePackDialog(
    iconUri: Uri?,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建图标包") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("图标包名称") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "图标（在启动器里显示，可选）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PackIconThumbnail(uri = iconUri)
                    Spacer(modifier = Modifier.width(12.dp))
                    TextButton(onClick = onPickIcon) { Text("选择图片") }
                    if (iconUri != null) {
                        TextButton(onClick = onClearIcon) { Text("清除") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
