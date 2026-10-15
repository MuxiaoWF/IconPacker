pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        // google 仓库国内镜像（阿里云）
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // JDK 25 工具链自动解析兜底（AGP 9.4 要求）；开发机已装 JDK 25 时不触发下载
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        // apksig-android 仅在 JitPack 分发（打包引擎签名链依赖；timart 无此依赖故未列）
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "IconPacker"
include(":app")
include(":core:packer")
include(":template")
