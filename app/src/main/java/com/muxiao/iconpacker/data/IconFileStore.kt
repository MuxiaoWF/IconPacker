package com.muxiao.iconpacker.data

import android.content.Context
import android.net.Uri
import com.muxiao.iconpacker.core.packer.engine.ImportCompressor
import com.muxiao.iconpacker.core.packer.engine.ZipIconPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/**
 * 应用私有文件布局管理。
 *
 * ```
 * files/
 * ├── icons/<packId>/<slot>__<原始名> 导入的图标原件（导入时压缩，见 ImportCompressor）
 * ├── template/template.apk               从 assets 拷贝的模板（首次打包时）
 * ├── keystores/ks_<ts>.keystore          用户导入的签名密钥库（跨项目共享，v7）
 * └── outputs/<packId>/pack-v<N>.apk      打包产物
 * ```
 * 全部位于应用私有目录：卸载即清除（docs/README.md），无需任何存储权限。
 *
 * **导入即压缩**：相机原图按原样落盘会让私有目录迅速膨胀（3.3MB/张，100 张即 300MB），
 * 而打包产物只用 192px、编辑预览按 1080 采样——多余的分辨率对最终图标没有任何贡献。
 * 压缩按内容判断格式（不透明 → WebP，含透明 → PNG），压不动就保留原文件，
 * 详见 [ImportCompressor]。
 *
 * **ZIP 解析**：保留条目原始路径、合并多密度副本、顺带取出包内 appfilter（见
 * [extractZipImages]）。这三件事是"导入即建立映射"的前提——路径与 appfilter 都携带
 * 映射所需的信息，丢了就只能靠文件名猜。
 */
class IconFileStore(
    private val context: Context,
    private val importCompressor: ImportCompressor = ImportCompressor(),
) {

    private val filesRoot: File get() = context.filesDir

    fun iconsDir(packId: Long): File = File(filesRoot, "icons/$packId").apply { mkdirs() }

    /**
     * 用户导入的签名密钥库目录。
     *
     * 与 icons/ 分开：密钥库**跨项目共享**（复制项目引用同一路径），生命周期
     * 由"是否还有项目引用"决定（见 PackRepository 的孤儿清理），不能按项目目录组织。
     */
    fun keystoresDir(): File = File(filesRoot, "keystores").apply { mkdirs() }

    fun templateApk(): File = File(filesRoot, "template/template.apk")

    fun outputDir(packId: Long): File = File(filesRoot, "outputs/$packId").apply { mkdirs() }

    fun outputApk(packId: Long, versionCode: Int): File =
        File(outputDir(packId), "pack-v$versionCode.apk")

    /**
     * 项目最新产物（版本号最大者）；不存在返回 null。
     *
     * 按**解析出的版本号**取最大，不能按文件名字符串比较：`maxBy { it.name }` 会认为
     * `pack-v9.apk` 比 `pack-v10.apk` 新（逐字符比 `9` > `1`）。用户既然能自定义版本号，
     * 这种错序就更容易撞上——导出与"我的图标包"列表会拿到旧产物。解析不出来（历史遗留
     * 文件名）的兜底为 0，仍可参与比较、只是排最后。
     */
    fun latestArtifact(packId: Long): File? =
        outputDir(packId)
            .listFiles { file -> file.name.startsWith("pack-v") && file.name.endsWith(".apk") }
            ?.maxByOrNull { versionCodeOfArtifact(it.name) }

    /** 从 `pack-v<N>.apk` 解析版本号；解析不出返回 0 */
    private fun versionCodeOfArtifact(fileName: String): Long =
        fileName.removePrefix("pack-v")
            .removeSuffix(".apk")
            .substringBefore('.')
            .toLongOrNull() ?: 0L

    /** 导出产物到用户选定的 Uri（SAF，无需存储权限）；返回写入字节数 */
    suspend fun exportArtifact(source: File, target: Uri): Long =
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(target)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: error("无法打开导出目标")
            source.length()
        }

    /**
     * 把照片选择器返回的 Uri 拷贝为私有文件，返回存储路径。
     * 文件名带槽位前缀与序号，保证同槽位重复导入可区分。
     *
     * 落地后立即压缩（可能改扩展名为 .webp）——调用方必须使用**返回的路径**落库，
     * 不能自己拼原名。
     */
    suspend fun copyImportedIcon(packId: Long, slot: Int, seq: Int, uri: Uri, displayName: String): String =
        withContext(Dispatchers.IO) {
            val safeName = sanitizeName(displayName)
            val target = File(iconsDir(packId), "${slot}__${seq}__$safeName")
            context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件：$uri")
            importCompressor.compressInPlace(target).absolutePath
        }

    /**
     * 解包 ZIP 中的图片文件，返回规划后的条目、跳过数、去重数与包内 appfilter 字节。
     *
     * 与原实现的四处差别（都源于真机实测的问题）：
     * 1. **保留原始条目路径**——`com.tencent.mm/icon.png` 的目录名是包名，是最可靠的匹配键，
     *  只取 basename 会让两个不同 App 的图标双双变成 `icon`；
     * 2. **多密度副本去重**——`drawable-{mdpi..xxxhdpi}/chrome.png` 只是同一张图的不同密度，
     *  原实现会把同一张图导入 5 次、占 5 个槽位（实测 3 图标 × 5 密度 = 17 → 4）；
     * 3. **顺带收集 appfilter.xml**（二进制 AXML 或明文），它是包作者写下的权威映射表；
     * 4. **只压缩最终保留的文件**——去重掉的那批连编码都不必做。
     *
     * 非图片条目只计数不落盘，调用方据此提示用户（FR-03 验收标准 1）。
     */
    suspend fun extractZipImages(packId: Long, zipFile: File): ZipExtraction =
        withContext(Dispatchers.IO) {
            val dest = iconsDir(packId)
            val staged = ArrayList<StagedEntry>()
            var skipped = 0
            var seq = 0
            var appFilterBytes: ByteArray? = null
            var appFilterPath: String? = null
            var appFilterRank = 0

            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val rank = appFilterRankOf(name)
                    when {
                        entry.isDirectory -> Unit
                        isImageName(name) -> {
                            val target = File(dest, "zip__${seq}__${sanitizeName(name)}")
                            target.outputStream().use { out -> zis.copyTo(out) }
                            seq++
                            if (target.length() > MAX_IMAGE_BYTES) {
                                target.delete()
                                skipped++
                            } else {
                                staged += StagedEntry(target, name)
                            }
                        }
                        // 优先 res/xml/appfilter.xml；同等级只认第一个
                        rank > appFilterRank -> {
                            appFilterBytes = zis.readCapped()
                            appFilterPath = name
                            appFilterRank = rank
                        }
                        else -> skipped++
                    }
                    entry = zis.nextEntry
                }
            }

            val plan = ZipIconPlanner.plan(
                staged.map { ZipIconPlanner.Entry(it.path, it.file.length()) },
            )
            val plannedByPath = plan.kept.associateBy { it.path }
            val consumed = HashSet<String>()
            val items = ArrayList<ZipImageItem>(plan.kept.size)
            for (candidate in staged) {
                val planned = plannedByPath[candidate.path]
                // 未入选（多密度副本）或同路径重复出现：删除，不在私有目录留孤儿
                if (planned == null || !consumed.add(candidate.path)) {
                    candidate.file.delete()
                    continue
                }
                val stored = importCompressor.compressInPlace(candidate.file)
                items += ZipImageItem(
                    file = stored,
                    entryPath = planned.path,
                    fileName = planned.fileName,
                    matchKey = planned.matchKey,
                )
            }
            ZipExtraction(
                items = items,
                skipped = skipped,
                duplicates = plan.duplicates,
                appFilterBytes = appFilterBytes,
                appFilterPath = appFilterPath,
            )
        }

    /** 把用户通过文件分享/下载得到的 ZIP 先落地再解包 */
    suspend fun importZipFromUri(packId: Long, uri: Uri): ZipExtraction =
        withContext(Dispatchers.IO) {
            val staged = File(iconsDir(packId), STAGED_ZIP_NAME)
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { out -> input.copyTo(out) }
            } ?: error("无法读取 ZIP：$uri")
            extractZipImages(packId, staged).also { staged.delete() }
        }

    /**
     * 把模板 APK：assets → files。
     *
     * 旧逻辑只在文件不存在时拷贝；这意味着覆盖安装（adb install -r）后，旧模板
     * 一直留在 files 目录、新引擎（assets 里的 template.apk）不会生效，用户被迫
     * 卸载重装——而卸载会清空应用私有数据（Room DB + 图标文件），即"草稿消失"的根因。
     *
     * 改为按**内容 CRC** 刷新：仅当 assets 模板与磁盘现有文件不一致（首次 / 模板更新）
     * 才重写，使覆盖安装即可拿到新模板，无需卸载，从而保住草稿。
     */
    suspend fun ensureTemplate(): String = withContext(Dispatchers.IO) {
        val target = templateApk()
        val assetBytes = context.assets.open(TEMPLATE_ASSET).readBytes()
        val expectedCrc = CRC32().apply { update(assetBytes) }.value
        val currentCrc = if (target.isFile) crc32Of(target) else null
        if (currentCrc != expectedCrc) {
            target.parentFile?.mkdirs()
            target.writeBytes(assetBytes)
        }
        target.absolutePath
    }

    /** 计算文件 CRC32（用于与 assets 模板比对是否一致） */
    private fun crc32Of(file: File): Long = file.inputStream().buffered().use { stream ->
        val crc = CRC32()
        val buf = ByteArray(8192)
        var n: Int
        while (stream.read(buf).also { n = it } != -1) crc.update(buf, 0, n)
        crc.value
    }

    /** 保存编辑产物（FR-05：从原图合成的带样式 WebP，覆盖同槽位旧产物） */
    suspend fun saveEditedIcon(packId: Long, slot: Int, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val target = File(iconsDir(packId), "edit_${slot}.webp")
            target.writeBytes(bytes)
            target.absolutePath
        }

    /**
     * 压缩一张**已落盘**的导入原图，返回压缩后的文件（未压缩则返回原文件）。
     *
     * 供存量数据的一次性清理使用：压缩只在导入路径生效，升级前导入的原图不会被追溯，
     * 需要由 [com.muxiao.iconpacker.data.PackRepository.compressImportedSources] 扫一遍。
     * 幂等：已达标（尺寸与体积都在阈值内）的图片会原样返回，重复调用无副作用。
     */
    suspend fun compressImported(file: File): File = withContext(Dispatchers.IO) {
        importCompressor.compressInPlace(file)
    }

    /**
     * 保存图标包自身的启动器图标（192px PNG 字节，由 IconProcessor.encodeLauncherIcon 编码）。
     * 固定文件名：同一项目只保留一份，重复设置直接覆盖。
     */
    suspend fun savePackIcon(packId: Long, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val target = File(iconsDir(packId), "pack_icon.png")
            target.writeBytes(bytes)
            target.absolutePath
        }

    /** 删除单个文件（还原编辑时清理产物；忽略不存在的路径） */
    suspend fun deleteFile(path: String) {
        withContext(Dispatchers.IO) { File(path).delete() }
    }

    /**
     * 把用户经 SAF 选中的密钥库文件落地到私有目录，返回存储路径。
     *
     * 落地时机：SAF 的 content Uri 只在本次授权内可读，打包是数秒后的
     * 独立流程——必须在用户选文件时就地拷贝，不能把 Uri 存进数据库留到打包。
     * 文件名用时间戳：同一密钥库多项目/多次导入各自成文件，覆盖会破坏"复制项目
     * 引用同一路径"的共享约定。
     */
    suspend fun importKeystore(uri: Uri): String = withContext(Dispatchers.IO) {
        val target = File(keystoresDir(), "ks_${System.currentTimeMillis()}.keystore")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选密钥库文件：$uri")
        target.absolutePath
    }

    /** 删除导入的密钥库文件（仅当已无项目引用时由仓库层调用） */
    suspend fun deleteKeystore(path: String) {
        withContext(Dispatchers.IO) { File(path).delete() }
    }

    /**
     * 删除 `icons/<packId>/` 中未被 [referenced] 引用的文件，返回 (删除数, 释放字节数)。
     *
     * 只删**命名符合本项目约定**的文件（导入件、编辑产物、暂存 ZIP），并且跳过最近
     * [ORPHAN_MIN_AGE_MS] 内改动过的文件——导入是"先落文件、后写数据库"，
     * 中间那一瞬间新文件天然"无人引用"，靠时间窗避免误删正在导入的图。
     */
    suspend fun deleteOrphans(packId: Long, referenced: Set<String>): Pair<Int, Long> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var count = 0
            var freed = 0L
            iconsDir(packId).listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.absolutePath in referenced) return@forEach
                if (!looksManaged(file.name)) return@forEach
                if (now - file.lastModified() < ORPHAN_MIN_AGE_MS) return@forEach
                val size = file.length()
                if (file.delete()) {
                    count++
                    freed += size
                }
            }
            count to freed
        }

    /** 文件名是否符合本项目的落盘约定（避免误删目录里任何非本项目产生的文件） */
    private fun looksManaged(name: String): Boolean =
        name.startsWith("zip__") ||
            name.startsWith("edit_") ||
            name == STAGED_ZIP_NAME ||
            name == "pack_icon.png" ||
            name.substringBefore("__").toIntOrNull() != null

    /** 删除项目相关的全部文件（删项目时调用；数据库记录由 Room 级联删除） */
    suspend fun deletePackFiles(packId: Long) = withContext(Dispatchers.IO) {
        File(filesRoot, "icons/$packId").deleteRecursively()
        File(filesRoot, "outputs/$packId").deleteRecursively()
        Unit
    }

    private fun isImageName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
    }

    /**
     * 条目是否为 appfilter.xml，以及优先级。
     *
     * ZIP 里可能同时存在多份（`res/xml/appfilter.xml` 与根目录的副本），
     * 取优先级最高的那份：`res/xml/` 是图标包规范的正式位置。
     */
    private fun appFilterRankOf(name: String): Int {
        val normalized = name.replace('\\', '/')
        return when {
            normalized.equals("res/xml/appfilter.xml", ignoreCase = true) -> 2
            normalized.substringAfterLast('/').equals("appfilter.xml", ignoreCase = true) -> 1
            else -> 0
        }
    }

    /** 读取至多 [MAX_APP_FILTER_BYTES] 字节：appfilter 是不可信输入，不能无上限地读进内存 */
    private fun InputStream.readCapped(): ByteArray {
        val limit = MAX_APP_FILTER_BYTES
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    /** 已落地但尚未规划（去重）的候选条目 */
    private class StagedEntry(val file: File, val path: String)

    /** 过滤路径分隔符与控制字符，防目录穿越与非法文件名 */
    private fun sanitizeName(raw: String): String {
        val base = File(raw).name.ifBlank { "icon" }
        val cleaned = base.map { ch ->
            when {
                ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_' -> ch
                else -> '_'
            }
        }.joinToString("")
        return cleaned.takeLast(MAX_NAME_LEN).ifBlank { "icon" }
    }

    companion object {
        const val TEMPLATE_ASSET = "template.apk"
        private const val MAX_NAME_LEN = 64

        /**
         * 按全局模板折算导出建议文件名。
         *
         * 占位符：`{name}`=项目名、`{version}`=版本名、`{code}`=版本号。
         * 空白折叠为单个连字符、非法字符替换为下划线，模板再花哨也不会发明出
         * SAF 拒收的文件名；解析不出任何内容时兜底一个非空名（CreateDocument
         * 的建议名不能为空）。
         */
        fun suggestedArtifactName(
            template: String,
            packName: String,
            versionName: String,
            versionCode: Int,
        ): String {
            val raw = template
                .replace("{name}", packName.trim())
                .replace("{version}", versionName.trim())
                .replace("{code}", versionCode.toString())
            val cleaned = raw
                .map { ch -> if (ch in ILLEGAL_NAME_CHARS || ch.isWhitespace()) '_' else ch }
                .joinToString("")
                .replace(Regex("_{2,}"), "_")
            return cleaned.takeLast(MAX_EXPORT_NAME_LEN).ifBlank { "IconPacker.apk" }
        }

        /** 需清洗的文件名字符集：SAF 文档名不允许出现路径分隔符等 */
        private val ILLEGAL_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** SAF 建议名的长度上限 */
        private const val MAX_EXPORT_NAME_LEN = 96

        /** 待解包 ZIP 的暂存文件名 */
        private const val STAGED_ZIP_NAME = "staged-import.zip"

        /** 孤儿文件的最小"年龄"：新文件一律不碰，避免与正在进行的导入抢同一个文件 */
        private const val ORPHAN_MIN_AGE_MS = 5L * 60 * 1000

        /** 单个图片条目的体积上限：超出即跳过，避免异常 ZIP 把导入拖死 */
        private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

        /** appfilter 读取上限（正常图标包的映射表在数百 KB 量级） */
        private const val MAX_APP_FILTER_BYTES = 8 * 1024 * 1024
    }
}
