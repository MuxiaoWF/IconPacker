package com.muxiao.iconpacker.core.packer.engine

import java.util.zip.ZipInputStream

/** ZIP 读取辅助（打包管线专用：按条目名读取，不存在返回 null） */
object ZipUtils {

    fun readEntry(zip: ByteArray, entryName: String): ByteArray? {
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == entryName && !entry.isDirectory) {
                    return zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    fun hasEntry(zip: ByteArray, entryName: String): Boolean = readEntry(zip, entryName) != null

    /**
     * 列出全部条目名（不含目录条目，不解压数据）。
     *
     * 供打包前校验使用：模板中应当存在的条目是否真的存在。
     */
    fun entryNames(zip: ByteArray): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out.add(entry.name)
                entry = zis.nextEntry
            }
        }
        return out
    }
}
