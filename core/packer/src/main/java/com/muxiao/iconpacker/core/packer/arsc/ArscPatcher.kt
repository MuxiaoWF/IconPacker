package com.muxiao.iconpacker.core.packer.arsc

/**
 * resources.arsc 定点改写器（与 axml/AxmlPatcher 同思路：字节级原位手术，不重排表）。
 *
 * 为什么必须改 arsc 的包名：
 * 打包引擎用 [com.muxiao.iconpacker.core.packer.axml.AxmlPatcher.setPackageName] 改写
 * 清单的 `package` 属性让产物以独立包名安装，但 aapt2 编译时写进 arsc 的
 * `ResTable_package.name` 仍是模板的包名。运行期 `Resources.getIdentifier(name, type, pkg)`
 * 的底层实现（AssetManager2::GetResourceId，frameworks/base libs/androidfw）对请求包名与
 * **arsc 中记录的包名字符串做逐字符精确匹配**，不一致直接返回 0：
 *
 * ```cpp
 * if (package_name != package->GetPackageName()) { break; } // → nullopt → getIdentifier == 0
 * ```
 *
 * 于是"manifest 已改、arsc 没改"的产物会出现一种最阴险的故障形态：
 * 装得上、能被启动器识别为图标包，但启动器按包名找 appfilter/drawable 一律落空
 * ——表现为"图标包里没有任何可以替换的图标"。从产物文件表面完全看不出异常。
 *
 * 修法即本文件：把 arsc 头部 ResTable_package.name（char16[128] 定长字段）原位改写为
 * 产物包名。字段定长 ⇒ 只覆写字节、不改任何 chunk 长度与偏移 ⇒ 格式风险为零，
 * 也不破坏 resources.arsc 的 STORED + 4 字节对齐要求（字节内容变了，压缩方式不变）。
 */
object ArscPatcher {

    /** 顶层 chunk 类型：RES_TABLE_PACKAGE_TYPE */
    private const val PACKAGE_CHUNK_TYPE = 0x0200

    /** ResTable 头部长度（type 2 + headerSize 2 + size 4） */
    private const val TABLE_HEADER_SIZE = 12

    /** 包名定长字段：128 个 char16，含结尾 NUL */
    private const val NAME_FIELD_BYTES = 256

    /** 包名字段在 ResTable_package chunk 内的偏移（type 2 + headerSize 2 + id 4 之后） */
    private const val NAME_FIELD_OFFSET = 12

    /**
     * 把 arsc 中第一个包 chunk（id 0x7F）的包名改写为 [newPackage]。
     *
     * 原位覆写、返回同一数组；新旧包名相同时原样返回。失败一律抛异常让打包中止——
     * 包名不一致的产物是"装得上但零图标"的坏件，比打包失败更难排查。
     *
     * @throws IllegalStateException arsc 中找不到包 chunk，或原包名不是合法非空字符串
     * @throws IllegalArgumentException 新包名为空或超出定长字段容量（127 字符 + NUL）
     */
    fun setPackageName(arsc: ByteArray, newPackage: String): ByteArray {
        require(newPackage.isNotEmpty()) { "产物包名不能为空" }
        require(newPackage.length < NAME_FIELD_BYTES / 2) {
            "包名超出 arsc 定长字段容量（最多 ${NAME_FIELD_BYTES / 2 - 1} 字符）"
        }

        val chunkOffset = findPackageChunk(arsc)
            ?: error("resources.arsc 中找不到 ResTable_package chunk，无法改写包名")
        val nameOffset = chunkOffset + NAME_FIELD_OFFSET

        val oldName = readName(arsc, nameOffset)
        require(oldName.isNotEmpty()) { "arsc 原包名为空，不是合法的资源表" }
        if (oldName == newPackage) return arsc

        // 新名 UTF-16LE + NUL 结尾 + 零填充到定长。utf16 编码 1 个 char 固定 2 字节，
        // ASCII 包名（PackageNameRules 已限定字符集）不含代理对，长度 = 2 * chars。
        val encoded = newPackage.toByteArray(Charsets.UTF_16LE)
        require(encoded.size + 2 <= NAME_FIELD_BYTES) {
            "包名超出 arsc 定长字段容量"
        }
        val field = ByteArray(NAME_FIELD_BYTES)
        encoded.copyInto(field)
        field[encoded.size] = 0
        field[encoded.size + 1] = 0

        System.arraycopy(field, 0, arsc, nameOffset, NAME_FIELD_BYTES)
        return arsc
    }

    /** 读取包名字段当前值（UTF-16LE，NUL 结尾） */
    fun packageName(arsc: ByteArray): String {
        val chunkOffset = findPackageChunk(arsc)
            ?: error("resources.arsc 中找不到 ResTable_package chunk")
        return readName(arsc, chunkOffset + NAME_FIELD_OFFSET)
    }

    private fun findPackageChunk(arsc: ByteArray): Int? {
        if (arsc.size < TABLE_HEADER_SIZE + 8) return null
        // ResTable 头之后是全局字符串池等顶层 chunk，逐个按其 size 字段前进，
        // 直到遇到包 chunk（包 chunk 是最后一个顶层 chunk，紧跟其后是 type/key 池等子 chunk）
        var off = TABLE_HEADER_SIZE
        while (off + 8 <= arsc.size) {
            val type = readUShort(arsc, off)
            val size = readUInt(arsc, off + 4)
            if (type == PACKAGE_CHUNK_TYPE) return off
            if (size < 8 || off + size > arsc.size) return null // 尺寸异常，防越界死循环
            off += size
        }
        return null
    }

    private fun readName(data: ByteArray, offset: Int): String {
        val field = data.copyOfRange(offset, offset + NAME_FIELD_BYTES)
        return field.toString(Charsets.UTF_16LE).substringBefore('\u0000')
    }

    private fun readUShort(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

    private fun readUInt(data: ByteArray, off: Int): Int =
        readUShort(data, off) or (readUShort(data, off + 2) shl 16)
}
