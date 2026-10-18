package com.muxiao.iconpacker.ui.packlist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muxiao.iconpacker.ServiceLocator
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.InstalledPackChecker
import com.muxiao.iconpacker.data.PackEntity
import com.muxiao.iconpacker.data.PackRepository
import com.muxiao.iconpacker.data.PackSignConfig
import com.muxiao.iconpacker.data.StorageUsage
import com.muxiao.iconpacker.domain.PackVersionRules
import com.muxiao.iconpacker.ui.components.UiText
import com.muxiao.iconpacker.ui.components.uiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** 项目列表状态（FR-02 + FR-08 产物管理） */
data class PackListUiState(
    val packs: List<PackEntity> = emptyList(),
    val loading: Boolean = true,
    /**
     * 包名 → 设备上已安装的版本码；未安装的包名不出现在表里。
     * 用包名做键而不是单一常量：每个项目的产物包名各不相同，共存时必须逐项目查询，
     * 否则所有项目会共享同一个（且多半是错的）安装状态。
     */
    val installedVersions: Map<String, Long> = emptyMap(),
    /** 磁盘上有产物（`pack-v*.apk`）的项目 id：决定"安装/更新"是否可用，不重新打包 */
    val installableIds: Set<Long> = emptySet(),
    val exportingPackId: Long? = null,
    /** 正在导出备份 ZIP 的项目 id（v8） */
    val backupBusyPackId: Long? = null,
    /** 备份导入进行中（v8）；完成后 createdPackId 被置位跳转映射工作台 */
    val importingBackup: Boolean = false,
    /** 搜索关键词（匹配项目名与包名） */
    val query: String = "",
    val sort: PackSort = PackSort.RECENT,
    /** 多选模式：已勾选的项目 id */
    val selectedIds: Set<Long> = emptySet(),
    /** 私有目录占用（异步统计，首次拿到前为 null） */
    val usage: StorageUsage? = null,
    val message: UiText? = null,
    /** 刚创建成功的项目 id（用于创建后直达编辑页，消费后置空） */
    val createdPackId: Long? = null,
) {
    /** 项目的展示状态（FR-08：版本对比 + 未提交修改提示） */
    fun statusOf(pack: PackEntity): PackStatus = when {
        pack.lastPackedAt == null -> PackStatus.NEVER_PACKED
        pack.updatedAt > pack.lastPackedAt -> PackStatus.DIRTY
        else -> PackStatus.PACKED
    }

    /** 安装状态徽标（FR-08：与该**项目自己包名**对应的已安装版本对比） */
    fun installLabelOf(pack: PackEntity): UiText =
        when (val installed = installedVersions[pack.packageName]) {
            null -> UiText(R.string.packlist_install_not_installed)
            pack.versionCode.toLong() -> UiText(R.string.packlist_install_latest)
            else -> UiText(R.string.packlist_install_update, installed, pack.versionCode)
        }

    /** 设备上已装的项目数（概览条用） */
    val installedCount: Int get() = packs.count { it.packageName in installedVersions }

    fun canUninstall(pack: PackEntity): Boolean = pack.packageName in installedVersions

    fun canInstall(pack: PackEntity): Boolean = pack.id in installableIds

    /**
     * 按搜索词过滤 + 排序后的可见列表。
     *
     * 过滤放在内存里而不是 DAO：项目量级是几十条，改 SQL 只会把"排序/过滤"
     * 拆成两处逻辑；`RECENT` 直接沿用 DAO 的 updatedAt DESC，顺序不变。
     */
    val visiblePacks: List<PackEntity>
        get() {
            val keyword = query.trim()
            val filtered = if (keyword.isEmpty()) {
                packs
            } else {
                packs.filter {
                    it.name.contains(keyword, ignoreCase = true) ||
                        it.packageName.contains(keyword, ignoreCase = true)
                }
            }
            return when (sort) {
                PackSort.RECENT -> filtered
                PackSort.NAME -> filtered.sortedBy { it.name.lowercase() }
                PackSort.INSTALLED -> filtered.sortedWith(
                    compareBy<PackEntity>(
                        { it.packageName !in installedVersions },
                        { -it.updatedAt },
                    ),
                )
            }
        }
}

/** 列表排序方式 */
enum class PackSort { RECENT, NAME, INSTALLED }

/** 项目自身的打包状态 */
enum class PackStatus { NEVER_PACKED, DIRTY, PACKED }

class PackListViewModel(
    private val repository: PackRepository,
    private val installedPackChecker: InstalledPackChecker,
    private val settings: AppSettings,
) : ViewModel() {

    private val packsFlow = repository.observePacks()

    /** 全局默认值快照（设置页改完，这里新建表单/导出建议名即时跟随） */
    val settingsState: StateFlow<AppSettings.State> = settings.state

    /**
     * 手动刷新信号。
     * 设备上的安装状态会因用户在外部安装/卸载而变化，而 packs 表毫无改动——
     * 单靠数据库的 Flow 察觉不到，必须由一个显式信号驱动重查。
     */
    private val refreshTrigger = MutableStateFlow(0)

    private val exportingFlow = MutableStateFlow<Long?>(null)
    private val messageFlow = MutableStateFlow<UiText?>(null)
    private val createdPackIdFlow = MutableStateFlow<Long?>(null)
    private val backupBusyFlow = MutableStateFlow<Long?>(null)
    private val importingBackupFlow = MutableStateFlow(false)

    // ---- 列表筛选 / 多选（纯 UI 状态，不进数据库）----
    private val queryFlow = MutableStateFlow("")
    private val sortFlow = MutableStateFlow(PackSort.RECENT)
    private val selectionFlow = MutableStateFlow<Set<Long>>(emptySet())

    private data class Extras(
        val createdPackId: Long?,
        val backupBusyPackId: Long?,
        val importingBackup: Boolean,
        val message: UiText?,
    )

    private data class Prefs(
        val query: String,
        val sort: PackSort,
        val selected: Set<Long>,
    )

    private data class DeviceState(
        val installed: Map<String, Long>,
        val installable: Set<Long>,
        val usage: StorageUsage?,
    )

    private val extrasFlow = combine(
        createdPackIdFlow,
        backupBusyFlow,
        importingBackupFlow,
        messageFlow,
    ) { createdPackId, backupBusy, importing, message ->
        Extras(createdPackId, backupBusy, importing, message)
    }

    private val prefsFlow = combine(queryFlow, sortFlow, selectionFlow) { query, sort, selected ->
        Prefs(query, sort, selected)
    }

    /** 逐项目查询安装状态；PackageManager 调用不能占主线程 */
    private val installedFlow: Flow<Map<String, Long>> =
        combine(packsFlow, refreshTrigger) { packs, _ -> packs }
            .map { packs ->
                packs.mapNotNull { pack ->
                    installedPackChecker.query(pack.packageName)
                        ?.let { pack.packageName to it.versionCode }
                }.toMap()
            }
            .flowOn(Dispatchers.IO)

    /** 磁盘上是否还有该项目的产物（有就能直接安装/更新，不必重新打包） */
    private val installableFlow: Flow<Set<Long>> =
        combine(packsFlow, refreshTrigger) { packs, _ -> packs }
            .map { packs ->
                packs.mapNotNull { pack ->
                    pack.id.takeIf { repository.latestArtifactOf(it) != null }
                }.toSet()
            }
            .flowOn(Dispatchers.IO)

    /** 私有目录占用：随项目变更与刷新信号重算（几百次 stat，代价可忽略） */
    private val usageFlow: Flow<StorageUsage?> =
        combine(packsFlow, refreshTrigger) { packs, _ -> packs }
            .map { repository.storageUsage() }
            .flowOn(Dispatchers.IO)

    private val deviceFlow = combine(installedFlow, installableFlow, usageFlow) { installed, installable, usage ->
        DeviceState(installed, installable, usage)
    }

    val uiState: StateFlow<PackListUiState> = combine(
        packsFlow,
        deviceFlow,
        prefsFlow,
        extrasFlow,
        exportingFlow,
    ) { packs, device, prefs, extras, exporting ->
        PackListUiState(
            packs = packs,
            loading = false,
            installedVersions = device.installed,
            installableIds = device.installable,
            usage = device.usage,
            exportingPackId = exporting,
            message = extras.message,
            createdPackId = extras.createdPackId,
            backupBusyPackId = extras.backupBusyPackId,
            importingBackup = extras.importingBackup,
            query = prefs.query,
            sort = prefs.sort,
            // 项目被删/被筛掉后，残留的勾选不再计入（避免"已选 3 项"却只有 1 个匹配项）
            selectedIds = prefs.selected intersect packs.map { it.id }.toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackListUiState())

    init {
        refreshInstalled()
    }

    /** 手动刷新：安装状态、产物存在性、占用一起重查 */
    fun refreshInstalled() {
        refreshTrigger.value++
    }

    // ---- 搜索 / 排序 / 多选 ----

    fun setQuery(value: String) {
        queryFlow.value = value
    }

    fun setSort(value: PackSort) {
        sortFlow.value = value
    }

    fun toggleSelection(packId: Long) {
        selectionFlow.value = selectionFlow.value.let { if (packId in it) it - packId else it + packId }
    }

    fun selectAll(ids: Collection<Long>) {
        selectionFlow.value = ids.toSet()
    }

    fun clearSelection() {
        selectionFlow.value = emptySet()
    }

    /** 记住"卸载说明不再提示" */
    fun dismissUninstallHint() = settings.setUninstallHintDismissed(true)

    /** 最新产物文件（列表内直接安装用）；无产物时为 null */
    suspend fun latestArtifact(packId: Long): File? = repository.latestArtifactOf(packId)

    /**
     * 新建图标包（完整表单版，FR-02）。
     *
     * 校验交给仓库层统一处理（包名/版本/签名配置与编辑对话框共用同一套规则），
     * 失败原因原样进消息队列；图标随后单独设置——创建失败不产生半成品，
     * 图标失败才有"已创建但图标未设置"的降级提示。
     */
    fun createPack(draft: PackDraft) {
        viewModelScope.launch {
            try {
                // 版本号输入在界面已校验过一次；这里再折算一次，防的是"界面漏校验直接落库"
                val nextCode = when (val parsed = PackVersionRules.parseCode(draft.versionCodeInput)) {
                    PackVersionRules.Parsed.Auto -> null
                    is PackVersionRules.Parsed.Explicit -> parsed.code
                    PackVersionRules.Parsed.Invalid -> {
                        messageFlow.value = UiText(R.string.msg_version_code_invalid)
                        return@launch
                    }
                }
                val id = repository.createPack(
                    name = draft.name,
                    packageName = draft.packageName,
                    versionName = draft.versionName,
                    nextVersionCode = nextCode,
                    showInDrawer = draft.showInDrawer,
                    sign = draft.sign,
                    minSdkOverride = draft.minSdkOverride,
                    targetSdkOverride = draft.targetSdkOverride,
                )
                if (draft.iconUri != null) {
                    runCatching { repository.setPackIcon(id, draft.iconUri) }
                        .onFailure {
                            messageFlow.value = UiText(R.string.msg_pack_created_icon_failed, it.message.orEmpty())
                        }
                }
                // 创建成功即直达映射工作台：新项目已自动持久化，用户可直接开始导入图标
                createdPackIdFlow.value = id
            } catch (e: Exception) {
                messageFlow.value = UiText(R.string.msg_create_failed, e.message.orEmpty())
            }
        }
    }

    /**
     * 导入密钥库文件（新建/编辑对话框选文件时调用）。
     *
     * SAF Uri 只在本次授权内可读，必须当场落盘并把私有目录路径回传给表单；
     * 结果回调 [onResult] 把成功路径或失败原因交给对话框呈现。
     */
    fun importKeystoreFile(uri: Uri, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repository.importKeystoreFile(uri) })
        }
    }

    /** 设置项目签名配置（v7：密钥来源/签名者名/签名方案） */
    fun setSigningConfig(pack: PackEntity, config: PackSignConfig) {
        viewModelScope.launch {
            val result = runCatching { repository.setSigningConfig(pack.id, config) }
            messageFlow.value = result.fold(
                onSuccess = { UiText(R.string.msg_sign_config_updated) },
                onFailure = { it.uiMessage(R.string.msg_sign_config_invalid) },
            )
        }
    }

    /** 复制项目（FR-02）：配置、图标池全量继承，包名/打包状态重置 */
    fun duplicatePack(pack: PackEntity) {
        viewModelScope.launch {
            val result = runCatching { repository.duplicatePack(pack.id) }
            messageFlow.value = result.fold(
                onSuccess = { UiText(R.string.msg_duplicated, "${pack.name}（副本）") },
                onFailure = { it.uiMessage(R.string.msg_duplicate_failed) },
            )
        }
    }

    /** 消费"已创建"导航事件（避免旋转屏幕/返回时重复跳转） */
    fun consumeCreated() {
        createdPackIdFlow.value = null
    }

    fun renamePack(pack: PackEntity, name: String) {
        viewModelScope.launch { repository.renamePack(pack.id, name.trim()) }
    }

    /**
     * 修改产物包名。
     * 校验失败（语法错误 / 与其它项目重复）由仓库层抛错，这里把原因直接给到用户——
     * 包名写错的表现是"产物装不上"或"另一个图标包莫名被覆盖"，从界面上无从反推。
     */
    fun setPackageName(pack: PackEntity, packageName: String) {
        val trimmed = packageName.trim()
        viewModelScope.launch {
            val result = runCatching { repository.setPackageName(pack.id, trimmed) }
            messageFlow.value = result.fold(
                onSuccess = { UiText(R.string.msg_package_name_updated, trimmed) },
                onFailure = { it.uiMessage(R.string.msg_package_name_invalid) },
            )
        }
    }

    /** 设置产物 SDK 重写（v8）：null = 沿用模板（仓库层折算成 0 哨兵落库） */
    fun setBuildSdk(pack: PackEntity, minSdkOverride: Int?, targetSdkOverride: Int?) {
        viewModelScope.launch {
            val result = runCatching { repository.setBuildSdk(pack.id, minSdkOverride, targetSdkOverride) }
            messageFlow.value = result.fold(
                onSuccess = {
                    UiText(
                        R.string.msg_sdk_updated,
                        minSdkOverride?.let { "min $it" } ?: UiText(R.string.packlist_sdk_min_template),
                        targetSdkOverride?.let { "target $it" } ?: UiText(R.string.packlist_sdk_target_template),
                    )
                },
                onFailure = { it.uiMessage(R.string.msg_sdk_update_failed) },
            )
        }
    }

    /** 设置图标包自身的启动器图标（相册选图结果） */
    fun setPackIcon(pack: PackEntity, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching { repository.setPackIcon(pack.id, uri) }
            messageFlow.value = result.fold(
                onSuccess = { UiText(R.string.msg_pack_icon_set) },
                onFailure = { it.uiMessage(R.string.msg_pack_icon_failed) },
            )
        }
    }

    fun clearPackIcon(pack: PackEntity) {
        viewModelScope.launch { repository.clearPackIcon(pack.id) }
    }

    /** 图标包是否出现在应用抽屉 */
    fun setShowInDrawer(pack: PackEntity, show: Boolean) {
        viewModelScope.launch { repository.setShowInDrawer(pack.id, show) }
    }

    /**
     * 设置版本信息（FR-02 补充）。
     * [versionCodeInput] 是界面上的原始文本，这里做一次折算：
     * - **留空** 或 **恰好等于自动递增值** → 存 null（= 回到自动 +1）。
     *  后者是常态：输入框预填的就是自动值，用户没动它就不该在库里留下一条
     *  与实际行为毫无差别的"显式指定"。
     * - 显式填写的数 → 只对**下一次**打包生效，之后继续自动递增（见
     *  [PackEntity.nextVersionCode]）。
     */
    fun setVersionInfo(pack: PackEntity, versionName: String, versionCodeInput: String) {
        val autoNext = PackVersionRules.autoNext(pack.versionCode)
        val next = when (val parsed = PackVersionRules.parseCode(versionCodeInput)) {
            PackVersionRules.Parsed.Auto -> null
            is PackVersionRules.Parsed.Explicit -> parsed.code.takeIf { it != autoNext }
            PackVersionRules.Parsed.Invalid -> {
                messageFlow.value = UiText(R.string.msg_version_code_invalid)
                return
            }
        }
        viewModelScope.launch {
            val result = runCatching { repository.setVersionInfo(pack.id, versionName, next) }
            messageFlow.value = result.fold(
                onSuccess = {
                    UiText(
                        R.string.msg_version_updated,
                        versionName.trim(),
                        next ?: autoNext,
                    )
                },
                onFailure = { it.uiMessage(R.string.msg_version_update_failed) },
            )
        }
    }

    fun deletePack(pack: PackEntity) {
        viewModelScope.launch {
            repository.deletePack(pack.id)
            refreshInstalled()
        }
    }

    /** 导出最新产物到用户选定 Uri（FR-08） */
    fun exportLatestArtifact(pack: PackEntity, target: Uri) {
        viewModelScope.launch {
            exportingFlow.value = pack.id
            val result = runCatching { repository.exportLatestArtifact(pack.id, target) }
            exportingFlow.value = null
            messageFlow.value = result.fold(
                onSuccess = { UiText(R.string.msg_exported, it / 1024) },
                onFailure = { it.uiMessage(R.string.msg_export_failed) },
            )
        }
    }

    /** 导出项目备份 ZIP（配置 + 图标池 + 映射；密钥库文件与密码不随包走） */
    fun exportProjectBackup(pack: PackEntity, uri: Uri) {
        viewModelScope.launch {
            backupBusyFlow.value = pack.id
            val result = runCatching { ServiceLocator.projectBackup.exportProject(pack.id, uri) }
            backupBusyFlow.value = null
            messageFlow.value = result.fold(
                onSuccess = { UiText(R.string.msg_backup_exported, it / 1024) },
                onFailure = { it.uiMessage(R.string.msg_backup_export_failed) },
            )
        }
    }

    /** 导入项目备份 ZIP：返回新项目 id 后由界面跳转映射工作台 */
    fun importProjectBackup(uri: Uri) {
        viewModelScope.launch {
            importingBackupFlow.value = true
            val result = runCatching { ServiceLocator.projectBackup.importProject(uri) }
            importingBackupFlow.value = false
            result.fold(
                onSuccess = { packId ->
                    messageFlow.value = UiText(R.string.msg_backup_imported)
                    createdPackIdFlow.value = packId
                },
                onFailure = { messageFlow.value = UiText(R.string.msg_backup_import_failed, it.message.orEmpty()) },
            )
        }
    }

    /** 密钥库导入等后台转前台操作在场外失败时，把原因投递到界面 Snackbar */
    fun postMessage(text: UiText) {
        messageFlow.value = text
    }

    fun consumeMessage() {
        messageFlow.value = null
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PackListViewModel(
                    repository = ServiceLocator.repository,
                    installedPackChecker = ServiceLocator.installedPackChecker,
                    settings = ServiceLocator.settings,
                )
            }
        }
    }
}
