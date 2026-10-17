package com.muxiao.iconpacker.core.packer.engine

/**
 * 模板模块与引擎之间的契约常量。
 *
 * [PACKAGE_NAME] 是**基础包名**，有两个用途：
 * 1. [PackageNameRules.suggest] 据此派生新项目的默认包名（`com.muxiao.icon.p<项目id>`）；
 * 2. 未显式指定包名时的兜底值。
 *
 * 打包时 `AxmlPatcher.setPackageName` 会把项目自己的包名写进产物清单，因此多个
 * 图标包可各自持有独立包名**并存安装**（详见 [PackageNameRules]）。
 * ⚠️ 改写 manifest 的同时**必须**用 arsc/ArscPatcher 把 resources.arsc 里的包名
 * 字符串一并改写：getIdentifier 按 arsc 包名精确匹配（AssetManager2::GetResourceId），
 * 只改 manifest 的产物装得上、能被识别，但启动器按包名找 appfilter/图标一律落空
 * ——桌面表现为"图标包没有任何可以替换的图标"（真机 ColorOS 实测踩过）。
 *
 * ⚠️ 改名成本与影响：**只改这里 + template 模块的 applicationId/namespace 两处即可**，
 * 引擎与启动器都不看这个值（启动器只认 appfilter 里的组件名）。改名只会换掉
 * 新项目默认包名的前缀，已安装的旧产物不受影响——它们的包名已固化在自己清单里。
 */
object TemplateInfo {
    const val PACKAGE_NAME = "com.muxiao.icon"

    /**
     * 模板 defaultConfig 里的 versionName，也是产物的**兜底版本名**。
     *
     * 与 [PACKAGE_NAME] 同理必须与 template 模块的 `defaultConfig.versionName` 一致：
     * 引擎按字符串属性替换该值，模板那边改了而这里没改，产物的版本名会退回模板值
     * （用户明明填了个版本名，系统里却显示别的）。
 */
    const val DEFAULT_VERSION_NAME = "1.0"
}
