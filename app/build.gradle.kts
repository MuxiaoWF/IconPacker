plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.muxiao.iconpacker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.muxiao.iconpacker"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    // 应用运行时自行切换语言（LocaleMode），不依赖 Play 的语言（AAB）拆分；
    // 显式关闭语言拆分，同时消除 AppBundleLocaleChanges 警告。
    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        // 已启用运行时切换语言（LocaleMode），且不通过 Play 分发语言包：
        // bundle.language.enableSplit=false 已从构建层面关闭语言拆分，此抑制覆盖
        // IDE Inspect Code（它不读 bundle 配置）。
        disable += "AppBundleLocaleChanges"
        // 自有启动器图标本身就是位图铺满的设计（打包时由用户图标填充而非本图标），
        // 按设计规范重绘轮廓收益低；报的是每 DPI 一张的同一类建议，直接忽略。
        disable += "IconLauncherShape"
        // 文案对单复数不敏感（计数随用户动态变化，zh 无复数形态；en 中采用
        // "N" 直接陈述的写法），引入 plurals 需要连动两份资源与全部调用点，收益不匹配。
        disable += "PluralsCandidate"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/androidx/**",
                "META-INF/com/google/android/**",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "kotlin/**",
                "**/*.kotlin_builtins",
                "**/*.version",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.signingConfig.apply {
            enableV1Signing.set(true)
            enableV2Signing.set(true)
            enableV3Signing.set(true)
        }
    }
}

// 打包即跑单元测试，减少"打包后再手动上机验证"的步骤（app 与 core 引擎两层都覆盖）。
// 任一单测失败，assemble 直接失败，把回归挡在产出 APK 之前。
// 注：本工程 app / core 模块均只生成 testDebugUnitTest（单测与构建变体无关），统一跑它。
afterEvaluate {
    listOf("Debug", "Release").forEach { variant ->
        tasks.named("assemble$variant") {
            dependsOn("testDebugUnitTest", ":core:packer:testDebugUnitTest")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(project(":core:packer"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

// 模板 APK 资产：每次构建前从 :template 模块的产物复制到 app assets，
val copyTemplateApk = tasks.register<Copy>("copyTemplateApk") {
    description = "Copy template APK from :template module into app assets"
    dependsOn(":template:assembleDebug")
    from(rootProject.file("template/build/outputs/apk/debug"))
    include("template-debug.apk")
    into("src/main/assets")
    rename { "template.apk" }
}
tasks.named("preBuild") { dependsOn(copyTemplateApk) }
