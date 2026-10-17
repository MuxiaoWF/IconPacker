package com.muxiao.iconpacker.core.packer.engine

import com.muxiao.iconpacker.core.packer.arsc.ArscPatcher

import com.muxiao.iconpacker.core.packer.axml.AttrValue
import com.muxiao.iconpacker.core.packer.axml.AxmlAttr
import com.muxiao.iconpacker.core.packer.axml.AxmlElement
import com.muxiao.iconpacker.core.packer.axml.AxmlReader
import com.muxiao.iconpacker.core.packer.axml.AxmlValueType
import com.muxiao.iconpacker.core.packer.axml.AxmlWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 打包管线干跑（JVM 集成测试，不依赖 Android 与签名）。
 *
 * 用一个"写入器自产的清单 + 占位资源"拼出假模板 ZIP，跑
 * [PackAssembler.buildUnsigned]，再逐条目解析回读断言——
 * 相当于 Sprint 0 出口"产物可被启动器识别"的 JVM 预演
 * （真机识别验证仍需设备，见任务 T5）。
 */
class PackAssemblerTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** 假模板 ZIP：自产清单 + arsc 占位 + 空 appfilter/drawable + 槽位图 + 调试签名 */
    private fun buildTemplate(
        withAppmap: Boolean = false,
        withUsesSdk: Boolean = false,
    ): ByteArray {
        val manifestDoc = AxmlElement(
            name = "manifest",
            attributes = listOf(
                AxmlAttr("package", AttrValue.Str("com.muxiao.icon")),
                AxmlAttr("versionCode", AttrValue.IntVal(1), nsUri = androidNs),
                // 与真实模板保持一致：aapt2 会把 defaultConfig 的 versionName 以
                // 字符串属性注入清单（实测 "1.0"），引擎的版本名替换以它的存在为前提
                AxmlAttr("versionName", AttrValue.Str("1.0"), nsUri = androidNs),
            ),
            children = buildList {
                if (withUsesSdk) {
                    // 与真实模板一致：aapt2 会把 defaultConfig 的 min/targetSdk 以
                    // 整数字面量注入 <uses-sdk>（prod defaultConfig = 24/37）
                    add(
                        AxmlElement(
                            name = "uses-sdk",
                            attributes = listOf(
                                AxmlAttr("minSdkVersion", AttrValue.IntVal(24), nsUri = androidNs),
                                AxmlAttr("targetSdkVersion", AttrValue.IntVal(37), nsUri = androidNs),
                            ),
                        ),
                    )
                }
                add(
                    AxmlElement(
                        name = "application",
                        attributes = listOf(
                            AxmlAttr("label", AttrValue.Str("My Icon Pack"), nsUri = androidNs),
                            AxmlAttr("icon", AttrValue.Ref(0x7F080001, "@mipmap/ic_launcher"), nsUri = androidNs),
                        ),
                        children = listOf(
                            AxmlElement(
                                name = "activity",
                                attributes = listOf(AxmlAttr("name", AttrValue.Str(".MainActivity"), nsUri = androidNs)),
                                // 抽屉主入口：category 由引擎在 LAUNCHER/DEFAULT 间切换
                                children = listOf(
                                    AxmlElement(
                                        name = "intent-filter",
                                        children = listOf(
                                            AxmlElement(
                                                name = "action",
                                                attributes = listOf(AxmlAttr("name", AttrValue.Str("android.intent.action.MAIN"), nsUri = androidNs)),
                                            ),
                                            AxmlElement(
                                                name = "category",
                                                attributes = listOf(AxmlAttr("name", AttrValue.Str("android.intent.category.LAUNCHER"), nsUri = androidNs)),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            },
        )
        val manifest = AxmlWriter(
            namespaces = listOf(AxmlWriter.Namespace("android", androidNs)),
        ).write(manifestDoc)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml")); zos.write(manifest); zos.closeEntry()
            // 最小真实结构 arsc（ResTable 头 + 包 chunk）：打包时会按产物包名原位改写
            // 它的包名字符串（见 arsc/ArscPatcher），占位假字节会 fail-fast
            zos.putNextEntry(ZipEntry("resources.arsc")); zos.write(fakeArsc()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/xml/appfilter.xml"))
            zos.write(AxmlWriter().write(AxmlElement("resources"))); zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/xml/drawable.xml"))
            zos.write(AxmlWriter().write(AxmlElement("resources"))); zos.closeEntry()
            if (withAppmap) {
                zos.putNextEntry(ZipEntry("res/xml/appmap.xml"))
                zos.write(AxmlWriter().write(AxmlElement("appmap"))); zos.closeEntry()
            }
            // 条目名必须与真实模板（aapt2 产物）一致：源工程的 res/drawable-xxxhdpi/
            // 编译后是 res/drawable-xxxhdpi-v4/。夹具与真实模板不一致，正是"图标写入
            // 孤儿条目、arsc 仍指向占位图"这个 bug 长期逃逸的原因——务必保持同步。
            // （真实模板由 :core:packer:syncTemplateFixture 从 :template 产物自动同步，
            // 这里的手写夹具属"假模板"，任何目录名变更都要两边一起改。）
            zos.putNextEntry(ZipEntry("res/drawable-xxxhdpi-v4/ic_0001.png"))
            zos.write(ByteArray(4) { 0 }); zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/drawable-xxxhdpi-v4/ic_0002.png"))
            zos.write(ByteArray(4) { 0 }); zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/mipmap-xxxhdpi-v4/ic_launcher.webp"))
            zos.write("PLACEHOLDER".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); zos.write("DEBUG-SIG".toByteArray()); zos.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * 抽屉可见性：true 保留 LAUNCHER（桌面能查到），false 换成 DEFAULT（查不到）。
     *
     * 锁这个行为是因为它决定了"图标包是否出现在应用抽屉"这个用户可见开关，
     * 而实现方式只是字符串池里的一条替换——静默失效不会有编译或运行时报错。
     */
    @Test
    fun `drawer visibility switches launcher category`() {
        val template = buildTemplate()
        val mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1))
        val iconBytes = mapOf(1 to "WEBP-1".toByteArray())

        fun categoryOf(showInDrawer: Boolean): String? {
            val out = PackAssembler.buildUnsigned(
                template = template,
                packName = "P",
                versionCode = 1,
                mappings = mappings,
                iconBytes = iconBytes,
                showInDrawer = showInDrawer,
            )
            val reader = AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!)
            val activity = reader.root.children.single { it.name == "application" }
                .children.single { it.name == "activity" }
            val filter = activity.children.single { it.name == "intent-filter" }
            return filter.children.single { it.name == "category" }
                .attributes.single { it.name == "name" }.rawValue
        }

        assertEquals("android.intent.category.LAUNCHER", categoryOf(showInDrawer = true))
        assertEquals("android.intent.category.DEFAULT", categoryOf(showInDrawer = false))
    }

    /**
     * 图标包自身的启动器图标：传了就替换模板占位图，没传就原样保留。
     *
     * 锁这个行为是因为模板里那张占位图只有 125 字节——一旦替换失效，
     * 用户所有产物的图标都会是同一个空白方块，且很难从产物表面看出来。
     */
    @Test
    fun `pack icon replaces template launcher icon only when provided`() {
        val template = buildTemplate()
        val mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1))
        val iconBytes = mapOf(1 to "WEBP-1".toByteArray())
        val launcherPng = "PNG-LAUNCHER-ICON".toByteArray()

        val withIcon = PackAssembler.buildUnsigned(
            template = template,
            packName = "我的图标包",
            versionCode = 1,
            mappings = mappings,
            iconBytes = iconBytes,
            packIconBytes = launcherPng,
        )
        assertArrayEquals(launcherPng, ZipUtils.readEntry(withIcon, "res/mipmap-xxxhdpi-v4/ic_launcher.webp"))

        val withoutIcon = PackAssembler.buildUnsigned(
            template = template,
            packName = "我的图标包",
            versionCode = 2,
            mappings = mappings,
            iconBytes = iconBytes,
        )
        assertArrayEquals(
            "PLACEHOLDER".toByteArray(),
            ZipUtils.readEntry(withoutIcon, "res/mipmap-xxxhdpi-v4/ic_launcher.webp"),
        )
    }

    @Test
    fun `unsigned apk has patched manifest, generated mappings and stripped signature`() {
        val template = buildTemplate()
        val mappings = listOf(
            IconMapping("ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}", 1),
            IconMapping("ComponentInfo{com.android.settings/.Settings}", 2),
        )
        val iconBytes = mapOf(
            1 to "WEBP-ICON-1".toByteArray(),
            2 to "WEBP-ICON-2".toByteArray(),
        )

        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "我的图标包",
            versionCode = 42,
            mappings = mappings,
            iconBytes = iconBytes,
        )

        // 1. 调试签名被剥离；arsc 按产物包名原位改写（此用例未传 packageName，
        // 包名保持模板原值，但必须是改写后的合法 arsc 而非占位字节）
        assertFalse(ZipUtils.hasEntry(out, "META-INF/MANIFEST.MF"))
        val arscOut = ZipUtils.readEntry(out, "resources.arsc")!!
        assertEquals(300, arscOut.size)
        assertEquals(
            "com.muxiao.icon",
            ArscPatcher.packageName(arscOut),
        )

        // 2. 清单：label 与 versionCode 已补丁，package/icon/activity 不受影响
        val manifest = AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!)
        val application = manifest.root.children.single { it.name == "application" }
        assertEquals("我的图标包", application.attributes.first { it.name == "label" }.rawValue)
        assertEquals(42, manifest.root.attributes.first { it.name == "versionCode" }.data)
        assertEquals("com.muxiao.icon", manifest.root.attributes.first { it.name == "package" }.rawValue)
        assertEquals(".MainActivity", application.children.single().attributes.single().rawValue)

        // 3. appfilter：两条映射按序生成，drawable 指向槽位资源名
        val appfilter = AxmlReader(ZipUtils.readEntry(out, "res/xml/appfilter.xml")!!)
        assertEquals(2, appfilter.root.children.size)
        val first = appfilter.root.children[0]
        assertEquals("ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}", first.attributes[0].rawValue)
        assertEquals("ic_0001", first.attributes[1].rawValue)
        assertEquals("ic_0002", appfilter.root.children[1].attributes[1].rawValue)

        // 4. drawable.xml：列出全部图标（供启动器手动选图标）
        val drawableList = AxmlReader(ZipUtils.readEntry(out, "res/xml/drawable.xml")!!)
        assertEquals(listOf("ic_0001", "ic_0002"), drawableList.root.children.map { it.attributes.single().rawValue })

        // 5. 槽位文件已替换为用户图标字节
        assertArrayEquals("WEBP-ICON-1".toByteArray(), ZipUtils.readEntry(out, "res/drawable-xxxhdpi-v4/ic_0001.png"))
        assertArrayEquals("WEBP-ICON-2".toByteArray(), ZipUtils.readEntry(out, "res/drawable-xxxhdpi-v4/ic_0002.png"))

        // 6. 不得产生孤儿条目：图标必须落在 arsc 登记的那个路径上。
        // 若目录写错，替换会"成功"但写到 arsc 不认识的新条目上，图标永远是占位图。
        assertEquals(
            "产物出现了模板中不存在的图标条目（图标不会被 arsc 引用）",
            emptyList<String>(),
            ZipUtils.entryNames(out).filter { it.contains("ic_0") && it.startsWith("res/drawable-xxxhdpi/") },
        )
    }

    @Test
    fun `missing icon slot entry in template fails loudly instead of silently appending`() {
        // 模板缺少 ic_0002 槽位：必须报错，而不是把图标追加成 arsc 不认识的孤儿条目
        val breakTemplate = zipWithoutEntry(buildTemplate())
        val error = assertThrows(IllegalArgumentException::class.java) {
            PackAssembler.buildUnsigned(
                template = breakTemplate,
                packName = "P",
                versionCode = 1,
                mappings = listOf(IconMapping("ComponentInfo{a/.A}", 2)),
                iconBytes = mapOf(2 to "X".toByteArray()),
            )
        }
        assertTrue("报错信息应指出缺失的槽位", error.message!!.contains("ic_0002"))
    }

    /** 构造最小真实结构 arsc：ResTable 头（12B，packageCount=1）+ 包 chunk（id 0x7F，固定包名 com.muxiao.icon） */
    private fun fakeArsc(): ByteArray {
        val pkgHeader = 288
        val buf = java.nio.ByteBuffer.allocate(12 + pkgHeader)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0002)                       // RES_TABLE_TYPE
        buf.putShort(12)                           // headerSize
        buf.putInt(12 + pkgHeader)                 // 整表 size
        buf.putInt(1)                              // packageCount
        buf.putShort(0x0200)                       // RES_TABLE_PACKAGE_TYPE
        buf.putShort(pkgHeader.toShort())          // chunk headerSize
        buf.putInt(pkgHeader)                      // chunk size
        buf.putInt(0x7F)                           // package id
        val nameField = ByteArray(256)
        "com.muxiao.icon".toByteArray(Charsets.UTF_16LE).copyInto(nameField)
        buf.put(nameField)
        buf.putInt(0) // lastPublicType
        buf.putInt(0) // lastKeyType
        return buf.array()
    }

    /** 从 ZIP 中剔除一个条目，用于构造"模板缺 ic_0002 槽位"的场景 */
    private fun zipWithoutEntry(zip: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(zip.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name != "res/drawable-xxxhdpi-v4/ic_0002.png" && !e.isDirectory) {
                        zos.putNextEntry(ZipEntry(e.name))
                        zos.write(zis.readBytes())
                        zos.closeEntry()
                    }
                    e = zis.nextEntry
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `appfilter attributes are plain strings without namespace`() {
        val out = PackAssembler.buildUnsigned(
            template = buildTemplate(),
            packName = "P",
            versionCode = 2,
            mappings = listOf(IconMapping("ComponentInfo{a/.B}", 1)),
            iconBytes = mapOf(1 to byteArrayOf(1)),
        )
        val appfilter = AxmlReader(ZipUtils.readEntry(out, "res/xml/appfilter.xml")!!)
        val item = appfilter.root.children.single()
        item.attributes.forEach { attr ->
            assertEquals(null, attr.nsUri)
            assertEquals(AxmlValueType.STRING, attr.dataType)
        }
    }

    @Test
    fun `progress callback fires in pipeline order`() {
        val steps = ArrayList<PackStep>()
        PackAssembler.buildUnsigned(
            template = buildTemplate(),
            packName = "P",
            versionCode = 2,
            mappings = listOf(IconMapping("ComponentInfo{a/.B}", 1)),
            iconBytes = mapOf(1 to byteArrayOf(1)),
        ) { step, _, _ -> if (step !in steps) steps.add(step) }
        assertEquals(listOf(PackStep.MANIFEST, PackStep.AXML, PackStep.ZIP), steps)
    }

    /**
     * 包名改写：不同项目必须拿到不同包名才能在系统里共存。
     *
     * 锁这个行为是因为清单里的 package 一旦漏改，产物在系统眼里仍是模板那个应用——
     * 装第二个要么覆盖掉第一个，要么因 versionCode 更低被系统拒绝，
     * 而产物本身完全正常，从文件表面看不出任何异常。
     */
    @Test
    fun `package name is rewritten into manifest`() {
        val template = buildTemplate()
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "P",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1)),
            iconBytes = mapOf(1 to "WEBP-1".toByteArray()),
            packageName = "com.example.custom.pack",
        )
        val manifest = AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!)
        assertEquals(
            "com.example.custom.pack",
            manifest.root.attributes.first { it.name == "package" }.rawValue,
        )
        // activity 类名刻意不动：dex 里就是模板那个类，applicationId 与 Java 包名解耦
        val activity = manifest.root.children.single { it.name == "application" }
            .children.single { it.name == "activity" }
        assertEquals(".MainActivity", activity.attributes.single { it.name == "name" }.rawValue)
        // arsc 包名必须与 manifest 同步（getIdentifier 按它精确匹配）
        assertEquals(
            "com.example.custom.pack",
            ArscPatcher.packageName(ZipUtils.readEntry(out, "resources.arsc")!!),
        )
    }

    /**
     * 包名长度变化时字符串池是**整体重建**（不是原地覆盖），池之后所有 chunk 的偏移
     * 都会平移——必须确保同一条链上改写的 label/versionCode 与其余节点仍能正确回读。
     */
    @Test
    fun `manifest stays readable across package name length changes`() {
        val template = buildTemplate()
        val mappings = listOf(IconMapping("ComponentInfo{com.a/.A}", 1))
        val iconBytes = mapOf(1 to "WEBP-1".toByteArray())

        // 明显更短 / 明显更长 / 与模板等长 三种形态
        val names = listOf("a.b", "com.example.a.quite.long.package.name.here", "com.muxiao.icon")
        for (name in names) {
            val out = PackAssembler.buildUnsigned(
                template = template,
                packName = "包名长度 $name",
                versionCode = 7,
                mappings = mappings,
                iconBytes = iconBytes,
                packageName = name,
            )
            val manifest = AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!)
            assertEquals(name, manifest.root.attributes.first { it.name == "package" }.rawValue)
            // arsc 包名跟随 manifest，无论长度如何变化（字段定长，原位覆写）
            assertEquals(
                name,
                ArscPatcher.packageName(ZipUtils.readEntry(out, "resources.arsc")!!),
            )
            assertEquals(7, manifest.root.attributes.first { it.name == "versionCode" }.data)
            val application = manifest.root.children.single { it.name == "application" }
            assertEquals(
                "包名长度 $name",
                application.attributes.first { it.name == "label" }.rawValue,
            )
            // 抽屉主入口 category 仍应可读（它位于池重建之后的位置）
            val activity = application.children.single { it.name == "activity" }
            val filter = activity.children.single { it.name == "intent-filter" }
            val category = filter.children.single { it.name == "category" }
            assertEquals(
                "android.intent.category.LAUNCHER",
                category.attributes.single { it.name == "name" }.rawValue,
            )
        }
    }

    /**
     * appmap.xml（CandyBar/Blueprint 生态组件映射，Atom Launcher 消费）：
     * 模板登记了该资源时按映射生成；class 是完整类名（相对名以包名展开）。
     */
    @Test
    fun `appmap entries carry class and name for mapped components`() {
        val template = buildTemplate(withAppmap = true)
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "P",
            versionCode = 1,
            mappings = listOf(
                IconMapping("ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}", 1),
                IconMapping("ComponentInfo{com.android.settings/.Settings}", 2),
            ),
            iconBytes = mapOf(1 to byteArrayOf(1), 2 to byteArrayOf(2)),
        )
        val appmap = AxmlReader(ZipUtils.readEntry(out, "res/xml/appmap.xml")!!)
        assertEquals("appmap", appmap.root.name)
        assertEquals(2, appmap.root.children.size)
        assertEquals(
            "com.google.android.apps.chrome.Main",
            appmap.root.children[0].attributes.single { it.name == "class" }.rawValue,
        )
        assertEquals(
            "ic_0001",
            appmap.root.children[0].attributes.single { it.name == "name" }.rawValue,
        )
        assertEquals(
            "com.android.settings.Settings",
            appmap.root.children[1].attributes.single { it.name == "class" }.rawValue,
        )
        assertEquals(
            "ic_0002",
            appmap.root.children[1].attributes.single { it.name == "name" }.rawValue,
        )
    }

    /**
     * appmap 归一化：仅给包名（`ComponentInfo{pkg/}`）的条目没有类名可写，
     * 直接丢弃——Atom 对残缺条目不生效，写进去等于误导。
     */
    @Test
    fun `appmap drops entries without an activity class`() {
        val template = buildTemplate(withAppmap = true)
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "P",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{com.orphan/}", 1)),
            iconBytes = mapOf(1 to byteArrayOf(1)),
        )
        val appmap = AxmlReader(ZipUtils.readEntry(out, "res/xml/appmap.xml")!!)
        assertEquals(0, appmap.root.children.size)
    }

    /** 合成/第三方模板未登记 appmap 时降级跳过，绝不追加 arsc 不认识的条目 */
    @Test
    fun `template without appmap entry stays orphan free`() {
        val template = buildTemplate(withAppmap = false)
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "P",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{a/.A}", 1)),
            iconBytes = mapOf(1 to byteArrayOf(1)),
        )
        assertFalse(ZipUtils.hasEntry(out, "res/xml/appmap.xml"))
    }

    /**
     * SDK 重写：\<uses-sdk\> 是整数字面量（aapt2 从 defaultConfig 注入），
     * 与 versionCode 同一条原地改写路径。只重写用户提供的那一侧。
     */
    @Test
    fun `sdk overrides patch uses-sdk in place`() {
        val template = buildTemplate(withUsesSdk = true)
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "P",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{a/.A}", 1)),
            iconBytes = mapOf(1 to "WEBP-1".toByteArray()),
            minSdkOverride = 26,
            targetSdkOverride = 29,
        )
        val manifest = AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!)
        val usesSdk = manifest.root.children.single { it.name == "uses-sdk" }
        assertEquals(26, usesSdk.attributes.single { it.name == "minSdkVersion" }.data)
        assertEquals(29, usesSdk.attributes.single { it.name == "targetSdkVersion" }.data)
        // 其余清单字段不受原地改写影响
        assertEquals(1, manifest.root.attributes.single { it.name == "versionCode" }.data)
    }

    /** 只重写 targetSdk：minSdk 保持模板值（不反写基准常量） */
    @Test
    fun `target sdk override leaves min sdk as is`() {
        val template = buildTemplate(withUsesSdk = true)
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "P",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{a/.A}", 1)),
            iconBytes = mapOf(1 to "WEBP-1".toByteArray()),
            targetSdkOverride = 29,
        )
        val usesSdk = AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!)
            .root.children.single { it.name == "uses-sdk" }
        assertEquals(24, usesSdk.attributes.single { it.name == "minSdkVersion" }.data)
        assertEquals(29, usesSdk.attributes.single { it.name == "targetSdkVersion" }.data)
    }

    /** 未传重写值：清单保持模板原样 */
    @Test
    fun `uses-sdk untouched without overrides`() {
        val template = buildTemplate(withUsesSdk = true)
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "P",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{a/.A}", 1)),
            iconBytes = mapOf(1 to "WEBP-1".toByteArray()),
        )
        val usesSdk = AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!)
            .root.children.single { it.name == "uses-sdk" }
        assertEquals(24, usesSdk.attributes.single { it.name == "minSdkVersion" }.data)
        assertEquals(37, usesSdk.attributes.single { it.name == "targetSdkVersion" }.data)
    }

    /** PackRequest 校验：minSdk/相对 target 重写非法值必须拒绝 */
    @Test
    fun `uses-sdk overrides are validated in pack request`() {
        val stubProvider = object : com.muxiao.iconpacker.core.packer.sign.SigningKeyProvider {
            override fun getOrCreate() = error("测试桩不应被调用")
        }
        // minSdk 低于下限
        val ex1 = assertThrows(IllegalArgumentException::class.java) {
            PackRequest(
                packName = "P",
                versionCode = 1,
                mappings = listOf(IconMapping("a", 1)),
                icons = listOf(SlotIcon(1, "/tmp/x.png")),
                templateApkPath = "/tmp/t.apk",
                signingKeyProvider = stubProvider,
                overrideMinSdk = 20,
                outputPath = "/tmp/out.apk",
            )
        }
        assertTrue(ex1.message!!.contains("minSdk"))
        // target < min
        val ex2 = assertThrows(IllegalArgumentException::class.java) {
            PackRequest(
                packName = "P",
                versionCode = 1,
                mappings = listOf(IconMapping("a", 1)),
                icons = listOf(SlotIcon(1, "/tmp/x.png")),
                templateApkPath = "/tmp/t.apk",
                signingKeyProvider = stubProvider,
                overrideMinSdk = 26,
                overrideTargetSdk = 25,
                outputPath = "/tmp/out.apk",
            )
        }
        assertTrue(ex2.message!!.contains("targetSdk"))
    }

    /** 模板缺 <uses-sdk> 时降级保持模板清单，不让打包失败 */
    @Test
    fun `sdk override degrades gracefully when template has no uses-sdk`() {
        val template = buildTemplate(withUsesSdk = false)
        val out = PackAssembler.buildUnsigned(
            template = template,
            packName = "P",
            versionCode = 1,
            mappings = listOf(IconMapping("ComponentInfo{a/.A}", 1)),
            iconBytes = mapOf(1 to "WEBP-1".toByteArray()),
            minSdkOverride = 26,
        )
        val manifest = AxmlReader(ZipUtils.readEntry(out, "AndroidManifest.xml")!!)
        assertTrue(manifest.root.children.none { it.name == "uses-sdk" })
        assertEquals(1, manifest.root.attributes.single { it.name == "versionCode" }.data)
    }

    /** 非法包名必须在装配前被拒绝，绝不能产出一个装不上（或覆盖他人）的 APK */
    @Test
    fun `invalid package name fails before assembly`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PackAssembler.buildUnsigned(
                template = buildTemplate(),
                packName = "P",
                versionCode = 1,
                mappings = listOf(IconMapping("ComponentInfo{a/.A}", 1)),
                iconBytes = mapOf(1 to byteArrayOf(1)),
                packageName = "com..broken",
            )
        }
        assertEquals(PackageNameRules.Issue.BLANK_SEGMENT.message, error.message)
    }

    @Test
    fun `drawable list includes unmapped icons`() {
        // 用户导入了 ic_0001/ic_0002 但只映射了 ic_0001：drawable.xml 仍应列出两个
        val out = PackAssembler.buildUnsigned(
            template = buildTemplate(),
            packName = "P",
            versionCode = 2,
            mappings = listOf(IconMapping("ComponentInfo{a/.B}", 1)),
            iconBytes = mapOf(1 to byteArrayOf(1), 2 to byteArrayOf(2)),
        )
        val drawableList = AxmlReader(ZipUtils.readEntry(out, "res/xml/drawable.xml")!!)
        assertEquals(listOf("ic_0001", "ic_0002"), drawableList.root.children.map { it.attributes.single().rawValue })
        // appfilter 只有实际映射
        val appfilter = AxmlReader(ZipUtils.readEntry(out, "res/xml/appfilter.xml")!!)
        assertEquals(1, appfilter.root.children.size)
    }

    @Test
    fun `template without manifest fails loudly`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("resources.arsc"))
            zos.write("X".toByteArray())
            zos.closeEntry()
        }
        val ex = runCatching {
            PackAssembler.buildUnsigned(
                template = out.toByteArray(),
                packName = "P",
                versionCode = 1,
                mappings = listOf(IconMapping("a", 1)),
                iconBytes = mapOf(1 to byteArrayOf(1)),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
    }
}
