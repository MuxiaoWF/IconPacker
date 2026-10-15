package com.muxiao.iconpacker.ui.packlist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muxiao.iconpacker.ServiceLocator
import com.muxiao.iconpacker.data.InstalledPackChecker
import com.muxiao.iconpacker.data.PackEntity
import com.muxiao.iconpacker.data.PackRepository
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
) : ViewModel() {

    private val packsFlow = repository.observePacks()

    /**
     * 手动刷新信号。
     * 设备上的安装状态会因用户在外部安装/卸载而变化，而 packs 表毫无改动——
     * 单靠数据库的 Flow 察觉不到，必须由一个显式信号驱动重查。
     */
    private val refreshTrigger = MutableStateFlow(0)

    private val exportingFlow = MutableStateFlow<Long?>(null)
    private val messageFlow = MutableStateFlow<String?>(null)
    private val createdPackIdFlow = MutableStateFlow<Long?>(null)

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
        createdPackIdFlow,
    ) { packs, installed, exporting, message, createdPackId ->
        PackListUiState(
            packs = packs,
            loading = false,
            installedVersions = installed,
            exportingPackId = exporting,
            message = message,
            createdPackId = createdPackId,
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
     * 新建图标包；可选附带启动器图标（新建对话框选图，Uri 尚未落库）。
     * 项目创建时即分配唯一包名，因此新建的图标包不会和已有项目抢同一个系统位置。
     */
    fun createPack(name: String, iconUri: Uri? = null) {
        viewModelScope.launch {
            val id = repository.createPack(name.trim())
            if (iconUri != null) {
                runCatching { repository.setPackIcon(id, iconUri) }
                    .onFailure { messageFlow.value = "已创建图标包，但图标设置失败：${it.message}" }
            }
            // 创建成功即直达编辑页：新项目已自动持久化，用户可直接开始导入图标
            createdPackIdFlow.value = id
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

    fun consumeMessage() {
        messageFlow.value = null
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PackListViewModel(ServiceLocator.repository, ServiceLocator.installedPackChecker)
            }
        }
    }
}
