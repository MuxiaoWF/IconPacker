package com.muxiao.iconpacker.data

import java.io.File

/**
 * 一次 ZIP 解包的结果。
 *
 * 刻意保留原始条目路径（[ZipImageItem.entryPath]）与匹配键：`com.tencent.mm/icon.png`
 * 这种结构里，最可靠的键（包名）就在目录上，而原实现只取 `File(entry.name).name`，
 * 把它整段丢掉了——实测两个不同 App 的图标会双双变成 `icon`，无法区分。
 *
 * 用普通类而非 data class：[appFilterBytes] 是数组，data class 的
 * equals/hashCode 会退化成引用比较，容易误用。
 */
class ZipExtraction(
    /** 规划后保留的图片条目（多密度副本已合并） */
    val items: List<ZipImageItem>,
    /** 非图片（或受支持格式之外的）条目数 */
    val skipped: Int,
    /** 因多密度重复被合并掉的条目数 */
    val duplicates: Int,
    /** 包内 appfilter.xml 的原始字节（二进制 AXML 或明文 XML）；未找到为 null */
    val appFilterBytes: ByteArray?,
    /** appfilter 在 ZIP 中的路径（用于提示用户映射来源） */
    val appFilterPath: String?,
)

/**
 * ZIP 中一个已落地的图标条目。
 *
 * @param file 私有目录中的文件（已压缩，实际扩展名可能与 ZIP 内条目不同）
 * @param entryPath ZIP 内的原始条目路径
 * @param fileName **ZIP 内**条目文件名（不含目录）。appfilter 的 `drawable` 名即
 *  "去扩展名"后的此值，因此它取自条目路径而非落地文件——落地文件可能因压缩
 *  改了扩展名（`.jpg` → `.webp`），那与映射关系无关
 * @param matchKey 推导出的匹配键（优先包名，其次有意义的目录名，最后文件名）
 */
data class ZipImageItem(
    val file: File,
    val entryPath: String,
    val fileName: String,
    val matchKey: String,
)
