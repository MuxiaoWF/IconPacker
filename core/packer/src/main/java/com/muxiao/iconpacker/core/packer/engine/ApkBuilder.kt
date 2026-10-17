package com.muxiao.iconpacker.core.packer.engine

import com.muxiao.iconpacker.core.packer.arsc.ArscPatcher
import com.muxiao.iconpacker.core.packer.axml.AxmlPatcher
import com.muxiao.iconpacker.core.packer.axml.AxmlWriter
import com.muxiao.iconpacker.core.packer.sign.ApkSignerWrapper
import java.io.File

/**
 * 打包引擎编排器：图标包项目 → 可安装 APK 的唯一入口。
 *
 * 管线（与 docs/pack-engine.md 一致）：
 * ```
 * PREPARE  读取模板 + 获取/创建设备端签名身份
 * ICONS    逐张预处理（缩放 192px → WebP）——Android 侧，唯一耗时步
 * [PackAssembler.buildUnsigned] MANIFEST → AXML → ZIP（纯 Kotlin，可 JVM 单测）
 * SIGN     apksig v1+v2+v3 签名 + 签名后自校验
 * ```
 *
 * 同步方法：调用方自行放到 IO 协程/线程（打包 100 图标约数秒，不得进主线程）。
 * 任一步骤失败即返回 [PackResult.Failure]，带步骤定位与原因，不静默成功。
 */
class ApkBuilder(
    private val iconProcessor: IconProcessor = IconProcessor(),
    private val signerWrapper: ApkSignerWrapper = ApkSignerWrapper(),
) {

    fun pack(request: PackRequest, onProgress: PackProgress = { _, _, _ -> }): PackResult {
        var step = PackStep.PREPARE
        val unsigned = File(request.outputPath + UNSIGNED_SUFFIX)
        return try {
            step = PackStep.PREPARE
            onProgress(step, 0, 1)
            val template = File(request.templateApkPath).readBytes()
            val identity = request.signingKeyProvider.getOrCreate()
            onProgress(step, 1, 1)

            step = PackStep.ICONS
            val iconBytes = HashMap<Int, ByteArray>(request.icons.size)
            request.icons.forEachIndexed { index, icon ->
                onProgress(step, index, request.icons.size)
                iconBytes[icon.slot] = iconProcessor.encode(icon.sourcePath)
            }
            onProgress(step, request.icons.size, request.icons.size)

            step = PackStep.ZIP
            val unsignedBytes = PackAssembler.buildUnsigned(
                template = template,
                packName = request.packName,
                versionCode = request.versionCode,
                versionName = request.versionName,
                mappings = request.mappings,
                iconBytes = iconBytes,
                packIconBytes = request.packIconBytes,
                showInDrawer = request.showInDrawer,
                packageName = request.packageName,
                minSdkOverride = request.overrideMinSdk,
                targetSdkOverride = request.overrideTargetSdk,
                onProgress = onProgress,
            )
            unsigned.writeBytes(unsignedBytes)

            step = PackStep.SIGN
            onProgress(step, 0, 1)
            val signerSubject = signerWrapper.sign(
                unsigned,
                File(request.outputPath),
                identity,
                request.signingSchemes,
            )
            onProgress(step, 1, 1)

            onProgress(PackStep.DONE, 1, 1)
            PackResult.Success(
                outputPath = request.outputPath,
                sizeBytes = File(request.outputPath).length(),
                signerSubject = signerSubject,
                versionCode = request.versionCode,
                versionName = request.versionName,
                packageName = request.packageName,
            )
        } catch (e: Throwable) {
            PackResult.Failure(step, e.message ?: e.javaClass.simpleName, e)
        } finally {
            unsigned.delete()
        }
    }
}

/**
 * 未签名 APK 组装（纯 Kotlin，零 Android 依赖，可 JVM 单测）。
 *
 * 输入模板 APK 与预处理后的图标字节，输出"待签名"APK 字节：
 * 1. MANIFEST：提取模板清单 → 补丁 label / package / versionCode / versionName / uses-sdk；
 * 2. AXML：生成 appfilter.xml（映射）+ drawable.xml（图标清单）+ appmap.xml（组件映射）；
 * 3. ZIP：重写模板——替换 drawable 槽位与上述三个条目，剥离 META-INF/ 调试签名。
 *
 * 图标槽位目录与启动器图标条目都不硬编码，而是从模板条目探测（见 [probeIconDir]）：
 * aapt2 会按源工程限定符改写目录名，写错会让图标变成
 * arsc 不认识的孤儿条目，且不会报任何错。
 *
 * 条目名常量与 template 模块的资源布局一一对应，改动需同步两边。
 */
internal object PackAssembler {

    fun buildUnsigned(
        template: ByteArray,
        packName: String,
        versionCode: Int,
        versionName: String = TemplateInfo.DEFAULT_VERSION_NAME,
        mappings: List<IconMapping>,
        iconBytes: Map<Int, ByteArray>,
        packIconBytes: ByteArray? = null,
        showInDrawer: Boolean = true,
        packageName: String = TemplateInfo.PACKAGE_NAME,
        minSdkOverride: Int? = null,
        targetSdkOverride: Int? = null,
        onProgress: PackProgress = { _, _, _ -> },
    ): ByteArray {
        // 包名是产物的系统身份：非法值会产出装不上、或装上去覆盖掉别的包的 APK，
        // 而产物自身完全正常、从文件表面看不出异常。宁可这里直接失败。
        PackageNameRules.requireValid(packageName)
        onProgress(PackStep.MANIFEST, 0, 1)
        val manifest = ZipUtils.readEntry(template, MANIFEST_ENTRY)
            ?: error("模板缺少 AndroidManifest.xml 条目")
        // 包名决定产物在系统里的身份：不同项目必须拿到不同包名才能共存。
        // 放在最内层先改，与 label/版本信息的改写相互独立（改的是不同属性）。
        val base = AxmlPatcher.setVersionName(
            AxmlPatcher.setVersionCode(
                AxmlPatcher.replaceLabel(
                    AxmlPatcher.setPackageName(manifest, packageName),
                    packName,
                ),
                versionCode,
            ),
            versionName,
        )
        // 版本名依赖模板清单里已有 android:versionName 字符串属性。真实模板由 aapt2
        // 从 defaultConfig 注入（实测为 "1.0"）；不含该属性的第三方 / 旧模板与抽屉
        // 可见性同理**降级为保持模板原值**，不让整个打包失败。真实模板的写入路径由
        // RealTemplateTest 的版本用例锁死，降级不会把"真该写入却没写"藏起来。
        val versioned = try {
            AxmlPatcher.setVersionName(base, versionName)
        } catch (e: IllegalStateException) {
            base
        }
        // 抽屉可见性依赖模板的主入口 <category>。若模板不含该元素，或文档中第一个
        // <category> 不是抽屉入口（旧模板 / 第三方模板 / 模板 filter 顺序被改坏），
        // 切换既无意义又会连累识别声明——抛 IllegalStateException 后降级为保持
        // 模板原样，不让整个打包失败。真实模板的写入路径由 RealTemplateTest 的
        // 抽屉用例锁死，降级不会把"真该写入却没写"藏起来。
        val patched = try {
            AxmlPatcher.setDrawerEntryVisible(versioned, showInDrawer)
        } catch (e: IllegalStateException) {
            versioned
        }
        // SDK 重写依赖模板清单里的 <uses-sdk>（aapt2 从 defaultConfig 注入 min/target）。
        // 只重写用户提供的那一侧（null 侧保持模板值不动）。合成/第三方模板缺该元素
        // 或属性类型不对时逐项降级为保持模板原值——与 versionName/抽屉开关同一条
        // 纪律；真实模板的写入路径由 RealTemplateTest 的 SDK 用例锁死。
        val sdkPatched = patchUsesSdk(patched, minSdkOverride, targetSdkOverride)
        onProgress(PackStep.MANIFEST, 1, 1)

        onProgress(PackStep.AXML, 0, 1)
        val appfilter = AxmlWriter().write(AppFilterFactory.buildAppFilter(mappings))
        val drawableList = AxmlWriter().write(AppFilterFactory.buildDrawableList(iconBytes.keys))
        val appmapList = AxmlWriter().write(AppFilterFactory.buildAppMap(mappings))
        onProgress(PackStep.AXML, 1, 1)

        onProgress(PackStep.ZIP, 0, 1)
        // 图标条目目录必须按模板实际登记的路径来。目录写错时 ZipRewriter 会把用户图标
        // 当成"新条目"追加到 ZIP 末尾，而 arsc 仍指向原占位图——产物照样能装、图标包照样被
        // 识别、映射表照样能读，只是图标永远显示为占位图，从产物表面完全看不出异常。
        // 故这里探测 + 强校验：宁可打包失败，也不产出"图标没生效"的坏件。
        val iconDir = probeIconDir(template)
        val templateEntries = ZipUtils.entryNames(template).toHashSet()
        val missing = iconBytes.keys.filterNot { templateEntries.contains(iconDir + AppFilterFactory.slotFileName(it)) }
        require(missing.isEmpty()) {
            val list = missing.sorted().take(8).joinToString { AppFilterFactory.slotFileName(it) }
            "模板缺少图标槽位条目：$list" +
                (if (missing.size > 8) " 等共 ${missing.size} 个" else "") +
                "（探测到的图标目录：$iconDir）"
        }

        val rewriter = ZipRewriter(template)
            .replace(MANIFEST_ENTRY, sdkPatched)
            .replace(APPFILTER_ENTRY, appfilter)
            .replace(DRAWABLE_LIST_ENTRY, drawableList)
            .dropPrefix(META_INF_PREFIX)
        // appmap.xml 仅在模板已登记该资源（含 arsc 条目）时替换：启动器靠
        // getIdentifier("appmap", "xml") 取路径，arsc 里没有登记时新写入的文件
        // 永远不被发现。合成/第三方模板缺省即降级跳过（真实模板由 RealTemplateTest 锁死）。
        if (templateEntries.contains(APPMAP_ENTRY)) {
            rewriter.replace(APPMAP_ENTRY, appmapList)
        }
        // arsc 的包名字符串必须与 manifest 同步改写：getIdentifier 按 arsc 里的
        // 包名字符串精确匹配（AssetManager2::GetResourceId），不改的产物装得上、
        // 能被识别，但启动器按包名找 appfilter/图标一律落空——"没有任何可以替换的图标"。
        // 详见 arsc/ArscPatcher 头注释。沿用 STORED 压缩方式由 ZipRewriter 保证。
        // 无 arsc 的合成/第三方模板降级跳过（真实模板必有 arsc，由 RealTemplateTest 锁死）。
        ZipUtils.readEntry(template, ARSC_ENTRY)?.let { templateArsc ->
            rewriter.replacePreservingCompression(
                ARSC_ENTRY,
                ArscPatcher.setPackageName(templateArsc, packageName),
            )
        }
        // 图标包自身的启动器图标：未提供则沿用模板占位图。
        // 启动器图标在模板里是多密度（hdpi..xxxhdpi）的 webp，逐密度条目都要替换，
        // 否则高密设备仍显示占位图（同名字节，密度无关）。
        if (packIconBytes != null) {
            val launcherEntries = ZipUtils.entryNames(template)
                .filter { it.endsWith("/$LAUNCHER_ICON_FILE") }
            require(launcherEntries.isNotEmpty()) {
                "模板中找不到启动器图标条目 $LAUNCHER_ICON_FILE，无法写入图标包自身图标"
            }
            launcherEntries.forEach { rewriter.replace(it, packIconBytes) }
        }
        iconBytes.forEach { (slot, bytes) ->
            rewriter.replace(iconDir + AppFilterFactory.slotFileName(slot), bytes)
        }
        val out = rewriter.build()
        onProgress(PackStep.ZIP, 1, 1)
        return out
    }

    /**
     * 从模板探测图标条目所在目录（返回带尾斜杠的目录前缀）。
     *
     * 以 1 号槽位的文件名为样本反查模板中的条目名，取其目录。找不到直接报错——
     * 模板与引擎的槽位命名不一致属于"不可能正确打包"的情形，不应降级继续，
     * 否则会产出图标全部停留在占位图的产物。
     */
    private fun probeIconDir(template: ByteArray): String {
        val sample = AppFilterFactory.slotFileName(1)
        val hit = ZipUtils.entryNames(template).firstOrNull { it.endsWith("/$sample") }
            ?: error("模板中找不到图标槽位条目 $sample，无法确定图标资源目录")
        return hit.substringBeforeLast('/') + "/"
    }

    // 启动器图标条目按文件名（含密度限定符）探测，见上方 buildUnsigned 中的 inline 处理。

    /**
     * 逐项重写 `<uses-sdk>`（仅用户提供的侧；模板缺该元素或属性类型不对时降级）。
     * 与整组一次性改写的区别：用户只重写 targetSdk 时不动 minSdk，避免把两边
     * 用同一份"模板基准值"硬编码进引擎——模板改了 defaultConfig 便会静默失配。
     */
    private fun patchUsesSdk(
        manifest: ByteArray,
        minSdkOverride: Int?,
        targetSdkOverride: Int?,
    ): ByteArray {
        var out = manifest
        if (minSdkOverride != null) {
            out = try {
                AxmlPatcher.setIntAttribute(out, "uses-sdk", "minSdkVersion", minSdkOverride)
            } catch (e: IllegalStateException) {
                out
            }
        }
        if (targetSdkOverride != null) {
            out = try {
                AxmlPatcher.setIntAttribute(out, "uses-sdk", "targetSdkVersion", targetSdkOverride)
            } catch (e: IllegalStateException) {
                out
            }
        }
        return out
    }

    private const val MANIFEST_ENTRY = "AndroidManifest.xml"
    private const val APPFILTER_ENTRY = "res/xml/appfilter.xml"
    private const val DRAWABLE_LIST_ENTRY = "res/xml/drawable.xml"
    private const val APPMAP_ENTRY = "res/xml/appmap.xml"
    private const val META_INF_PREFIX = "META-INF/"
    private const val ARSC_ENTRY = "resources.arsc"

    /** 启动器图标文件名（多密度 webp；探测见 buildUnsigned） */
    private const val LAUNCHER_ICON_FILE = "ic_launcher.webp"
}

/** 未签名中间产物后缀（[ApkBuilder.pack] 签名成功后删除） */
private const val UNSIGNED_SUFFIX = ".unsigned"
