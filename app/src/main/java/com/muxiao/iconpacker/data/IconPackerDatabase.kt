package com.muxiao.iconpacker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * IconPacker 本地数据库（零网络、纯本地，符合 docs/README.md）。
 *
 * schema 导出关闭：本项目不分发 schema.json。结构变更**必须同时提供显式迁移**，
 * `fallbackToDestructiveMigration` 只作为迁移链断裂时的兜底——它会清空全部项目数据
 * （用户已导入的图标与映射），不能当作常规升级路径（见 docs/data-model.md）。
 */
@Database(
    entities = [PackEntity::class, MappingEntity::class, IconAssetEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class IconPackerDatabase : RoomDatabase() {

    abstract fun packDao(): PackDao
    abstract fun mappingDao(): MappingDao
    abstract fun iconAssetDao(): IconAssetDao

    companion object {

        /**
         * v4 → v5：packs 增加 `packageName`（多个图标包共存的基础）。
         *
         * 刻意用显式迁移而不是破坏性重建：此时用户很可能已经导入了一批图标、
         * 配好了映射，清库等于把这些工作全部丢掉。
         *
         * 存量项目统一回填模板基础包名 `com.muxiao.icon`：它们此前产出的 APK 就是这个
         * 包名，回填后与设备上已安装的那个保持一致，不会突然变成"未安装"。
         * 新建项目才由 `PackageNameRules.suggest` 派生唯一包名。
         *
         * DEFAULT 必须与 [PackEntity.packageName] 上的 `@ColumnInfo(defaultValue)`
         * 逐字一致，否则 Room 打开库时会判定"迁移未正确执行"并抛异常。
 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE packs ADD COLUMN packageName TEXT NOT NULL " +
                        "DEFAULT 'com.muxiao.icon'",
                )
            }
        }

        /**
         * v5 → v6：packs 增加 `versionName` 与 `nextVersionCode`（版本可自定义）。
         *
         * 同样是显式迁移：此时库里的项目带着用户导入的图标与配好的映射，不能清库。
         *
         * 两列的默认值都让存量项目**行为完全不变**：
         * - `versionName` 回填 `'1.0'`——它们此前产出的 APK 版本名就是模板值 1.0，
         *  回填后与设备上已装的那个显示一致；
         * - `nextVersionCode` 保持 NULL（可空、无默认值）——即"自动在 versionCode 上 +1"，
         *  与改动前的递增逻辑逐字一致。
         *
         * DEFAULT 必须与 [PackEntity.versionName] 上的 `@ColumnInfo(defaultValue)`
         * 逐字一致，否则 Room 打开库时会判定"迁移未正确执行"并抛异常。
 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE packs ADD COLUMN versionName TEXT NOT NULL DEFAULT '1.0'",
                )
                db.execSQL("ALTER TABLE packs ADD COLUMN nextVersionCode INTEGER")
            }
        }

        /**
         * v6 → v7：packs 增加签名配置族（签名密钥可自定义 + 签名者名 + 签名方案开关）。
         *
         * 全部默认值都让存量项目**行为完全不变**：
         * - `signMode=0`（设备密钥）、三个方案开关 `1`（三方案同签）与既有打包
         *  行为逐字一致；
         * - 密钥库/别名/密码/签名者名五列可空，存量行均为 NULL——取默认值语义。
         *
         * 可空列不加 DEFAULT、实体上也不写 `@ColumnInfo(defaultValue)`；
         * 非 NULL 列的 DEFAULT 必须与 `@ColumnInfo(defaultValue)` 逐字一致，
         * 否则 Room 打开库时会判定"迁移未正确执行"并抛异常。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE packs ADD COLUMN signMode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE packs ADD COLUMN keystorePath TEXT")
                db.execSQL("ALTER TABLE packs ADD COLUMN keystorePassword TEXT")
                db.execSQL("ALTER TABLE packs ADD COLUMN keystoreAlias TEXT")
                db.execSQL("ALTER TABLE packs ADD COLUMN keyPassword TEXT")
                db.execSQL("ALTER TABLE packs ADD COLUMN signerName TEXT")
                db.execSQL("ALTER TABLE packs ADD COLUMN signSchemeV1 INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE packs ADD COLUMN signSchemeV2 INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE packs ADD COLUMN signSchemeV3 INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v7 → v8：packs 增加 `minSdkOverride` / `targetSdkOverride`（产物 SDK 可重写）。
         *
         * 非空 INTEGER，**0 是"沿用模板值"的哨兵**（合法 SDK 值最低 24，0 不会歧义）。
         * DEFAULT 0 让存量项目行为完全不变——打开库时未写值的行即"不重写"。
         *
         * DEFAULT 必须与 [PackEntity] 两列上的 `@ColumnInfo(defaultValue)` 逐字一致，
         * 否则 Room 打开库时会判定"迁移未正确执行"并抛异常。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE packs ADD COLUMN minSdkOverride INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE packs ADD COLUMN targetSdkOverride INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): IconPackerDatabase =
            Room.databaseBuilder(context, IconPackerDatabase::class.java, "iconpacker.db")
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration(false)
                .build()
    }
}
