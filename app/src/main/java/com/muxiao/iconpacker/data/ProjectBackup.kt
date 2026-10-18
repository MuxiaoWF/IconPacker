package com.muxiao.iconpacker.data

import android.content.Context
import android.net.Uri
import com.muxiao.iconpacker.core.packer.engine.PackageNameRules
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 项目备份：把单个图标包项目（配置 + 图标池 + 映射）打包成可迁移的 ZIP（v8）。
 *
 * 动机：应用私有目录随卸载清空（docs/README.md），换机/重装意味着一切重头再来。
 * 备份 ZIP 是把"配置 + 图标文件"作为一个原子单元搬走/搬回的唯一通道——两个通道
 * 各自搬一半（只导图标不导映射，或只导配置不导图标）都要用户手工拼，必错。
 *
 * ZIP 布局：
 * ```
 * project.json            项目元数据（pack 字段 + assets 清单 + mappings）
 * pack_icon.png           图标包自身启动器图标（未设置时无此条目）
 * icons/<slot>/source.ext 原图（按磁盘上的真实扩展名）
 * icons/<slot>/edited.webp 编辑产物（仅编辑过该槽位时存在）
 * artifact.apk            最新产物 APK（仅 v2「完整备份」携带；密钥库仍不随包）
 * ```
 * `formatVersion=1` 数据备份 / `formatVersion=2` 完整备份（多了 artifact.apk）。
 * 导入同时接受两版：v2 且条目存在时按 project.json 里的 versionCode 还原产物。
 *
 * 边界（隐私与安全）：
 * - **密钥库文件与密码不进 ZIP**。密钥库是跨项目共享资产且含密码；导出的
 *  备份常被分享/云端存放，签名身份不能随之泄出。导入项目一律回退到设备密钥
 *  （signerName 与方案开关保留），用户需要同签名身份时手动补导密钥库。
 * - **originalName 原样保留**：它是自动匹配（AppMatcher / ImportBinder）与
 *  图标库显示的输入，任何转写都会让重新导入后的匹配变差。
 * - 解析只认 `formatVersion=1`；未来布局变更时新版本号 + 旧版独立解析分支，
 *  绝不静默猜格式。
 */
class ProjectBackup(
    private val packDao: PackDao,
    private val mappingDao: MappingDao,
    private val assetDao: IconAssetDao,
    private val fileStore: IconFileStore,
    private val context: Context,
) {

    /**
     * 导出项目到用户选定 Uri；返回写入字节数。
     *
     * [includeArtifact] 为 true 时升级为 v2 完整备份：最新产物 APK 随包走，
     * 换机后可直装、免重打。产物不存在（从未打包/已被裁剪）时直接报错——
     * 用户明确要求"带产物"却拿不到，静默降级成数据备份会让他误以为备份是完整的。
     */
    suspend fun exportProject(
        packId: Long,
        target: Uri,
        includeArtifact: Boolean = false,
    ): Long = withContext(Dispatchers.IO) {
        val pack = packDao.getById(packId) ?: error("项目不存在：$packId")
        val artifact = if (includeArtifact) {
            fileStore.latestArtifact(packId) ?: error("该项目还没有产物，无法完整备份")
        } else {
            null
        }
        val assets = assetDao.getByPack(packId)
        val mappings = mappingDao.getByPack(packId)

        // 原图文件先做存在性检查再动 ZIP：写一半失败留给用户的是个残废文件
        assets.forEach { asset ->
            require(File(asset.sourcePath).isFile) { "原图文件缺失：${asset.sourcePath}" }
        }

        var written = 0L
        context.contentResolver.openOutputStream(target)?.use { out ->
            ZipOutputStream(out.buffered(IO_BUFFER)).use { zip ->
                val packJson = JSONObject()
                    .put("name", pack.name)
                    .put("versionCode", pack.versionCode)
                    .put("versionName", pack.versionName)
                    .put("showInDrawer", pack.showInDrawer)
                    .put("packageName", pack.packageName)
                    .put("nextVersionCode", pack.nextVersionCode ?: JSONObject.NULL)
                    .put("lastPackedAt", pack.lastPackedAt ?: JSONObject.NULL)
                    .put("signerName", pack.signerName ?: JSONObject.NULL)
                    .put("signSchemeV1", pack.signSchemeV1)
                    .put("signSchemeV2", pack.signSchemeV2)
                    .put("signSchemeV3", pack.signSchemeV3)
                    .put("minSdkOverride", pack.minSdkOverride)
                    .put("targetSdkOverride", pack.targetSdkOverride)
                val json = JSONObject()
                    .put(FORMAT_VERSION, if (artifact != null) FORMAT_VERSION_FULL else FORMAT_VERSION_DATA)
                    .put("pack", packJson)
                val assetsJson = JSONArray()
                assets.forEach { asset ->
                    val slotDir = "icons/${asset.slot}"
                    val ext = asset.sourcePath.substringAfterLast('.', "img")
                    val zipSource = zip.writeFileEntry("$slotDir/source.$ext", File(asset.sourcePath))
                    val editedPath = asset.editedPath?.takeIf { File(it).isFile }
                    val zipEdited = editedPath?.let { zip.writeFileEntry("$slotDir/edited.webp", File(it)) }
                    assetsJson.put(
                        JSONObject()
                            .put("slot", asset.slot)
                            .put("originalName", asset.originalName)
                            .put("sourceEntry", zipSource)
                            .put("editedEntry", zipEdited ?: JSONObject.NULL),
                    )
                }
                json.put("assets", assetsJson)
                json.put(
                    "mappings",
                    JSONArray().apply {
                        mappings.forEach { mapping ->
                            put(
                                JSONObject()
                                    .put("component", mapping.component)
                                    .put("slot", mapping.slot),
                            )
                        }
                    },
                )
                zip.putNextEntry(ZipEntry("project.json"))
                json.toString(2).toByteArray(Charsets.UTF_8).let { bytes ->
                    zip.write(bytes)
                    written += bytes.size
                }
                zip.closeEntry()

                pack.iconPath?.takeIf { File(it).isFile }?.let { path ->
                    zip.putNextEntry(ZipEntry("pack_icon.png"))
                    File(path).inputStream().buffered(IO_BUFFER).use { input ->
                        input.copyTo(zip, IO_BUFFER).also { written += it }
                    }
                    zip.closeEntry()
                }

                // v2 完整备份：产物固定名 artifact.apk，版本号已在 project.json 里，
                // 导入时按它还原成 pack-v<N>.apk（不猜测条目名、不做路径拼接）
                artifact?.let { apk ->
                    zip.putNextEntry(ZipEntry(ARTIFACT_ENTRY))
                    apk.inputStream().buffered(IO_BUFFER).use { input ->
                        input.copyTo(zip, IO_BUFFER).also { written += it }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("无法打开导出目标")
        written
    }

    /** 写文件条目并返回条目名（JSON 里记录，导入按下表解包，避免路径重猜） */
    private fun ZipOutputStream.writeFileEntry(entryName: String, source: File): String {
        putNextEntry(ZipEntry(entryName))
        val copied = source.inputStream().buffered(IO_BUFFER).use { input ->
            input.copyTo(this, IO_BUFFER)
        }
        closeEntry()
        if (copied != source.length()) error("图标条目写出不完整：$entryName")
        return entryName
    }

    /**
     * 从用户选定 Uri 导入项目，返回新项目 id。
     *
     * 包名冲突回退：同包名产物在系统里互相覆盖（见 PackRepository.setPackageName），
     * 备份若原样带回旧包名并恰与既有项目撞车，导入的产物会悄悄覆盖已有项目——
     * 回退到按新 id 派生，并把这个决定交给界面提示。
     *
     * 签名配置移植（v8）："用谁的密钥"无法跟 ZIP 走（见类注释），导入一律落回
     * 设备密钥；signerName 与方案开关原样保留。
     */
    suspend fun importProject(uri: Uri): Long = withContext(Dispatchers.IO) {
        val staged = File(context.cacheDir, "backup-import.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            staged.outputStream().buffered(IO_BUFFER).use { output ->
                input.copyTo(output, IO_BUFFER)
            }
        } ?: error("无法读取 ZIP：$uri")
        try {
            ZipFile(staged).use { zip ->
                val jsonText = zip.getInputStream(
                    zip.getEntry(JSON_ENTRY) ?: error("不是本项目备份：缺少 project.json"),
                ).readBytes().toString(Charsets.UTF_8)
                val json = JSONObject(jsonText)
                val version = json.optInt(FORMAT_VERSION, -1)
                require(version in FORMAT_VERSION_DATA..FORMAT_VERSION_FULL) {
                    "备份版本不支持（formatVersion=$version）"
                }

                val packJson = json.getJSONObject("pack")
                val now = System.currentTimeMillis()
                val basePack = PackEntity(
                    name = packJson.getString("name"),
                    versionCode = packJson.optInt("versionCode", 1),
                    versionName = packJson.optString("versionName", "1.0"),
                    createdAt = now,
                    updatedAt = now,
                    lastPackedAt = packJson.optLong("lastPackedAt", 0).takeIf { it > 0 },
                    showInDrawer = packJson.optBoolean("showInDrawer", true),
                    signerName = packJson.optString("signerName", "").takeIf { it.isNotEmpty() },
                    signSchemeV1 = packJson.optBoolean("signSchemeV1", true),
                    signSchemeV2 = packJson.optBoolean("signSchemeV2", true),
                    signSchemeV3 = packJson.optBoolean("signSchemeV3", true),
                    minSdkOverride = packJson.optInt("minSdkOverride", 0),
                    targetSdkOverride = packJson.optInt("targetSdkOverride", 0),
                )

                val customName = packJson.optString("packageName", "").takeIf { it.isNotEmpty() }
                    ?.let { pkg ->
                        if (PackageNameRules.validate(pkg) != null) null else pkg
                    }
                    ?.takeIf { pkg -> packDao.findOtherWithPackageName(pkg, -1L) == null }
                val newId = packDao.insertWithPackage(basePack, now, customName)

                copyPackIcon(zip, newId, now)

                // v2 完整备份：把随包产物按备份里的版本号还原到新项目的 outputs，
                // 使其直接出现在产物历史里（重装/导出不必重新打包）
                if (version >= FORMAT_VERSION_FULL) {
                    restoreArtifact(zip, newId, basePack.versionCode)
                }

                // 逐条 insertAll 等于"每个图标一次事务"，300 张图标就是 300 次磁盘提交——
                // 这是备份导入慢的另一半原因。先攒成一批，收尾各写一次。
                val candidateIds = HashSet<Int>()
                val assetRows = ArrayList<IconAssetEntity>(json.getJSONArray("assets").length())
                json.getJSONArray("assets").forEach { raw ->
                    val assetJson = raw as JSONObject
                    val slot = assetJson.getInt("slot")
                    val sourceEntry = assetJson.optString("sourceEntry")
                    val destSlotDir = fileStore.iconsDir(newId)
                    // 落盘名遵循既有约定 `<slot>__<seq>__<名>`（孤儿清理按前缀识别自有文件）；
                    // 同目录平铺多个槽位的同名条目（source.webp）会互撞，前缀同时消歧
                    val sourcePath = unzipTo(zip, sourceEntry, slot, destSlotDir)
                        ?: error("备份缺少原图条目 $sourceEntry")
                    val editedPath = assetJson.optString("editedEntry", "")
                        .takeIf { it.isNotEmpty() }?.let { unzipTo(zip, it, slot, destSlotDir) }
                    assetRows += IconAssetEntity(
                        packId = newId,
                        slot = slot,
                        sourcePath = sourcePath,
                        originalName = assetJson.getString("originalName"),
                        addedAt = now,
                        editedPath = editedPath,
                    )
                    candidateIds.add(slot)
                }
                if (assetRows.isNotEmpty()) assetDao.insertAll(assetRows)

                val mappingRows = ArrayList<MappingEntity>()
                json.getJSONArray("mappings").forEach { raw ->
                    val mappingJson = raw as JSONObject
                    val slot = mappingJson.getInt("slot")
                    val component = mappingJson.getString("component")
                    // 映射指向落不进来的槽位：保留映射只会打包出灾难（缺图标中断），指向目标无效
                    if (slot in candidateIds) {
                        mappingRows += MappingEntity(packId = newId, component = component, slot = slot)
                    }
                }
                if (mappingRows.isNotEmpty()) mappingDao.insertAll(mappingRows)
                newId
            }
        } finally {
            staged.delete()
        }
    }

    private suspend fun copyPackIcon(zip: ZipFile, newId: Long, now: Long) {
        val entry = zip.getEntry(PACK_ICON_ENTRY) ?: return
        val target = File(fileStore.iconsDir(newId), "pack_icon.png")
        target.outputStream().buffered(IO_BUFFER).use { output ->
            zip.getInputStream(entry).use { input -> input.copyTo(output, IO_BUFFER) }
        }
        packDao.setIcon(newId, target.absolutePath, now)
    }

    /** v2：还原随包产物到新项目的私有目录（命名与打包产物一致：pack-v&lt;N&gt;.apk） */
    private fun restoreArtifact(zip: ZipFile, newId: Long, versionCode: Int) {
        val entry = zip.getEntry(ARTIFACT_ENTRY) ?: return
        val target = fileStore.outputApk(newId, versionCode)
        target.outputStream().buffered(IO_BUFFER).use { output ->
            zip.getInputStream(entry).use { input -> input.copyTo(output, IO_BUFFER) }
        }
    }

    /** 解包一条目到目录（落盘名 `<slot>__0__<basename>`），返回新路径；条目缺失返回 null */
    private fun unzipTo(zip: ZipFile, entryName: String, slot: Int, destDir: File): String? {
        val entry = zip.getEntry(entryName) ?: return null
        // 条目名由导出流程生成，此处只取 basename，杜绝 path traversal
        val base = entryName.substringAfterLast('/')
        val target = File(destDir, safeDiskName("${slot}__0__$base"))
        target.outputStream().buffered(IO_BUFFER).use { output ->
            zip.getInputStream(entry).use { input -> input.copyTo(output, IO_BUFFER) }
        }
        return target.absolutePath
    }

    private fun safeDiskName(raw: String): String =
        raw.map { ch ->
            if (ch.isLetterOrDigit() || ch in "._-") ch else '_'
        }.joinToString("").takeLast(96).ifBlank { "icon" }

    private inline fun JSONArray.forEach(block: (Any) -> Unit) {
        for (i in 0 until length()) block(get(i))
    }

    companion object {
        private const val FORMAT_VERSION = "formatVersion"
        private const val JSON_ENTRY = "project.json"
        private const val PACK_ICON_ENTRY = "pack_icon.png"
        private const val ARTIFACT_ENTRY = "artifact.apk"

        /** v1：数据备份（配置 + 图标池 + 映射） */
        const val FORMAT_VERSION_DATA = 1

        /** v2：完整备份（额外携带最新产物 APK） */
        const val FORMAT_VERSION_FULL = 2

        /** ZIP 条目搬运缓冲区：默认 8KB 在几百张图标时会多出几十万次小读 */
        private const val IO_BUFFER = 64 * 1024
    }
}
