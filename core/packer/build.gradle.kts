plugins {
    alias(libs.plugins.android.library)
    // AGP 9.0 起 Kotlin 内置于 AGP，禁止再应用 org.jetbrains.kotlin.android（见根构建脚本注释）
}

android {
    namespace = "com.muxiao.iconpacker.core.packer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.apksig.android)
    testImplementation(libs.junit)
}

// 真实模板 fixture 从 :template 产物自动同步，禁止手工拷贝。
val syncTemplateFixture = tasks.register<Copy>("syncTemplateFixture") {
    description = "Sync the real template APK from :template into test resources"
    dependsOn(":template:assembleDebug")
    from(rootProject.file("template/build/outputs/apk/debug"))
    include("template-debug.apk")
    into("src/test/resources")
    rename { "template.apk" }
}
tasks.named("preBuild") { dependsOn(syncTemplateFixture) }
