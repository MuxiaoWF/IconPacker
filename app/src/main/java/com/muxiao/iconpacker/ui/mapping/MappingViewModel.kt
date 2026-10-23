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
import com.muxiao.iconpacker.R
import com.muxiao.iconpacker.domain.ImportOutcome
import com.muxiao.iconpacker.ui.components.UiText
import com.muxiao.iconpacker.ui.components.uiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 应用行：设备或映射表里的一个目标 + 当前指派槽位（null = 未映射）。
 *
 * `apps` 列表里只包含**本机已安装**的应用。导入图标包时为目标未安装的应用建立的
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
    /**
     * 只看未映射。**两个 Tab 共用**，但"未映射"的含义各自成立：
     * - 应用：还没有指派任何图标（`assignedSlot == null`）；
     * - 图标：还没有任何应用用它（反查表里查不到，`iconTargets[slot]` 为空）。
     *
     * 两边问的是同一个问题——"还有哪些没有配对"——所以共用一个开关，
     * 而不是给图标再发明一个"只看未使用"，否则同一屏里会出现两个语义相近的开关。
     */
    val onlyUnmapped: Boolean = false,
    /**
     * 组件 → 槽位（**含目标未安装的预置映射**）。
     *
     * 包名编辑对话框用它判断"这个包名是不是已经有图标了"（见 [MappingConflict]）。
     * 预置映射同样占着包名——应用没装不代表它不挡路，把它排除掉会漏报冲突。
     */
    val slotByComponent: Map<String, Int> = emptyMap(),
    /** 槽位 → 图标显示名（冲突提示里要指名道姓："已被「com.tencent.mm」使用"） */
    val iconNameBySlot: Map<Int, String> = emptyMap(),
    val loading: Boolean = true,
    val importing: Boolean = false,
    val message: List<UiText>? = null,
) {
    val mappedCount: Int get() = apps.count { it.assignedSlot != null }
    val visibleApps: List<AppRow>
        get() = apps
            .asSequence()
            .filter { !onlyUnmapped || it.assignedSlot == null }
            .filter { app ->
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .toList()

    /**
     * 未使用的图标：反查表里没有它。
     *
     * 注意"有映射但目标没装"不算未使用——图标确实被指派了，只是等应用装上来，
     * 把它算成未使用会让人以为要重新挑一张图。
     */
    val unusedIcons: List<IconAssetEntity>
        get() = icons.filter { iconTargets[it.slot].isNullOrEmpty() }

    /** 图标库当前应显示的图标（受同一个"只看未映射"开关约束） */
    val visibleIcons: List<IconAssetEntity>
        get() = if (onlyUnmapped) unusedIcons else icons
}

class MappingViewModel(
    private val repository: PackRepository,
    private val packId: Long,
) : ViewModel() {

    private val appsFlow = MutableStateFlow<List<AppInfoProvider.AppEntry>>(emptyList())
    private val queryFlow = MutableStateFlow("")
    private val messageFlow = MutableStateFlow<List<UiText>?>(null)
    private val importingFlow = MutableStateFlow(false)
    private val unmappedOnlyFlow = MutableStateFlow(false)

    /**
     * 残缺映射数：缺活动类名且目标未安装的条目——标准启动器会整条丢弃，
     * 桌面表现为"图标包没有任何可以替换的图标"。头部卡按此预警。
     */
    val brokenCount: StateFlow<Int> = repository.observeMappings(packId)
        .map { repository.incompleteMappingCount(packId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 搜索行下方控件共用的小状态包 */
    private data class ListControls(
        val query: String,
        val message: List<UiText>?,
        val importing: Boolean,
        val onlyUnmapped: Boolean,
    )

    private val listControlsFlow = combine(
        queryFlow,
        messageFlow,
        importingFlow,
        unmappedOnlyFlow,
    ) { query, message, importing, onlyUnmapped ->
        ListControls(query, message, importing, onlyUnmapped)
    }

    val uiState: StateFlow<MappingUiState> = combine(
        repository.observePack(packId),
        repository.observeMappings(packId),
        repository.observeIcons(packId),
        appsFlow,
        listControlsFlow,
    ) { pack, mappings, icons, apps, controls ->
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
            slotByComponent = slotByComponent,
            iconNameBySlot = icons.associate { it.slot to it.originalName },
            query = controls.query,
            onlyUnmapped = controls.onlyUnmapped,
            loading = apps.isEmpty(),
            importing = controls.importing,
            message = controls.message,
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
                messageFlow.value = listOf(UiText(R.string.msg_preset_activated, fixed))
            }
        }
    }

    fun setQuery(query: String) {
        queryFlow.value = query
    }

    fun setOnlyUnmapped(only: Boolean) {
        unmappedOnlyFlow.value = only
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
                    // 指名道姓：被顶掉的是哪张图（只说包名等于没说，用户根本不知道少了什么）
                    listOf(
                        UiText(
                            R.string.msg_retarget_displaced,
                            target,
                            repository.iconNameAt(packId, displaced.slot),
                        ),
                    )
                } else {
                    listOf(UiText(R.string.msg_retarget, target))
                }
            } else {
                val previousSlot = repository.setMapping(packId, component, slot)
                // 从图标库直接指定包名时，该包名可能本来就映射着别的图标——
                // 这种情况就是"换图"，说出来才不至于让人以为凭空少了一张映射
                messageFlow.value = if (previousSlot == null || previousSlot == slot) {
                    null
                } else {
                    listOf(
                        UiText(
                            R.string.msg_reassign_icon,
                            target,
                            repository.iconNameAt(packId, previousSlot),
                        ),
                    )
                }
            }
        }
    }

    fun unassign(component: String) {
        viewModelScope.launch {
            repository.removeMapping(packId, component)
            // 清除是不可见的状态变化（行会退回"未映射"），给一句回执才确定点中了。
            // 优先用应用名——用户认的是"Clash Meta"，不是包名
            val target = uiState.value.apps.firstOrNull { it.component == component }?.label
                ?: IconPackManifest.packageOfComponent(component)
                ?: component
            messageFlow.value = listOf(UiText(R.string.msg_unassign, target))
        }
    }

    fun autoMatch() {
        viewModelScope.launch {
            val count = repository.autoMatch(packId)
            messageFlow.value = if (count > 0) {
                listOf(UiText(R.string.msg_automatch_added, count))
            } else {
                listOf(UiText(R.string.msg_automatch_none))
            }
        }
    }

    fun importFromUris(uris: List<Pair<Uri, String>>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importingFlow.value = true
            val outcome = runCatching { repository.importFromUris(packId, uris) }
                .onFailure { messageFlow.value = listOf(it.uiMessage(R.string.msg_import_failed)) }
                .getOrNull()
            importingFlow.value = false
            outcome?.let { messageFlow.value = describeImport(it, fromZip = false) }
        }
    }

    fun importFromZip(uri: Uri) {
        viewModelScope.launch {
            importingFlow.value = true
            val outcome = runCatching { repository.importFromZip(packId, uri) }
                .onFailure { messageFlow.value = listOf(it.uiMessage(R.string.msg_import_failed)) }
                .getOrNull()
            importingFlow.value = false
            outcome?.let { messageFlow.value = describeImport(it, fromZip = true) }
        }
    }

    /**
     * 导入结果文案（分项，UI 侧按本地化分隔符拼接）。
     *
     * 分项而不是只报总数：用户需要知道"为什么只建立了 9 个映射"以及"包里那些图去哪了"。
     * 静默丢弃与静默不匹配，是这一块最容易积累困惑的地方。
     */
    private fun describeImport(outcome: ImportOutcome, fromZip: Boolean): List<UiText> {
        if (!outcome.changed) {
            // 一张都没进来，或全都与池中一模一样（重复导入同一份 ZIP 就落在这一支）
            return listOf(
                when {
                    outcome.unchanged > 0 ->
                        UiText(R.string.msg_import_unchanged_only, outcome.unchanged)
                    outcome.truncated > 0 ->
                        UiText(R.string.msg_import_pool_full, PackRepository.MAX_SLOTS)
                    fromZip -> UiText(R.string.msg_zip_no_images)
                    else -> UiText(R.string.msg_import_none)
                },
            )
        }
        val parts = ArrayList<UiText>(7)
        if (outcome.imported > 0) {
            parts += if (fromZip) {
                UiText(R.string.msg_imported_from_zip, outcome.imported)
            } else {
                UiText(R.string.msg_imported, outcome.imported)
            }
        }
        // 更新与未变化必须单独报：否则"60 张更新 + 60 张新增"会被读成"只进来了 60 张"
        if (outcome.updated > 0) parts += UiText(R.string.msg_import_updated, outcome.updated)
        if (outcome.unchanged > 0) parts += UiText(R.string.msg_import_unchanged, outcome.unchanged)
        if (outcome.editedReset > 0) parts += UiText(R.string.msg_import_edit_reset, outcome.editedReset)

        if (outcome.autoMapped > 0) {
            val detail = buildList {
                if (outcome.manifestMapped > 0) add(UiText(R.string.msg_automap_manifest, outcome.manifestMapped))
                if (outcome.packageMapped > 0) add(UiText(R.string.msg_automap_package, outcome.packageMapped))
            }.flatMapIndexed { i, part ->
                if (i == 0) listOf(part) else listOf(UiText(R.string.mapping_automap_sep), part)
            }
            parts += UiText(R.string.msg_automap_detail, outcome.autoMapped, detail)
            // 预置映射单独报出——这是用户唯一能感知"图标包已经替将来做好了准备"的信号。
            // 映射列表里不列这些条目（见 [AppRow]），少了这句，那部分图标就像凭空消失了。
            if (outcome.presetMapped > 0) {
                parts += UiText(R.string.msg_automap_preset, outcome.presetMapped)
            }
        } else if (fromZip && outcome.imported > 0) {
            // 纯更新时不该弹"没匹配到应用"——那些图标本来就已经有映射了
            parts += if (outcome.manifestPath == null) {
                UiText(R.string.msg_zip_no_appfilter)
            } else {
                UiText(R.string.msg_zip_no_auto_match)
            }
        }

        if (outcome.duplicates > 0) parts += UiText(R.string.msg_import_merged, outcome.duplicates)
        if (outcome.skipped > 0) parts += UiText(R.string.msg_import_skipped, outcome.skipped)
        if (outcome.truncated > 0) parts += UiText(R.string.msg_import_truncated, outcome.truncated)
        return parts
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
            }.onFailure { messageFlow.value = listOf(it.uiMessage(R.string.msg_shape_failed)) }
                .getOrNull()
            importingFlow.value = false
            result?.let { (applied, skipped) ->
                messageFlow.value = when {
                    applied == 0 && skipped > 0 ->
                        listOf(UiText(R.string.msg_batch_none_applied, skipped))
                    skipped > 0 ->
                        listOf(UiText(R.string.msg_batch_applied_skipped, applied, skipped))
                    else -> listOf(UiText(R.string.msg_batch_applied, applied))
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
