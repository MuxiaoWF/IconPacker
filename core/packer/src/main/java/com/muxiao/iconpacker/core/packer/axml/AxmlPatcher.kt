package com.muxiao.iconpacker.core.packer.axml

/**
 * AXML 清单补丁器。
 *
 * 设计取舍：清单（AndroidManifest.xml）不采用 [AxmlWriter] 全量生成，而是
 * 对模板 APK 中由 aapt2 编译好的清单做定点修补。原因：
 * 1. 清单含 android: 命名空间属性，框架（PackageManager）依赖资源表把属性名
 *    解析为框架资源 ID（如 versionCode=0x0101021b），资源表的索引语义由 aapt2
 *    保证，自行生成容易踩坑；
 * 2. 每次构建变动的几个值都走定点修补：应用名（label，字符串）、包名（package，字符串）、
 *  版本名（versionName，字符串）、版本号（versionCode，整数）——格式风险为零。
 *
 * 前置约束（模板构建时必须满足，见 T3）：
 * - `android:label` 必须是原始字符串（不能是 `@string/xxx` 引用）；
 * - `android:versionCode` 必须是整数字面量。
 */
object AxmlPatcher {

    /**
     * 替换指定元素的字符串属性值（如 application 的 label）。
     *
     * 实现：定位属性的 rawValue 字符串索引 → 用新值重建整个字符串池
     * （保持字符串顺序不变，仅替换该条内容）→ 重组文件并修正头部尺寸。
     * 其余 chunk 只按索引引用字符串，不受池长度变化影响。
     *
     * @throws IllegalStateException 目标元素/属性不存在或不是字符串类型
     */
    fun replaceStringAttribute(
        data: ByteArray,
        elementName: String,
        attributeName: String,
        newValue: String,
    ): ByteArray {
        // StringAttrLocation 第 4 个分量即 rawIndex 本身（Int），无需再取字段
        val (poolStart, poolEnd, strings, rawIdx) = locateStringAttribute(
            data, elementName, attributeName,
        )
        require(rawIdx in strings.indices) { "rawValue 索引越界：$rawIdx" }
        require(newValue.isNotEmpty()) { "新值不能为空字符串" }

        val newStrings = strings.toMutableList()
        newStrings[rawIdx] = newValue
        val newPool = StringPool.encode(newStrings)

        val out = AxmlReader.concat(
            data.copyOfRange(0, poolStart),
            newPool,
            data.copyOfRange(poolEnd, data.size),
        )
        AxmlReader.writeU32(out, 4, out.size)
        return out
    }

    /**
     * 原地修改指定元素的整数属性值（如 manifest 的 versionCode）。
     *
     * 整数属性的 typedValue.data 是定长 4 字节，原地改写不改变任何 chunk 尺寸，
     * 文件布局完全不变。
     *
     * @throws IllegalStateException 目标元素/属性不存在或不是整数类型
     */
    fun setIntAttribute(
        data: ByteArray,
        elementName: String,
        attributeName: String,
        value: Int,
    ): ByteArray {
        val target = locateIntAttribute(data, elementName, attributeName)
        val out = data.copyOf()
        AxmlReader.writeU32(out, target.dataOffset, value)
        return out
    }

    /** 便捷入口：替换 application 的 label（即启动器里显示的图标包名） */
    fun replaceLabel(manifest: ByteArray, label: String): ByteArray =
        replaceStringAttribute(manifest, "application", "label", label)

    /** 便捷入口：设置 manifest 的 versionCode（覆盖安装升级用） */
    fun setVersionCode(manifest: ByteArray, versionCode: Int): ByteArray =
        setIntAttribute(manifest, "manifest", "versionCode", versionCode)

    /**
     * 便捷入口：设置 manifest 的 versionName（系统"应用信息"里显示给用户的版本名）。
     *
     * 与 versionCode 的区别是**语义层面**的，两个都要能改：versionCode 决定"能否覆盖安装"
     * （必须不小于已装版本），versionName 只是给人看的字符串（`2.1.0`、`2026.10` 都行）。
     * 此前引擎只改 versionCode，所有产物的版本名都停在模板的 `1.0`——用户自定义了版本
     * 却在系统里看不见，等于没生效。
     *
     * 走 [replaceStringAttribute]（变长字符串，重建池），与 label 同一条已验证路径。
     * 前置约束：模板清单里 `android:versionName` 必须是字符串字面量——aapt2 从
     * `defaultConfig.versionName` 注入，实测模板产物中为 `"1.0"`。
     */
    fun setVersionName(manifest: ByteArray, versionName: String): ByteArray =
        replaceStringAttribute(manifest, "manifest", "versionName", versionName)

    /**
     * 便捷入口：设置 manifest 的包名（applicationId）。
     *
     * 这是"多个图标包共存"的关键：不改包名时所有产物在系统眼里是同一个应用，
     * 装第二个必然覆盖或降级失败。
     *
     * 刻意**不同步改写 resources.arsc 里的包名**（aapt2 从模板 namespace 写入，仍是
     * 模板原值）。两者不一致不影响运行：AssetManager 按资源 package id（0x7f）索引，
     * 包名字符串只服务于 `getResourcePackageName()` 一类带名查询。
     * 已实测验证：改包名后的产物可正常安装、主题组件可被系统解析、Activity 能启动并完成
     * 资源加载（窗口视图创建成功，无 Resources$NotFoundException）。
     *
     * `package` 属性无命名空间前缀，[replaceStringAttribute] 按属性名匹配，不受影响。
     * 同理**不改** `<activity android:name>`——dex 里就是模板那个类名，
     * applicationId 与 Java 包名本就允许解耦。
     */
    fun setPackageName(manifest: ByteArray, packageName: String): ByteArray =
        replaceStringAttribute(manifest, "manifest", "package", packageName)

    /**
     * 便捷入口：控制图标包是否出现在应用抽屉。
     *
     * 手法是替换主入口 `intent-filter` 里 `<category>` 的 android:name：
     * - `android.intent.category.LAUNCHER` → 显示（桌面按 MAIN+LAUNCHER 查询抽屉项）；
     * - `android.intent.category.DEFAULT` → 隐藏（MAIN+DEFAULT 是中性组合，不会被拾取）。
     *
     * 之所以不增删 `intent-filter` 节点：那需要 Reader 解析 → 改树 → Writer 重写整份
     * 清单，而框架依赖 arsc 的属性 ID 语义，重建有回归风险。替换字符串池里的一条
     * 字符串走的是 [replaceStringAttribute] 既有路径（支持变长，重建池但不碰其他
     * chunk），格式风险为零。
     *
     * 模板约束（见 template/src/main/AndroidManifest.xml）：模板现在含**多条**
     * `<category>`（图标包识别 filter 需要 DEFAULT / Apex 等声明），它们引用池中
     * 各自独立的槽位；本函数只改写**文档顺序第一个** `<category>`——必须是抽屉入口
     * 那条（当前值为 LAUNCHER 或 DEFAULT）。若第一个 category 是别的值（说明模板
     * 把识别 filter 排到了抽屉前面，或来自第三方模板），改写会把识别声明静默改坏，
     * 故直接抛 [IllegalStateException] 拒绝——[com.muxiao.iconpacker.core.packer.engine.
     * PackAssembler] 对该异常降级为保持模板原样，不让打包整体失败。
     */
    fun setDrawerEntryVisible(manifest: ByteArray, visible: Boolean): ByteArray {
        val (_, _, strings, rawIdx) = locateStringAttribute(manifest, "category", "name")
        val current = strings.getOrNull(rawIdx).orEmpty()
        check(current == CATEGORY_LAUNCHER || current == CATEGORY_HIDDEN) {
            "清单中第一个 <category> 的值是 \"$current\"（应为 LAUNCHER/DEFAULT）——" +
                "抽屉入口 filter 必须排在所有含 <category> 的 filter 之前，" +
                "否则抽屉开关会把识别声明改坏"
        }
        return replaceStringAttribute(
            data = manifest,
            elementName = "category",
            attributeName = "name",
            newValue = if (visible) CATEGORY_LAUNCHER else CATEGORY_HIDDEN,
        )
    }

    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    private const val CATEGORY_HIDDEN = "android.intent.category.DEFAULT"

    // ---- 内部定位逻辑 ----

    /** 注意：必须是 data class——[replaceStringAttribute] 依赖 component1..4 解构 */
    private data class StringAttrLocation(
        val poolStart: Int,
        val poolEnd: Int,
        val strings: List<String>,
        val rawIndex: Int,
    )

    private data class IntAttrLocation(val dataOffset: Int)

    private fun locateStringAttribute(
        data: ByteArray,
        elementName: String,
        attributeName: String,
    ): StringAttrLocation {
        var poolStart = -1
        var poolEnd = -1
        var strings: List<String> = emptyList()
        var rawIndex: Int

        var off = 8
        val total = AxmlReader.readU32(data, 4)
        while (off + 8 <= total) {
            val chunkType = AxmlReader.readU16(data, off)
            val chunkSize = AxmlReader.readU32(data, off + 4)
            when (chunkType) {
                StringPool.RES_STRING_POOL_TYPE -> {
                    poolStart = off
                    poolEnd = off + chunkSize
                    strings = StringPool.decode(data, off)
                }
                AxmlWriter.RES_XML_START_ELEMENT_TYPE -> {
                    val nameIdx = AxmlReader.readU32(data, off + 20)
                    if (strings.getOrNull(nameIdx) == elementName) {
                        val attrCount = AxmlReader.readU16(data, off + 28)
                        // 属性数组起点 = headerSize + attributeStart（attributeStart 的基准是
                        // attrExt 起点）。不要硬编码 36——那只是当前 aapt2 产物的巧合值，
                        // 一旦 headerSize/attributeStart 组合变化就会静默错位。
                        var attrOff = off + AxmlReader.readU16(data, off + 2) +
                            AxmlReader.readU16(data, off + 24)
                        repeat(attrCount) {
                            val aNameIdx = AxmlReader.readU32(data, attrOff + 4)
                            if (strings.getOrNull(aNameIdx) == attributeName) {
                                val aType = data[attrOff + 15].toInt() and 0xFF
                                // 用 check 而非 require：这是"数据状态不符合预期"
                                // （属性存在但类型不对），契约为 IllegalStateException，
                                // 与 error()/未找到属性的分支保持一致
                                check(aType == AxmlValueType.STRING) {
                                    "属性 $attributeName 不是字符串类型（type=0x${aType.toString(16)}）"
                                }
                                rawIndex = AxmlReader.readU32(data, attrOff + 8)
                                return StringAttrLocation(poolStart, poolEnd, strings, rawIndex)
                            }
                            attrOff += AxmlWriter.ATTR_SIZE
                        }
                    }
                }
                else -> Unit
            }
            off += chunkSize
        }
        error("未找到元素 <$elementName> 的字符串属性 $attributeName")
    }

    private fun locateIntAttribute(
        data: ByteArray,
        elementName: String,
        attributeName: String,
    ): IntAttrLocation {
        var strings: List<String> = emptyList()

        var off = 8
        val total = AxmlReader.readU32(data, 4)
        while (off + 8 <= total) {
            val chunkType = AxmlReader.readU16(data, off)
            val chunkSize = AxmlReader.readU32(data, off + 4)
            when (chunkType) {
                StringPool.RES_STRING_POOL_TYPE -> strings = StringPool.decode(data, off)
                AxmlWriter.RES_XML_START_ELEMENT_TYPE -> {
                    val nameIdx = AxmlReader.readU32(data, off + 20)
                    if (strings.getOrNull(nameIdx) == elementName) {
                        val attrCount = AxmlReader.readU16(data, off + 28)
                        // 属性数组起点 = headerSize + attributeStart（attributeStart 的基准是
                        // attrExt 起点）。不要硬编码 36——那只是当前 aapt2 产物的巧合值，
                        // 一旦 headerSize/attributeStart 组合变化就会静默错位。
                        var attrOff = off + AxmlReader.readU16(data, off + 2) +
                            AxmlReader.readU16(data, off + 24)
                        repeat(attrCount) {
                            val aNameIdx = AxmlReader.readU32(data, attrOff + 4)
                            if (strings.getOrNull(aNameIdx) == attributeName) {
                                val aType = data[attrOff + 15].toInt() and 0xFF
                                check(
                                    aType == AxmlValueType.INT_DEC || aType == AxmlValueType.INT_HEX,
                                ) {
                                    "属性 $attributeName 不是整数类型（type=0x${aType.toString(16)}）"
                                }
                                // 属性 chunk：ns(4)+name(4)+raw(4)+Res_value(8)，
                                // data 位于属性起始 +16
                                return IntAttrLocation(attrOff + 16)
                            }
                            attrOff += AxmlWriter.ATTR_SIZE
                        }
                    }
                }
                else -> Unit
            }
            off += chunkSize
        }
        error("未找到元素 <$elementName> 的整数属性 $attributeName")
    }
}
