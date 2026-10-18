package com.muxiao.iconpacker.ui.packlist

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.data.IconFileStore
import com.muxiao.iconpacker.data.PackEntity
import com.muxiao.iconpacker.data.toSignConfig
import com.muxiao.iconpacker.domain.PackVersionRules
import com.muxiao.iconpacker.ui.IconImage
import com.muxiao.iconpacker.ui.sampleSizeFor
import com.muxiao.iconpacker.ui.editor.IconEditorScreen
import com.muxiao.iconpacker.ui.editor.IconEditorTarget
import com.muxiao.iconpacker.ui.components.AppDivider
import com.muxiao.iconpacker.ui.components.AppScaffold
import com.muxiao.iconpacker.ui.components.EmptyState
import com.muxiao.iconpacker.ui.components.StatusBadge
import com.muxiao.iconpacker.ui.components.ThumbnailBox
import com.muxiao.iconpacker.ui.components.Tone
import com.muxiao.iconpacker.ui.components.UiText
import com.muxiao.iconpacker.ui.components.asString
import com.muxiao.iconpacker.ui.theme.IconSize
import com.muxiao.iconpacker.ui.theme.Layout
import com.muxiao.iconpacker.ui.theme.LocalAppPalette
import com.muxiao.iconpacker.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 项目列表（FR-02）+ 产物管理（FR-08）。
 *
 * 每项展示：版本与打包状态（未打包 / 有未提交修改 / 已打包）、
 * 安装状态徽标（与设备上已安装的产物版本对比）、导出与删除操作。
 */
@Composable
fun PackListScreen(
    onOpenPack: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PackListViewModel = viewModel(factory = PackListViewModel.factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
    // 图标包图标的一次性编辑会话（选图 → 编辑器 → 回填），见文件末尾的编辑浮层
    var packIconEdit by remember { mutableStateOf<PackIconEdit?>(null) }

    // 编辑对话框内选启动器图标：Photo Picker 免存储权限（FR-03 同款通道），
    // 选中后不直接落库——先进编辑器（见下方的编辑浮层），保存才生效
    val iconEditScope = rememberCoroutineScope()
    fun beginPackIconEdit(uri: Uri, pack: PackEntity?) {
        iconEditScope.launch {
            val staged = stageIconSource(context, uri)
            if (staged == null) {
                // 此刻表单页盖在列表之上，AppScaffold 的 Snackbar 被遮住看不见，
                // 所以这类"表单内"的错误用 Toast 直接弹在最上层
                Toast.makeText(context, R.string.msg_icon_read_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            packIconEdit = PackIconEdit(sourcePath = staged, pack = pack)
        }
    }

    val packIconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) beginPackIconEdit(uri, pendingEdit)
    }

    // 新建对话框内选启动器图标（同款免权限通道）：同样先进编辑器
    val createIconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) beginPackIconEdit(uri, pack = null)
    }

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
                    onFailure = {
                        viewModel.postMessage(UiText(R.string.msg_keystore_read_failed, it.message.orEmpty()))
                    },
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
                    onFailure = {
                        viewModel.postMessage(UiText(R.string.msg_keystore_read_failed, it.message.orEmpty()))
                    },
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
            snackbarHostState.showSnackbar(it.asString(context))
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
        title = stringResource(R.string.packlist_title),
        snackbarHostState = snackbarHostState,
        actions = {
            TextButton(
                onClick = {
                    backupImportLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed"),
                    )
                },
                enabled = !state.importingBackup,
            ) {
                Text(
                    text = if (state.importingBackup) {
                        stringResource(R.string.packlist_importing)
                    } else {
                        stringResource(R.string.packlist_import)
                    },
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { createForm = PackForm(defaults = settings) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.packlist_new)) },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { innerPadding ->
        if (state.packs.isEmpty() && !state.loading) {
            // 空态给出下一步动作：旧版只有一句"还没有图标包"，用户仍不知道该点哪
            EmptyState(
                title = stringResource(R.string.packlist_empty_title),
                body = stringResource(R.string.packlist_empty_body),
                icon = Icons.Default.Add,
                actionLabel = stringResource(R.string.dialog_create_pack_title),
                onAction = { createForm = PackForm(defaults = settings) },
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(
                    start = Layout.pageHorizontal,
                    end = Layout.pageHorizontal,
                    top = Layout.pageTop,
                    bottom = Layout.pageBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(Layout.cardGap),
            ) {
                item(key = "pack-count") {
                    Text(
                        text = stringResource(R.string.packlist_count, state.packs.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
                    )
                }
                items(state.packs, key = { it.id }) { pack ->
                    PackItem(
                        pack = pack,
                        status = state.statusOf(pack),
                        installLabel = state.installLabelOf(pack),
                        installedVersion = state.installedVersions[pack.packageName],
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
            onClearKeystore = { form.clearKeystoreFile() },
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
        val snapshot = pendingEdit!!
        // 图标在编辑里是**即时落库**的（不像其它字段"保存才写"），所以这里必须读实时行：
        // 用点击菜单时那份快照，选完图标缩略图还是"未设"，"移除图标"也永远不出现——
        // 用户会以为没选上，且选错了没有任何退路
        val pack = state.packs.firstOrNull { it.id == snapshot.id } ?: snapshot
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
            title = { Text(stringResource(R.string.packlist_delete_title)) },
            text = { Text(stringResource(R.string.packlist_delete_confirm, pack.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePack(pack)
                        pendingDelete = null
                    },
                ) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
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
        val backupFileName = stringResource(R.string.packlist_backup_filename, pack.name)
        LaunchedEffect(pack.id) {
            backupExportLauncher.launch(backupFileName)
        }
    }

    // 图标编辑浮层：叠在表单之上（与"表单叠在列表之上"同一套层叠顺序）。
    // 选图后一律走这里——先把裁剪/外形/背景调好，再决定保存还是放弃；
    // 放弃既不改变表单里已有的图标，也不碰数据库。
    packIconEdit?.let { edit ->
        // 每次会话单独一个 ViewModelStore，而不是复用 Activity 的那一份：
        // 1. 编辑器 VM 会常驻解码后的源图（约 3MB），挂在 Activity 上会按会话累积；
        // 2. 上一次会话的 finished=true 还在，复用同一个 VM 会让浮层刚出现就自动关闭。
        val sessionOwner = remember(edit.sourcePath) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(sessionOwner) {
            onDispose { sessionOwner.viewModelStore.clear() }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides sessionOwner) {
            IconEditorScreen(
                target = IconEditorTarget.PackIcon(edit.sourcePath, edit.outputPath),
                titleOverride = stringResource(R.string.editor_title_pack_icon),
                onSaved = {
                    val edited = Uri.fromFile(File(edit.outputPath))
                    val pack = edit.pack
                    if (pack == null) {
                        // 新建：产物先挂在表单上，创建项目时才由引擎编码落库
                        createForm?.iconUri = edited
                    } else {
                        // 编辑：与图标库里的编辑一致，保存即写库
                        viewModel.setPackIcon(pack, edited)
                    }
                },
                onBack = {
                    // 源图只是相册图片的临时副本，用完即删；产物留给表单引用
                    File(edit.sourcePath).delete()
                    packIconEdit = null
                },
            )
        }
    }
}

/**
 * 图标包图标的一次性编辑会话。
 *
 * [pack] 为空表示新建表单（结果暂存在表单里，创建时才落库），
 * 非空表示编辑既有项目（保存即写库，与图标库的编辑语义一致）。
 */
private data class PackIconEdit(
    val sourcePath: String,
    val pack: PackEntity?,
) {
    /**
     * 产物与源图同目录，路径由源图推导，避免在调用处各拼一遍。
     *
     * 只对**文件名**追加后缀：整条路径里 `com.muxiao.iconpacker` 也带点，
     * 按最后一个 `.` 切会把目录拆掉（实测得到 `/data/user/0/com.muxiao_edited.webp`）。
     */
    val outputPath: String
        get() = File(File(sourcePath).parentFile, File(sourcePath).name + "_edited.webp").absolutePath
}

/**
 * 缓存副本的保留时长：短到不占空间，长到足够覆盖一次正常编辑。
 */
private const val STALE_ICON_EDIT_MS = 60 * 60 * 1000L

/**
 * 把相册 Uri 复制到缓存目录，交给编辑器按路径编辑。
 *
 * 编辑器全链路走 `BitmapFactory.decodeFile`（见 IconProcessor），且 SAF 的临时授权
 * 不适合跨"打开编辑器 → 调参数 → 保存"这段较长的交互，所以先落地成本地文件。
 * 命名带时间戳、只清理陈旧副本：上一轮没保存的文件可能仍被表单引用着。
 */
private suspend fun stageIconSource(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "pack_icon_edit").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - STALE_ICON_EDIT_MS
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            val target = File(dir, "src_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            target.absolutePath
        }.getOrNull()
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
    installLabel: UiText,
    installedVersion: Long?,
    exporting: Boolean,
    backing: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onExportBackup: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalAppPalette.current
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = palette.card,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(Layout.hairline, palette.divider),
    ) {
        Row(
            // 右侧只留一个 48dp 的溢出按钮，所以 end 收到 xs：按钮自带触摸内边距，
            // 再叠 16dp 会让图标视觉上离卡片边缘过远，右侧重心空掉
            modifier = Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.md, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackIconThumbnail(path = pack.iconPath)
            Spacer(modifier = Modifier.size(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                // 第一层：名称。单行截断——两行标题会让下面两层被压出卡片视区
                Text(
                    text = pack.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(2.dp))
                // 第二层：元信息。版本名给人看、版本号决定能否覆盖安装、包名用于区分并存项目，
                // 三者同属"识别信息"，合并成一行灰字，不再各占一行争抢视线
                Text(
                    text = stringResource(
                        R.string.packlist_meta,
                        pack.versionName,
                        pack.versionCode,
                        pack.packageName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(Spacing.sm))
                // 第三层：状态徽标。彩色只留给这里，且用"底色 + 深字"而非纯彩色文字
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    StatusBadge(
                        text = stringResource(
                            when (status) {
                                PackStatus.NEVER_PACKED -> R.string.pack_status_never_packed
                                PackStatus.DIRTY -> R.string.pack_status_dirty
                                PackStatus.PACKED -> R.string.pack_status_packed
                            },
                        ),
                        tone = when (status) {
                            PackStatus.NEVER_PACKED -> Tone.Neutral
                            PackStatus.DIRTY -> Tone.Warning
                            PackStatus.PACKED -> Tone.Success
                        },
                    )
                    StatusBadge(
                        text = installLabel.asString(),
                        // 用 subject 形式：三个分支都在判断同一个值，写成条件链会读成三件事
                        tone = when (installedVersion) {
                            null -> Tone.Neutral
                            pack.versionCode.toLong() -> Tone.Success
                            else -> Tone.Primary
                        },
                    )
                    // 用户显式指定过下次版本号时如实预告，否则只能打包后才知道"这次打的是 v 几"
                    if (pack.nextVersionCode != null) {
                        StatusBadge(
                            text = stringResource(
                                R.string.packlist_next_version_short,
                                pack.upcomingVersionCode,
                            ),
                            tone = Tone.Primary,
                        )
                    }
                }
            }
            if (exporting || backing) {
                CircularProgressIndicator(modifier = Modifier.size(IconSize.progressSmall))
            } else {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_edit_pack)) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export_artifact)) },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                            enabled = pack.lastPackedAt != null,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_duplicate)) },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export_backup)) },
                            onClick = {
                                menuExpanded = false
                                onExportBackup()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.common_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
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
    PackFormPage(
        title = stringResource(R.string.dialog_edit_pack_title),
        confirmLabel = stringResource(R.string.common_save),
        confirmEnabled = form.isValid,
        onDismiss = onDismiss,
        onConfirm = { onSave(form) },
    ) {
        PackFormFields(
            form = form,
            installedVersionCode = installedVersionCode,
            currentPackVersionCode = pack.versionCode,
            onPickKeystore = onPickKeystore,
            onClearKeystore = { form.clearKeystoreFile() },
            iconContent = { PackIconThumbnail(path = pack.iconPath) },
            onPickIcon = onPickIcon,
            onClearIcon = if (pack.iconPath != null) onClearIcon else null,
        )
    }
}

/**
 * 全屏表单页骨架：顶栏（关闭 + 标题）+ 可滚表单 + 底栏（取消 + 主操作）。
 *
 * 与编辑器、打包页共用同一套骨架语言：页面底色、卡片内容、底栏常驻主操作。
 * 系统返回键与"关闭"同义——用户不该在表单里按返回却停在列表。
 */
@Composable
private fun PackFormPage(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalAppPalette.current
    BackHandler(onBack = onDismiss)
    Surface(modifier = Modifier.fillMaxSize(), color = palette.page) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.common_cancel),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Layout.pageHorizontal, vertical = Spacing.sm),
            ) {
                content()
                Spacer(modifier = Modifier.size(Spacing.lg))
            }
            Surface(color = palette.card) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    AppDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Layout.pageHorizontal, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            modifier = Modifier.heightIn(min = Layout.touchTarget),
                        ) { Text(confirmLabel) }
                    }
                }
            }
        }
    }
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
    ThumbnailBox(size = IconSize.thumb, shape = MaterialTheme.shapes.medium) {
        when {
            path != null -> IconImage(
                path = path,
                contentDescription = stringResource(R.string.cd_pack_icon),
                modifier = Modifier.fillMaxSize(),
            )

            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_pack_icon),
                modifier = Modifier.fillMaxSize(),
            )

            // 未设图标时保留同尺寸占位，不再用一行小字顶替整个缩略图
            else -> Text(
                text = stringResource(R.string.pack_icon_unset),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 新建项目表单（FR-02 全字段版）：与编辑共用 [PackFormFields]，
 * 新增字段不再出现"编辑能改、新建不能选"的错位。创建校验集中在仓库层，
 * 表单的即时校验只为提前拦住用户、让保存按钮的表达准确。
 *
 * 布局从 AlertDialog 改为全屏表单页：10 个字段塞进 75% 屏高的弹窗里，
 * 用户只能在一段可滚动的窄窗口里翻找，且"创建"按钮与内容争空间；
 * 全屏后顶栏给标题与关闭、底栏给主操作，表单拿到完整高度。
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
    PackFormPage(
        title = stringResource(R.string.dialog_create_pack_title),
        confirmLabel = stringResource(R.string.common_create),
        confirmEnabled = form.isValid,
        onDismiss = onDismiss,
        onConfirm = onCreate,
    ) {
        PackFormFields(
            form = form,
            onPickKeystore = onPickKeystore,
            onClearKeystore = onClearKeystore,
            iconContent = { PackIconThumbnail(uri = form.iconUri) },
            onPickIcon = onPickIcon,
            onClearIcon = if (form.iconUri != null) onClearIcon else null,
        )
    }
}
