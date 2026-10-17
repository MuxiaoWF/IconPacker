package com.muxiao.iconpacker.ui.packlist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muxiao.iconpacker.ServiceLocator
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.InstalledPackChecker
import com.muxiao.iconpacker.data.PackEntity
import com.muxiao.iconpacker.data.PackRepository
import com.muxiao.iconpacker.data.PackSignConfig
import com.muxiao.iconpacker.domain.PackVersionRules
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
    val exportingPackId: Long? = null,
    /** 正在导出备份 ZIP 的项目 id（v8） */
    val backupBusyPackId: Long? = null,
    /** 备份导入进行中（v8）；完成后 createdPackId 被置位跳转映射工作台 */
    val importingBackup: Boolean = false,
    val message: String? = null,
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
    fun installLabelOf(pack: PackEntity): String =
        when (val installed = installedVersions[pack.packageName]) {
            null -> "未安装"
            pack.versionCode.toLong() -> "已是最新"
            else -> "可更新（已装 v$installed → v${pack.versionCode}）"
        }
}

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
    private val messageFlow = MutableStateFlow<String?>(null)
    private val createdPackIdFlow = MutableStateFlow<Long?>(null)
    private val backupBusyFlow = MutableStateFlow<Long?>(null)
    private val importingBackupFlow = MutableStateFlow(false)

    private val packExtrasFlow = combine(createdPackIdFlow, backupBusyFlow, importingBackupFlow) {
            createdPackId, backupBusy, importing ->
        Triple(createdPackId, backupBusy, importing)
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

    val uiState: StateFlow<PackListUiState> = combine(
        packsFlow,
        installedFlow,
        exportingFlow,
        messageFlow,
        packExtrasFlow,
    ) { packs, installed, exporting, message, extras ->
        PackListUiState(
            packs = packs,
            loading = false,
            installedVersions = installed,
            exportingPackId = exporting,
            message = message,
            createdPackId = extras.first,
            backupBusyPackId = extras.second,
            importingBackup = extras.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackListUiState())

    init {
        refreshInstalled()
    }

    /** 查询已安装产物版本（回到列表页时刷新） */
    fun refreshInstalled() {
        refreshTrigger.value++
    }

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
                        messageFlow.value = "版本号必须是大于 0 的整数"
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
                        .onFailure { messageFlow.value = "已创建图标包，但图标设置失败：${it.message}" }
                }
                // 创建成功即直达映射工作台：新项目已自动持久化，用户可直接开始导入图标
                createdPackIdFlow.value = id
            } catch (e: Exception) {
                messageFlow.value = "创建失败：${e.message}"
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
                onSuccess = { "签名配置已更新" },
                onFailure = { "签名配置不可用：${it.message}" },
            )
        }
    }

    /** 复制项目（FR-02）：配置、图标池全量继承，包名/打包状态重置 */
    fun duplicatePack(pack: PackEntity) {
        viewModelScope.launch {
            val result = runCatching { repository.duplicatePack(pack.id) }
            messageFlow.value = result.fold(
                onSuccess = { "已复制为「${pack.name}（副本）」" },
                onFailure = { "复制失败：${it.message}" },
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
                onSuccess = { "包名已更新为 $trimmed" },
                onFailure = { "包名不可用：${it.message}" },
            )
        }
    }

    /** 设置产物 SDK 重写（v8）：null = 沿用模板（仓库层折算成 0 哨兵落库） */
    fun setBuildSdk(pack: PackEntity, minSdkOverride: Int?, targetSdkOverride: Int?) {
        viewModelScope.launch {
            val result = runCatching { repository.setBuildSdk(pack.id, minSdkOverride, targetSdkOverride) }
            messageFlow.value = result.fold(
                onSuccess = {
                    val min = minSdkOverride?.let { "min $it" } ?: "min 沿用模板"
                    val target = targetSdkOverride?.let { "target $it" } ?: "target 沿用模板"
                    "产物 SDK 已更新（$min / $target）"
                },
                onFailure = { "SDK 设置失败：${it.message}" },
            )
        }
    }

    /** 设置图标包自身的启动器图标（相册选图结果） */
    fun setPackIcon(pack: PackEntity, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching { repository.setPackIcon(pack.id, uri) }
            messageFlow.value = result.fold(
                onSuccess = { "已设置图标包图标" },
                onFailure = { "设置图标失败：${it.message}" },
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
                messageFlow.value = "版本号必须是大于 0 的整数"
                return
            }
        }
        viewModelScope.launch {
            val result = runCatching { repository.setVersionInfo(pack.id, versionName, next) }
            messageFlow.value = result.fold(
                onSuccess = { "版本已更新为 ${versionName.trim()}（下次打包 v${next ?: autoNext}）" },
                onFailure = { "版本设置失败：${it.message}" },
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
                onSuccess = { "已导出（${it / 1024} KB）" },
                onFailure = { "导出失败：${it.message}" },
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
                onSuccess = { "已导出项目备份（${it / 1024} KB），含图标与映射，不含密钥库" },
                onFailure = { "备份导出失败：${it.message}" },
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
                    messageFlow.value = "项目已导入"
                    createdPackIdFlow.value = packId
                },
                onFailure = { messageFlow.value = "备份导入失败：${it.message}" },
            )
        }
    }

    /** 密钥库导入等后台转前台操作在场外失败时，把原因投递到界面 Snackbar */
    fun postMessage(text: String) {
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
