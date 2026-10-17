plugins {
    alias(libs.plugins.android.application)
}

// 模板 APK 模块：IconPacker 打包引擎的"资源底座"。
// 本模块不依赖任何库、**不含任何 Kotlin 源码**（界面是纯 Java 的 MainActivity，
// 只用框架控件），仅由 aapt2 编译出：
//   1. 预声明 1024 个 drawable 槽位（ic_0001 ~ ic_1024）的 resources.arsc
//   2. 带图标包识别 intent-filter 的 AndroidManifest.xml（label 为原始字符串、
//      versionCode 为整数字面量、activity 名为全限定名——三者是 AxmlPatcher 的前置约束）
//   3. 空的 appfilter.xml / drawable.xml 占位（引擎打包时整体替换条目内容）
// 产物 template-debug.apk 由 app 模块的 copyTemplateApk 任务复制进 assets，
// 并由 core/packer 的 syncTemplateFixture 任务同步为单测 fixture。
//
// ⚠️ 图标资源目录必须带**密度限定符**（drawable-xxxhdpi / mipmap-xxxhdpi），
//    绝不可用 anydpi。原因：anydpi 编译出的 ResTable_config.density 是 ANY(0xFFFE=65534)，
//    Android 会把它当作 BitmapFactory 的 opts.inDensity，使 192px 图标在 320dpi 屏上
//    内禀尺寸塌成 192×320/65534 ≈ 1px——启动器只画得出 1×1 再放大，整张图标退化为
//    一块平均色（纯色方块）。占位图恰是单色，平均色等于自身，所以这个缺陷极难自查。
//    192px ↔ 640dpi ↔ 48dp，与启动器图标的标准尺寸严格对应；回归由
//    core/packer 的 RealTemplateTest「icon resources are density-qualified rather than anydpi」守住。
// 模板里没有任何 Kotlin 源码，但 AGP 的内置 Kotlin 会把 kotlin-stdlib 作为默认依赖链进来：
// 它会在**每个产物**里留下一份 2.6MB 的 classes.dex（压缩后仍约 0.8MB，占产物体积的六成以上），
// 而图标包运行时根本用不到。这里显式剔除——本模块是 application 模块，剔除只影响自己。
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

android {
    namespace = "com.muxiao.icon"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.muxiao.icon"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 模板是"资源底座"：1024 个占位槽位 ic_0001~ic_1024 必须原样保留，
    // 打包引擎只替换 PNG 字节、永不改写 resources.arsc。一旦被收缩器当 unused 丢弃即失效，
    // 故显式关闭收缩（默认本就关闭，这里写死以防误复用 app 的 release 配置）。
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    lint {
        // 占位槽位不会被任何代码/XML 引用（drawable.xml/appfilter.xml 是空占位），
        // "unused resource" 报红属预期误报；禁用以免误触 "Remove Unused Resources" 批量删除。
        disable += "UnusedResources"
        disable += "IconLauncherShape" // 占位图标铺满位图属预期（会被产物图标整体替换）
    }
}
