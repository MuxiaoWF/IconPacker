package com.muxiao.iconpacker.core.packer.axml

/**
 * AXML 属性值的数据类型常量。
 *
 * 与 framework 的 `ResourceTypes.h` / `android.content.res` 中的定义一致，
 * 二进制 XML 中每个属性携带一个 Res_value（dataType + 32 位 data）。
 */
object AxmlValueType {
    /** 资源引用（@drawable/xxx），data 为资源 ID */
    const val REFERENCE = 0x01
    /** 字符串，data 为字符串池索引 */
    const val STRING = 0x03
    /** 十进制整数 */
    const val INT_DEC = 0x10
    /** 十六进制整数 */
    const val INT_HEX = 0x11
    /** 布尔值（0xFFFFFFFF = true，0 = false） */
    const val INT_BOOLEAN = 0x12
}

/**
 * AXML 属性值。
 *
 * 序列化分两步：先由 [StringPool] 统一分配字符串索引（收集阶段），
 * 再写入 chunk（发射阶段）。因此 [payload] / [rawIndex] 需要传入同一个池实例。
 *
 * 命名约定：嵌套类不得命名为 [Int]——会遮蔽 `kotlin.Int`，导致本接口内
 * `fun payload(...): Int` 等成员签名被解析为嵌套类而编译失败。故整数类型
 * 命名为 [IntVal]。
 */
sealed interface AttrValue {
    /** Res_value.dataType */
    val dataType: Int

    /** Res_value.data 的 32 位负载 */
    fun payload(pool: StringPool): Int

    /** rawValue 的字符串池索引；无原始字符串形式时为 -1（0xFFFFFFFF） */
    fun rawIndex(pool: StringPool): Int

    /** 字符串值：rawValue 与 data 均指向同一个字符串池索引 */
    data class Str(val value: String) : AttrValue {
        override val dataType: Int get() = AxmlValueType.STRING
        override fun payload(pool: StringPool): Int = pool.intern(value)
        override fun rawIndex(pool: StringPool): Int = pool.intern(value)
    }

    /** 十进制整数值（如 versionCode） */
    data class IntVal(val value: Int) : AttrValue {
        override val dataType: Int get() = AxmlValueType.INT_DEC
        override fun payload(pool: StringPool): Int = value
        override fun rawIndex(pool: StringPool): Int = -1
    }

    /** 布尔值 */
    data class Bool(val value: Boolean) : AttrValue {
        override val dataType: Int get() = AxmlValueType.INT_BOOLEAN
        override fun payload(pool: StringPool): Int = if (value) -1 else 0
        override fun rawIndex(pool: StringPool): Int = -1
    }

    /**
     * 资源引用值（如 @mipmap/ic_launcher）。
     *
     * @param resourceId 资源 ID（模板 arsc 中的 0x7f 开头 ID）
     * @param raw 原始字符串形式（如 "@mipmap/ic_launcher"），写入 rawValue；为空则 -1
     */
    data class Ref(val resourceId: Int, val raw: String? = null) : AttrValue {
        override val dataType: Int get() = AxmlValueType.REFERENCE
        override fun payload(pool: StringPool): Int = resourceId
        override fun rawIndex(pool: StringPool): Int = raw?.let { pool.intern(it) } ?: -1
    }
}
