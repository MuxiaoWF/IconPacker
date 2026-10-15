// Top-level build file where you can add configuration options common to all sub-projects/modules.
// 禁止再声明 org.jetbrains.kotlin.android——否则与内置 Kotlin 抢占 'kotlin' 扩展报
// InvalidPluginException: Cannot add extension with name 'kotlin'
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
