# IconPacker release 混淆规则
#
# 关键约束：打包引擎在运行时依赖【资源名】与【类名】字符串解析，
# 这些字符串无法被 R8 静态追踪，因此相关成员必须显式 keep。

# ---- 模板资源槽位（引擎按 ic_%04d 名称定位并替换 ZIP 条目）----
# 槽位条目本身是文件字节替换，不涉及代码混淆；此处保留占位以便未来
# 若改为资源 ID 查找时可补规则。

# ---- AXML / ZIP / 签名引擎 ----
# apksig 内部大量使用反射（ASN.1 BER 解析、JCA Provider/Signature 动态查找），
# R8 静态分析无法覆盖。实测过收敛版 keep（只保留 ApkSigner/ApkVerifier + 子包）：
# 产物仅从 4,287,466 B 降到 4,271,081 B（-16KB，0.4%），却要承担"签名链路
# 运行期崩在某个未覆盖的反射点"的风险——收益与风险严重不对等，故全量保留。
-keep class com.muxiao.iconpacker.core.packer.** { *; }
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# ---- Room：实体与 DAO 由注解处理器生成，运行时经反射实例化 ----
-keep class com.muxiao.iconpacker.data.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { public *** getOpenHelper(); }
-dontwarn androidx.room.paging.**

# ---- 图标包 manifest 中的启动器 Activity 类名以字符串形式出现在
#      appfilter.xml 中（ComponentInfo{pkg/.Activity}），需保持类名不被改写 ----
-keep class com.muxiao.iconpacker.MainActivity { *; }

# ---- Compose / Kotlin 基础（AGP 内置 Kotlin 已带默认规则，此处仅兜底）----
-keepclassmembers class androidx.compose.** { *; }
-dontwarn org.jetbrains.annotations.**