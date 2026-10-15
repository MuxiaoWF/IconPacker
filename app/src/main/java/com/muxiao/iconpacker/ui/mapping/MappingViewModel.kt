package com.muxiao.iconpacker.ui.mapping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import com.muxiao.iconpacker.ServiceLocator
import com.muxiao.iconpacker.core.packer.engine.IconPackManifest
import com.muxiao.iconpacker.core.packer.engine.IconShape
import com.muxiao.iconpacker.core.packer.engine.IconStyle
import com.muxiao.iconpacker.data.AppInfoProvider
import com.muxiao.iconpacker.data.IconAssetEntity
import com.muxiao.iconpacker.data.PackRepository
import com.muxiao.iconpacker.domain.IconUsageIndex
import com.muxiao.iconpacker.domain.ImportOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 应用行：设备或映射表里的一个目标 + 当前指派槽位（null = 未映射）。
 *
 * [apps] 列表里只包含**本机已安装**的应用。导入图标包时为目标未安装的应用建立的
 * 预置映射仍留在库里（应用装上后自动校准、打包时一并写入），但不占列表位置——
 * 一个覆盖 800 个应用的图标包会让这里多出几百行与当前设备无关的条目。
 *
 * 图标库反查（[MappingUiState.iconTargets]）是例外：那里**必须**带上预置映射，
 * 否则那些图标会被标成"未使用"（见 [com.muxiao.iconpacker.domain.IconUsageIndex]）。
 * 两种情况共用本类型，靠 [installed] 区分。
 */
data class AppRow(
    val packageName: String,
    val activityName: String,
    val label: String,
    val component: String,
    val assignedSlot: Int?,
    // 默认 true：所有"来自设备"的行都满足；只有图标库反查补出来的预置映射为 false
    val installed: Boolean = true,
)

/** 映射工作台状态（FR-03 导入 + FR-04 映射） */
data class MappingUiState(
    val packName: String = "",
    val apps: List<AppRow> = emptyList(),
    val icons: List<IconAssetEntity> = emptyList(),
    /** 图标反查：槽位 → 使用该图标的应用（图标库展示"供哪个 App 使用"）。
     *  与 [apps] 不同，这里**包含目标未安装的预置映射**——图标库问的是
     *  "这张图有没有被用上"，把预置映射排除掉会让那批图标显示成"未使用"。 */
    val iconTargets: Map<Int, List<AppRow>> = emptyMap(),
    val query: String = "",
    val loading: Boolean = true,
    val importing: Boolean = false,
    val message: String? = null,
) {
    val mappedCount: Int get() = apps.count { it.assignedSlot != null }
    val visibleApps: List<AppRow>
        get() = if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
}

class MappingViewModel(
    private val repository: PackRepository,
    private val packId: Long,
) : ViewModel() {

    private val appsFlow = MutableStateFlow<List<AppInfoProvider.AppEntry>>(emptyList())
    private val queryFlow = MutableStateFlow("")
    private val messageFlow = MutableStateFlow<String?>(null)
    private val importingFlow = MutableStateFlow(false)

    val uiState: StateFlow<MappingUiState> = combine(
        repository.observePack(packId),
        repository.observeMappings(packId),
        repository.observeIcons(packId),
        appsFlow,
        combine(queryFlow, messageFlow, importingFlow) { q, m, i -> Triple(q, m, i) },
    ) { pack, mappings, icons, apps, (query, message, importing) ->
        val slotByComponent = mappings.associate { it.component to it.slot }

        // 列表只由"本机已安装的可启动应用"构成：
        // 左边是设备事实，右边是库里是否有映射，逐行对应。
        // 库里那些目标未安装的预置映射不在这里露面（见 [AppRow]）。
        val appRows = apps.map { entry ->
            AppRow(
                packageName = entry.packageName,
                activityName = entry.activityName,
                label = entry.label,
                component = entry.component,
                assignedSlot = slotByComponent[entry.component],
            )
        }

        // 反查（支持 1:N，FR-04 验收 4）：**含目标未安装的预置映射**——
        // 图标库只关心"这张图有没有被用上"，预置映射同样是"已用上"，只是等应用装上来。
        // 排除它们会让导入的图标包大半图标显示"未使用"，与事实相反（见 [IconUsageIndex]）。
        val iconTargets = IconUsageIndex.build(
            mappings = mappings.map { it.component to it.slot },
            installed = apps.map {
                IconUsageIndex.InstalledApp(it.component, it.packageName, it.label)
            },
        ).mapValues { (slot, entries) ->
            entries.map { entry ->
                AppRow(
                    packageName = entry.packageName,
                    activityName = entry.component.substringAfter('/', "").removeSuffix("}"),
                    // 未安装的应用取不到名称，用包名兜底——界面上会另标"未安装"
                    label = entry.label ?: entry.packageName,
                    component = entry.component,
                    assignedSlot = slot,
                    installed = entry.installed,
                )
            }
        }

        MappingUiState(
            packName = pack?.name ?: "",
            apps = appRows,
            icons = icons,
            iconTargets = iconTargets,
            query = query,
            loading = apps.isEmpty(),
            importing = importing,
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MappingUiState())

    init {
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            // 先校准再由 Flow 重新组装列表：应用装上后，预置映射的组件名要换成设备真实值，
            // 否则它会以"未安装"行的身份一直挂着——图标在包里，桌面却不生效。
            val fixed = runCatching { repository.reconcileMappings(packId) }.getOrDefault(0)
            appsFlow.value = repository.loadApps()
            if (fixed > 0) {
                messageFlow.value = "已为 $fixed 个新安装的应用启用预置图标"
            }
        }
    }

    fun setQuery(query: String) {
        queryFlow.value = query
    }

    /**
     * 指派图标。
     *
     * @param fromComponent 非空表示这次是**改包名**：把 [fromComponent] 那条映射改挂到新目标
     *  [component] 上，槽位不变——而不是新增一条。槽位保持不变，才能让
     *  "换了个目标"与"重新指派图标"在库里表现为同一条映射，不会凭空多出一条。
     */
    fun assign(component: String, slot: Int, fromComponent: String? = null) {
        viewModelScope.launch {
            val target = IconPackManifest.packageOfComponent(component) ?: component
            if (fromComponent != null && fromComponent != component) {
                val displaced = repository.remapComponent(packId, fromComponent, component, slot)
                messageFlow.value = if (displaced != null) {
                    "映射已改指向 $target（该目标原有的映射被替换）"
                } else {
                    "映射已改指向 $target"
                }
            } else {
                val previousSlot = repository.setMapping(packId, component, slot)
                // 从图标库直接指定包名时，该包名可能本来就映射着别的图标——
                // 这种情况就是"换图"，说出来才不至于让人以为凭空少了一张映射
                messageFlow.value = if (previousSlot == null || previousSlot == slot) {
                    null
                } else {
                    "已将 $target 的映射改到这张图标上（它原有的映射已被替换）"
                }
            }
        }
    }

    fun unassign(component: String) {
        viewModelScope.launch { repository.removeMapping(packId, component) }
    }

    fun autoMatch() {
        viewModelScope.launch {
            val count = repository.autoMatch(packId)
            messageFlow.value = if (count > 0) "自动匹配新增 $count 个映射" else "没有找到可自动匹配的应用"
        }
    }

    fun importFromUris(uris: List<Pair<Uri, String>>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importingFlow.value = true
            val outcome = runCatching { repository.importFromUris(packId, uris) }
                .onFailure { messageFlow.value = "导入失败：${it.message}" }
                .getOrNull()
            importingFlow.value = false
            outcome?.let { messageFlow.value = describeImport(it, fromZip = false) }
        }
    }

    fun importFromZip(uri: Uri) {
        viewModelScope.launch {
            importingFlow.value = true
            val outcome = runCatching { repository.importFromZip(packId, uri) }
                .onFailure { messageFlow.value = "导入失败：${it.message}" }
                .getOrNull()
            importingFlow.value = false
            outcome?.let { messageFlow.value = describeImport(it, fromZip = true) }
        }
    }

    /**
     * 导入结果文案。
     *
     * 分项而不是只报总数：用户需要知道"为什么只建立了 9 个映射"以及"包里那些图去哪了"。
     * 静默丢弃与静默不匹配，是这一块最容易积累困惑的地方。
     */
    private fun describeImport(outcome: ImportOutcome, fromZip: Boolean): String {
        if (outcome.imported == 0) {
            return when {
                outcome.truncated > 0 -> "图标池已满（上限 ${PackRepository.MAX_SLOTS} 个），未能导入"
                fromZip -> "ZIP 中未找到可用的图片文件"
                else -> "没有导入任何图标"
            }
        }
        val parts = ArrayList<String>(5)
        parts += if (fromZip) "已从 ZIP 导入 ${outcome.imported} 个图标" else "已导入 ${outcome.imported} 个图标"

        if (outcome.autoMapped > 0) {
            val detail = buildList {
                if (outcome.manifestMapped > 0) add("包内映射表 ${outcome.manifestMapped}")
                if (outcome.packageMapped > 0) add("包名匹配 ${outcome.packageMapped}")
            }.joinToString("、")
            parts += "自动建立 ${outcome.autoMapped} 个映射（$detail）"
            // 预置映射单独报出——这是用户唯一能感知"图标包已经替将来做好了准备"的信号。
            // 映射列表里不列这些条目（见 [AppRow]），少了这句，那部分图标就像凭空消失了。
            if (outcome.presetMapped > 0) {
                parts += "另有 ${outcome.presetMapped} 个映射的目标应用未在本机安装，装上后自动生效"
            }
        } else if (fromZip && outcome.manifestPath == null) {
            parts += "包内未发现 appfilter.xml，可按名称手动匹配"
        } else if (fromZip) {
            parts += "未自动匹配到应用，可点「一键自动匹配」按名称匹配"
        }

        if (outcome.duplicates > 0) parts += "合并 ${outcome.duplicates} 个多密度副本"
        if (outcome.skipped > 0) parts += "跳过 ${outcome.skipped} 个非图片文件"
        if (outcome.truncated > 0) parts += "另有 ${outcome.truncated} 个超出槽位上限未导入"
        return parts.joinToString("；")
    }

    fun deleteIcon(slot: Int) {
        viewModelScope.launch { repository.deleteIcon(packId, slot) }
    }

    /**
     * 批量统一形状（FR-05）：从原图合成。
     *
     * @param backgroundColor 默认传透明；只有用户显式选了颜色才画底板。
     * @param overwriteEdited 是否连同已单独编辑过的图标一起重做。默认 false——
     *  FR-05 的措辞是"为所有**未设背景**的图标应用"，早期实现无条件覆盖
     *  全部图标，会把用户逐张调好的样式冲掉。
     */
    fun batchApplyShape(shape: IconShape, backgroundColor: Int, overwriteEdited: Boolean) {
        viewModelScope.launch {
            importingFlow.value = true
            val style = IconStyle(
                shape = shape,
                backgroundColor = backgroundColor,
                // 有底板时内容收到安全区，否则铺满（只裁外形不该缩内容）
                contentScale = if (backgroundColor == android.graphics.Color.TRANSPARENT) {
                    IconStyle.DEFAULT_CONTENT_SCALE
                } else {
                    IconStyle.SAFE_SCALE
                },
            )
            val result = runCatching {
                repository.batchApplyShape(packId, style, overwriteEdited)
            }.onFailure { messageFlow.value = "统一形状失败：${it.message}" }
                .getOrNull()
            importingFlow.value = false
            result?.let { (applied, skipped) ->
                messageFlow.value = when {
                    applied == 0 && skipped > 0 -> "没有可应用的图标（$skipped 个已单独编辑过）"
                    skipped > 0 -> "已统一应用 $applied 个图标，跳过 $skipped 个已单独编辑的"
                    else -> "已统一应用 $applied 个图标"
                }
            }
        }
    }

    fun consumeMessage() {
        messageFlow.value = null
    }

    companion object {
        fun factory(packId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { MappingViewModel(ServiceLocator.repository, packId) }
        }
    }
}
