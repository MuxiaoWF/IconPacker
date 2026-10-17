package com.muxiao.iconpacker.data

import com.muxiao.iconpacker.core.packer.engine.ApkBuilder
import com.muxiao.iconpacker.core.packer.engine.IconMapping
import com.muxiao.iconpacker.core.packer.engine.IconPackManifest
import com.muxiao.iconpacker.core.packer.engine.IconProcessor
import com.muxiao.iconpacker.core.packer.engine.IconStyle
import com.muxiao.iconpacker.core.packer.engine.PackageNameRules
import com.muxiao.iconpacker.core.packer.engine.PackProgress
import com.muxiao.iconpacker.core.packer.engine.PackRequest
import com.muxiao.iconpacker.core.packer.engine.PackResult
import com.muxiao.iconpacker.core.packer.engine.SlotIcon
import com.muxiao.iconpacker.core.packer.engine.ZipIconPlanner
import com.muxiao.iconpacker.core.packer.sign.AndroidKeystoreKeyProvider
import com.muxiao.iconpacker.domain.AppMatcher
import com.muxiao.iconpacker.domain.ImportBinder
import com.muxiao.iconpacker.domain.ImportOutcome
import com.muxiao.iconpacker.domain.MappingReconciler
import com.muxiao.iconpacker.domain.PackVersionRules
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 图标包项目仓库：数据库 + 文件 + 打包引擎的唯一编排点。
 *
 * ViewModel 只依赖本类；引擎（core/packer）与系统能力（PackageManager/ContentResolver）
 * 的细节全部收敛在此。
 */
class PackRepository(
    private val packDao: PackDao,
    private val mappingDao: MappingDao,
    private val assetDao: IconAssetDao,
    private val fileStore: IconFileStore,
    private val appInfoProvider: AppInfoProvider,
    private val apkBuilder: ApkBuilder = ApkBuilder(),
    private val iconProcessor: IconProcessor = IconProcessor(),
    private val signingKeyProvider: com.muxiao.iconpacker.core.packer.sign.SigningKeyProvider =
        AndroidKeystoreKeyProvider(),
) {

    // ---- 观察（UI 响应式数据源） ----

    fun observePacks(): Flow<List<PackEntity>> = packDao.observeAll()

    fun observePack(packId: Long): Flow<PackEntity?> = packDao.observeById(packId)

    fun observeMappings(packId: Long): Flow<List<MappingEntity>> = mappingDao.observeByPack(packId)

    fun observeIcons(packId: Long): Flow<List<IconAssetEntity>> = assetDao.observeByPack(packId)

    /** 已安装应用列表（映射工作台数据源，FR-04） */
    suspend fun loadApps(): List<AppInfoProvider.AppEntry> = appInfoProvider.loadLaunchableApps()

    // ---- 项目（FR-02） ----

    /**
     * 新建项目。
     *
     * 项目诞生即分配唯一包名（由 id 派生，见 [PackDao.insertWithPackage]）——
     * 包名是产物在系统里的身份，不分配的后果是第二个图标包把第一个覆盖掉。
     */
    suspend fun createPack(name: String): Long {
        val now = System.currentTimeMillis()
        return packDao.insertWithPackage(
            PackEntity(name = name, createdAt = now, updatedAt = now),
            now,
        )
    }

    suspend fun renamePack(packId: Long, name: String) {
        packDao.rename(packId, name, System.currentTimeMillis())
    }

    /**
     * 设置图标包自身的启动器图标（FR-02 补充）。
     *
     * 用户在相册选图 → 编码为 192px PNG → 存私有目录 → 路径落库。
     * 编码必须在 IO 线程（Bitmap 解码），由 [IconProcessor.encodeLauncherIcon] 完成。
     */
    suspend fun setPackIcon(packId: Long, uri: Uri): String = withContext(Dispatchers.IO) {
        val source = fileStore.copyImportedIcon(packId, PACK_ICON_SLOT, 0, uri, "pack_icon.png")
        val bytes = iconProcessor.encodeLauncherIcon(source)
        val path = fileStore.savePackIcon(packId, bytes)
        packDao.setIcon(packId, path, System.currentTimeMillis())
        // 落地的原图只用于编码，用完即删，避免私有目录堆积
        fileStore.deleteFile(source)
        path
    }

    /** 清除启动器图标，回退到模板占位图 */
    suspend fun clearPackIcon(packId: Long) {
        packDao.getById(packId)?.iconPath?.let { fileStore.deleteFile(it) }
        packDao.setIcon(packId, null, System.currentTimeMillis())
    }

    /** 设置图标包是否出现在应用抽屉（FR-02 补充） */
    suspend fun setShowInDrawer(packId: Long, show: Boolean) {
        packDao.setShowInDrawer(packId, show, System.currentTimeMillis())
    }

    /**
     * 修改产物包名。
     *
     * 两道检查缺一不可：
     * 1. 语法（[PackageNameRules]）——非法包名产出的 APK 装不上；
     * 2. 占用——两个项目共用包名时，后装的产物会覆盖先装的，而用户看到的只是
     *  "另一个图标包怎么不见了"，完全追不到这里。
     *
     * 注意改包名 ≠ 迁移已安装的包：旧包名在系统里仍是一个独立应用，需用户自行卸载。
     */
    suspend fun setPackageName(packId: Long, packageName: String) {
        PackageNameRules.requireValid(packageName)
        packDao.findOtherWithPackageName(packageName, packId)?.let { other ->
            throw IllegalArgumentException("包名已被项目「${other.name}」占用")
        }
        packDao.setPackageName(packId, packageName, System.currentTimeMillis())
    }

    suspend fun deletePack(packId: Long) {
        packDao.getById(packId)?.let {
            packDao.delete(it) // Room 级联删除映射与图标记录
            fileStore.deletePackFiles(packId)
        }
    }

    /**
     * 设置版本信息（FR-02 补充）。
     *
     * @param versionName 版本名（系统"应用信息"里显示的那一行）
     * @param nextVersionCode 显式指定的**下次打包版本号**；null = 回到自动递增。
     *  界面把"留空"与"恰好等于自动值"都折算成 null，避免库里多出一条
     *  与实际行为无差别的显式指定。
     */
    suspend fun setVersionInfo(packId: Long, versionName: String, nextVersionCode: Int?) {
        val name = versionName.trim()
        PackVersionRules.validateName(name)?.let { throw IllegalArgumentException(it.message) }
        PackVersionRules.validateCode(nextVersionCode)
            ?.let { throw IllegalArgumentException(it.message) }
        packDao.setVersionInfo(packId, name, nextVersionCode, System.currentTimeMillis())
    }

    // ---- 图标导入（FR-03） ----

    /**
     * 照片选择器多选导入。返回分项结果（含自动建立的映射数）。
     *
     * 与 ZIP 通道共用后半段：按 `matchKey` 走包名精确匹配（L2）。
     * 从相册选来的图通常命名无意义（`IMG_2024.jpg`），此时不会命中，行为与从前一致；
     * 但如果用户刻意用包名命名文件，就能省掉手动指派。
     */
    suspend fun importFromUris(packId: Long, uris: List<Pair<Uri, String>>): ImportOutcome {
        if (uris.isEmpty()) return ImportOutcome()
        val used = usedSlots(packId)
        val accepted = uris.take(availableSlots(used))
        val truncated = uris.size - accepted.size
        if (accepted.isEmpty()) return ImportOutcome(truncated = truncated)

        val slots = allocateSlots(used, accepted.size)
        val sources = accepted.mapIndexed { index, (uri, displayName) ->
            ImportedSource(
                path = fileStore.copyImportedIcon(packId, slots[index], index, uri, displayName),
                fileName = displayName,
                matchKey = ZipIconPlanner.matchKeyOf(displayName),
            )
        }
        return insertAndBind(packId, slots, sources, truncated = truncated)
    }

    /**
     * ZIP 批量导入：解包（去重、取 appfilter）→ 入池 → 自动建立映射。
     *
     * 映射分两层自动完成（见 [ImportBinder]）：L1 用包内 appfilter 的权威映射，
     * L2 用"文件名/目录名 = 包名"的确定性匹配。两者都不涉及相似度猜测，
     * 所以可以在导入时直接生效而不会误配；模糊匹配仍由用户主动触发。
     *
     * L1 **不要求目标应用已安装**：appfilter 里就带着完整的组件名，应用还没装也先把
     * 映射占好位，等它装上就能直接用上图标（这类条目计入 [ImportOutcome.presetMapped]）。
     */
    suspend fun importFromZip(packId: Long, uri: Uri): ImportOutcome {
        val extraction = fileStore.importZipFromUri(packId, uri)
        // 映射表解析失败不该让整次导入失败——L2 仍能工作
        val manifestEntries = extraction.appFilterBytes
            ?.let { bytes -> runCatching { IconPackManifest.parse(bytes).entries }.getOrDefault(emptyList()) }
            .orEmpty()

        val used = usedSlots(packId)
        val accepted = extraction.items.take(availableSlots(used))
        val truncated = extraction.items.size - accepted.size
        // 超出槽位上限的条目立即删除，不在私有目录留孤儿
        extraction.items.drop(accepted.size).forEach { fileStore.deleteFile(it.file.absolutePath) }

        if (accepted.isEmpty()) {
            return ImportOutcome(
                skipped = extraction.skipped,
                duplicates = extraction.duplicates,
                manifestPath = extraction.appFilterPath,
                truncated = truncated,
            )
        }

        val slots = allocateSlots(used, accepted.size)
        val sources = accepted.map {
            ImportedSource(
                path = it.file.absolutePath,
                fileName = it.fileName,
                matchKey = it.matchKey,
            )
        }
        return insertAndBind(
            packId = packId,
            slots = slots,
            sources = sources,
            skipped = extraction.skipped,
            duplicates = extraction.duplicates,
            manifestEntries = manifestEntries,
            manifestPath = extraction.appFilterPath,
            truncated = truncated,
        )
    }

    /**
     * 删除图标槽位：**连同磁盘文件一起删**。
     *
     * 此前只删数据库行与映射，文件永远留在 `icons/<packId>/`——用户在界面上删除的图标
     * 其实一直占着空间（真机实测残留了一张 3.3MB 的相机原图）。删除是不可逆操作，
     * 记录与文件必须同生共死，否则私有目录只增不减。
     */
    suspend fun deleteIcon(packId: Long, slot: Int) {
        assetDao.get(packId, slot)?.let { asset ->
            fileStore.deleteFile(asset.sourcePath)
            asset.editedPath?.let { fileStore.deleteFile(it) }
        }
        assetDao.delete(packId, slot)
        mappingDao.getByPack(packId)
            .filter { it.slot == slot }
            .forEach { mappingDao.delete(packId, it.component) }
        touchPack(packId)
    }

    // ---- 应用映射（FR-04） ----

    /**
     * 写入一条映射（同组件覆盖即替换）。
     *
     * @return 该组件**原先**的槽位；null 表示此前没有映射。
     *  用于区分"新增一条"与"把已有映射换到另一张图标上"——
     *  后者在界面上表现为"少了一张图标的映射"，不说清就是悬案。
     *  与 [remapComponent]（改目标、保槽位）互为反向操作。
     */
    suspend fun setMapping(packId: Long, component: String, slot: Int): Int? {
        val previous = mappingDao.get(packId, component)?.slot
        mappingDao.upsert(MappingEntity(packId = packId, component = component, slot = slot))
        touchPack(packId)
        return previous
    }

    suspend fun removeMapping(packId: Long, component: String) {
        mappingDao.delete(packId, component)
        touchPack(packId)
    }

    /**
     * 改写既有映射的目标组件（FR-04「编辑映射包名」），槽位不变。
     *
     * 为什么不落成"先删后增"：两步之间会短暂丢失映射，且若新目标已存在，
     * 删除顺序不同会得到不同结果。这里先落新目标再删旧目标，任意时刻都至少有一条有效映射。
     *
     * 唯一的冲突是新的目标组件**已经有映射**（例如两个项目习惯不同、或作者把
     * 别名 Activity 与主 Activity 都写了进来）——此时后者被顶掉，返回值把它报出去，
     * 让用户知道"少了一条"是这次改写的结果，而不是自己看花了眼。
     *
     * @return 被顶掉的既有映射的组件名；null 表示没有冲突
     */
    suspend fun remapComponent(packId: Long, from: String, to: String, slot: Int): String? {
        if (from == to) return null
        val displaced = mappingDao.getByPack(packId)
            .firstOrNull { it.component == to }
            ?.component
        mappingDao.upsert(MappingEntity(packId = packId, component = to, slot = slot))
        mappingDao.delete(packId, from)
        touchPack(packId)
        return displaced
    }

    /**
     * 校准预置映射的组件名（见 [MappingReconciler]），返回改动条数。
     *
     * 导入时未安装的应用只能沿用图标包作者写的组件名，而设备上的真实启动 Activity
     * 可能与之不同。应用装上后不校准，这条映射就永远匹配不上——图标在包里、桌面上却不生效。
     *
     * 两个调用时机缺一不可：
     * - 映射页刷新时：用户一进页面就能看到校准结果，并收到提示；
     * - 打包前：用户可能在别处装了新应用却没打开过映射页，直接打包也必须带上校准后的组件名。
     */
    suspend fun reconcileMappings(packId: Long): Int {
        val mappings = mappingDao.getByPack(packId)
        if (mappings.isEmpty()) return 0
        val apps = appInfoProvider.loadLaunchableApps().map {
            ImportBinder.AppItem(it.component, it.packageName)
        }
        val fixes = MappingReconciler.plan(mappings.map { it.component to it.slot }, apps)
        if (fixes.isEmpty()) return 0

        for (fix in fixes) {
            when (fix) {
                is MappingReconciler.Fix.Rebind -> {
                    mappingDao.delete(packId, fix.from)
                    mappingDao.upsert(
                        MappingEntity(packId = packId, component = fix.to, slot = fix.slot),
                    )
                }
                is MappingReconciler.Fix.Drop -> mappingDao.delete(packId, fix.component)
            }
        }
        touchPack(packId)
        return fixes.size
    }

    /**
     * 统计"目标未安装且组件名缺活动类"的映射数（打包页提示用，见 BuildScreen 警告卡）。
     *
     * appfilter 的 component 是 `ComponentInfo{包名/活动类}`；部分图标包源数据写成
     * `ComponentInfo{包名/}`（斜杠后为空）。标准解析（ComponentName.unflattenFromString）
     * 对空类名返回 null，Kvaesitso 源码即按此整条跳过；实测 ColorOS 14 同样把这种
     * 条目视为无效，表现为"图标包没有任何可以替换的图标"。
     * 本机已安装的应用会被 [MappingReconciler] 校准成真实启动组件；仍未安装的只能
     * 原样保留——打包页必须把数量告诉用户，否则"映射几十条、桌面一条不生效"
     * 几乎无从排查（本项目实测踩过：71 条映射 69 条 `pkg/` 且全部未安装）。
     */
    suspend fun incompleteMappingCount(packId: Long): Int {
        val mappings = mappingDao.getByPack(packId)
        if (mappings.isEmpty()) return 0
        val installed = appInfoProvider.loadLaunchableApps().mapTo(HashSet()) { it.packageName }
        return mappings.count { m ->
            val pkg = IconPackManifest.packageOfComponent(m.component) ?: return@count false
            if (pkg in installed) return@count false
            // 空活动类：`ComponentInfo{pkg/}` 的斜杠后没有内容
            m.component.substringAfter('/').removeSuffix("}").isEmpty()
        }
    }

    /** 一键自动匹配（仅填充未手动映射的应用）；返回新增映射数 */
    suspend fun autoMatch(packId: Long): Int {
        val apps = appInfoProvider.loadLaunchableApps()
        val assets = assetDao.getByPack(packId)
        if (apps.isEmpty() || assets.isEmpty()) return 0
        val existing = mappingDao.getByPack(packId).map { it.component }.toSet()
        val candidates = assets.map { AppMatcher.Candidate(it.slot, it.originalName) }
        val matched = AppMatcher.matchAll(
            entries = apps.map {
                AppMatcher.AppEntryForMatch(it.component, it.label, it.packageName)
            },
            candidates = candidates,
            skipComponents = existing,
        )
        matched.forEach { (component, slot) ->
            mappingDao.upsert(MappingEntity(packId = packId, component = component, slot = slot))
        }
        if (matched.isNotEmpty()) touchPack(packId)
        return matched.size
    }

    // ---- 打包（FR-06） ----

    /**
     * 组装打包请求并提交版本号（FR-06）。
     *
     * 版本号在构建前即落库：若构建失败只是跳过一个号，避免"成功后忘递增导致
     * 无法覆盖安装"（FR-06 验收 4）。用户若显式指定过版本号，这一次就用它，
     * 之后自动回到 +1（见 [PackEntity.nextVersionCode]）。
     */
    suspend fun prepareBuild(packId: Long): PackRequest {
        val pack = packDao.getById(packId) ?: error("项目不存在：$packId")
        // 打包前先校准：用户可能刚装上某个此前只有预置映射的应用，
        // 而映射页未必被打开过——那样打出来的包仍带着匹配不上的旧组件名。
        reconcileMappings(packId)
        val mappings = mappingDao.getByPack(packId)
        require(mappings.isNotEmpty()) { "还没有任何映射，无法打包" }
        val assets = assetDao.getByPack(packId)
        val slotsWithFiles = assets.map { it.slot }.toSet()
        mappings.firstOrNull { it.slot !in slotsWithFiles }?.let {
            error("槽位 ${it.slot}（${it.component}）没有对应图标文件")
        }

        val versionCode = pack.upcomingVersionCode
        packDao.commitVersion(packId, versionCode, System.currentTimeMillis())
        // 启动器图标：已设置则编码字节交给引擎；未设置传 null，沿用模板占位图
        val packIconBytes = pack.iconPath?.let { path ->
            val file = java.io.File(path)
            if (file.isFile) file.readBytes() else null
        }
        return PackRequest(
            packName = pack.name,
            versionCode = versionCode,
            versionName = pack.versionName,
            packIconBytes = packIconBytes,
            showInDrawer = pack.showInDrawer,
            packageName = pack.packageName,
            mappings = mappings.map { IconMapping(it.component, it.slot) },
            // 编辑产物优先于原图（FR-05：非破坏性编辑，原图始终保留）
            icons = assets.map { SlotIcon(it.slot, it.editedPath ?: it.sourcePath) },
            templateApkPath = fileStore.ensureTemplate(),
            outputPath = fileStore.outputApk(packId, versionCode).absolutePath,
            signingKeyProvider = signingKeyProvider,
        )
    }

    /** 打包并标记状态；调用方自行切 IO 线程 */
    suspend fun buildPack(packId: Long, onProgress: PackProgress = { _, _, _ -> }): PackResult {
        val request = prepareBuild(packId)
        return when (val result = apkBuilder.pack(request, onProgress)) {
            is PackResult.Success -> {
                packDao.markPacked(packId, System.currentTimeMillis())
                result
            }
            is PackResult.Failure -> result
        }
    }

    // ---- 图标编辑（FR-05） ----

    suspend fun getIcon(packId: Long, slot: Int): IconAssetEntity? = assetDao.get(packId, slot)

    /** 编码并保存单张编辑产物（FR-05），返回新产物路径 */
    suspend fun saveIconEdit(packId: Long, slot: Int, style: IconStyle): String {
        val icon = assetDao.get(packId, slot) ?: error("图标不存在：pack=$packId slot=$slot")
        val bytes = withContext(Dispatchers.IO) { iconProcessor.encode(icon.sourcePath, style) }
        val path = fileStore.saveEditedIcon(packId, slot, bytes)
        assetDao.updateEdit(packId, slot, path)
        touchPack(packId)
        return path
    }

    /** 保存编辑产物路径（文件由调用方写入） */
    suspend fun updateIconEdit(packId: Long, slot: Int, editedPath: String?) {
        assetDao.updateEdit(packId, slot, editedPath)
        touchPack(packId)
    }

    /** 还原为原图：删除编辑产物文件并清字段 */
    suspend fun clearIconEdit(packId: Long, slot: Int) {
        val icon = assetDao.get(packId, slot) ?: return
        icon.editedPath?.let { fileStore.deleteFile(it) }
        assetDao.updateEdit(packId, slot, null)
        touchPack(packId)
    }

    /**
     * 批量统一形状：对图标从**原图**合成（不叠加既有编辑）。
     *
     * @param overwriteEdited 默认 false，跳过已有编辑产物的图标（`editedPath != null`），
     *  避免把用户逐张调好的样式冲掉——FR-05 要求的是"为所有未设背景的图标应用"。
     * @return (实际应用数, 被跳过数)；进度回调 (当前, 待处理总数)。
     */
    suspend fun batchApplyShape(
        packId: Long,
        style: IconStyle,
        overwriteEdited: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<Int, Int> {
        val all = assetDao.getByPack(packId)
        val targets = if (overwriteEdited) all else all.filter { it.editedPath == null }
        val skipped = all.size - targets.size
        targets.forEachIndexed { index, asset ->
            onProgress(index, targets.size)
            val bytes = withContext(Dispatchers.IO) { iconProcessor.encode(asset.sourcePath, style) }
            val path = fileStore.saveEditedIcon(packId, asset.slot, bytes)
            assetDao.updateEdit(packId, asset.slot, path)
        }
        onProgress(targets.size, targets.size)
        if (targets.isNotEmpty()) touchPack(packId)
        return targets.size to skipped
    }

    // ---- 产物管理（FR-08） ----

    /** 项目最新产物路径（无则 null） */
    fun latestArtifactPath(packId: Long): String? = fileStore.latestArtifact(packId)?.absolutePath

    /** 导出最新产物到用户选定 Uri；返回写入字节数 */
    suspend fun exportLatestArtifact(packId: Long, target: Uri): Long {
        val artifact = fileStore.latestArtifact(packId) ?: error("没有可导出的产物，请先打包")
        return fileStore.exportArtifact(artifact, target)
    }

    // ---- 存量维护 ----

    /**
     * 压缩项目内**已落盘**的导入原图，返回实际被压缩的张数。
     *
     * 压缩只在导入路径生效，升级前导入的原图不会被追溯——相机原图 3.3MB/张，
     * 存量不清理的话"压缩"对老用户等于没发生。本方法幂等：已达标的图片原样返回，
     * 重复调用只是几次 bounds 解码。
     *
     * 文件名可能从 `.jpg` 变为 `.webp`，因此必须把新路径写回数据库，
     * 否则打包与编辑都会找不到文件。
     */
    suspend fun compressImportedSources(packId: Long): Int {
        val assets = assetDao.getByPack(packId)
        var compressed = 0
        assets.forEach { asset ->
            val file = java.io.File(asset.sourcePath)
            if (!file.isFile) return@forEach
            val result = fileStore.compressImported(file)
            if (result.absolutePath != asset.sourcePath) {
                assetDao.updateSource(packId, asset.slot, result.absolutePath)
                compressed++
            }
        }
        return compressed
    }

    /** 全部项目的存量原图压缩（应用启动后台执行一次，失败不打扰用户） */
    suspend fun compressAllImportedSources(): Int =
        packDao.getAll().sumOf { compressImportedSources(it.id) }

    /**
     * 清理项目内无人引用的残留文件，返回 (删除文件数, 释放字节数)。
     *
     * 存量泄漏的补救：[deleteIcon] 历史上只删数据库行，磁盘文件会一直留存。
     * 判定口径是"数据库里没有任何记录指向它"——原图、编辑产物、图标包自身图标
     * 三类引用都算数，只有三类都不认的文件才会被删。
     */
    suspend fun cleanupOrphanIcons(packId: Long): Pair<Int, Long> {
        val referenced = HashSet<String>()
        assetDao.getByPack(packId).forEach { asset ->
            referenced.add(asset.sourcePath)
            asset.editedPath?.let { referenced.add(it) }
        }
        packDao.getById(packId)?.iconPath?.let { referenced.add(it) }
        return fileStore.deleteOrphans(packId, referenced)
    }

    /** 全部项目的孤儿文件清理（应用启动后台执行一次） */
    suspend fun cleanupAllOrphanIcons(): Pair<Int, Long> =
        packDao.getAll().fold(0 to 0L) { acc, pack ->
            val (count, bytes) = cleanupOrphanIcons(pack.id)
            (acc.first + count) to (acc.second + bytes)
        }

    // ---- 内部 ----

    /** 一次导入中每个图标的最小信息（两个通道共用后半段） */
    private data class ImportedSource(val path: String, val fileName: String, val matchKey: String)

    /**
     * 入池并自动绑定映射（两个导入通道的公共尾部）。
     *
     * `originalName` 落的是 [ImportedSource.matchKey] 而不是原始文件名——它是"当前可得的最佳匹配键"：
     * 文件名叫包名时就是包名，目录是包名时就是目录名，都不成立才退回文件名。
     * 这既让自动匹配有的放矢，也让图标库里的名称更有意义（显示 `com.tencent.mm` 而不是 `icon`）。
     */
    private suspend fun insertAndBind(
        packId: Long,
        slots: List<Int>,
        sources: List<ImportedSource>,
        skipped: Int = 0,
        duplicates: Int = 0,
        manifestEntries: List<IconPackManifest.Entry> = emptyList(),
        manifestPath: String? = null,
        truncated: Int = 0,
    ): ImportOutcome {
        val now = System.currentTimeMillis()
        assetDao.insertAll(
            sources.mapIndexed { index, source ->
                IconAssetEntity(
                    packId = packId,
                    slot = slots[index],
                    sourcePath = source.path,
                    originalName = source.matchKey,
                    addedAt = now,
                )
            },
        )

        // 已存在的映射一律让位：自动绑定绝不覆盖用户的人工指派
        val existing = mappingDao.getByPack(packId).map { it.component }.toSet()
        val bindings = ImportBinder.bind(
            manifestEntries = manifestEntries,
            icons = sources.mapIndexed { index, source ->
                ImportBinder.IconItem(index, source.fileName, source.matchKey)
            },
            apps = appInfoProvider.loadLaunchableApps().map {
                ImportBinder.AppItem(it.component, it.packageName)
            },
            skipComponents = existing,
        )
        bindings.forEach { binding ->
            mappingDao.upsert(
                MappingEntity(
                    packId = packId,
                    component = binding.component,
                    slot = slots[binding.iconIndex],
                ),
            )
        }

        touchPack(packId)
        return ImportOutcome(
            imported = sources.size,
            manifestMapped = bindings.count { it.source == ImportBinder.Source.MANIFEST },
            // 预置映射只可能来自 L1（L2 需要设备上的真实启动组件，未安装时无从得知）
            presetMapped = bindings.count { !it.installed },
            packageMapped = bindings.count { it.source == ImportBinder.Source.PACKAGE_NAME },
            skipped = skipped,
            duplicates = duplicates,
            manifestPath = manifestPath,
            truncated = truncated,
        )
    }

    /** 已占用的槽位集合 */
    private suspend fun usedSlots(packId: Long): MutableSet<Int> =
        assetDao.getByPack(packId).map { it.slot }.toSortedSet()

    /** 剩余可分配槽位数 */
    private fun availableSlots(used: Set<Int>): Int = (MAX_SLOTS - used.size).coerceAtLeast(0)

    /** 在 1..1024 中找最小空闲槽位（含历史空洞回收）；[used] 会被就地更新 */
    private fun allocateSlots(used: MutableSet<Int>, count: Int): List<Int> {
        val result = ArrayList<Int>(count)
        var candidate = 1
        while (result.size < count) {
            if (candidate > MAX_SLOTS) error("图标池已满（上限 $MAX_SLOTS 个）")
            if (candidate !in used) {
                result.add(candidate)
                used.add(candidate)
            }
            candidate++
        }
        return result
    }

    private suspend fun touchPack(packId: Long) {
        packDao.getById(packId)?.let { pack ->
            packDao.update(pack.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    companion object {
        const val MAX_SLOTS = 1024

        /**
         * 启动器图标在落盘时借用的槽位号（仅用于临时文件命名，不占真实槽位）。
         * 取 MAX_SLOTS + 1，绝不会与 1..1024 的图标槽位冲突。
         */
        private const val PACK_ICON_SLOT = MAX_SLOTS + 1
    }
}
