package com.muxiao.iconpacker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.muxiao.iconpacker.core.packer.engine.PackageNameRules
import kotlinx.coroutines.flow.Flow

@Dao
interface PackDao {

    @Query("SELECT * FROM packs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PackEntity>>

    /** 全部项目（存量维护任务用，需要一次性遍历而不是观察） */
    @Query("SELECT * FROM packs")
    suspend fun getAll(): List<PackEntity>

    @Query("SELECT * FROM packs WHERE id = :packId")
    fun observeById(packId: Long): Flow<PackEntity?>

    @Query("SELECT * FROM packs WHERE id = :packId")
    suspend fun getById(packId: Long): PackEntity?

    @Insert
    suspend fun insert(pack: PackEntity): Long

    @Update
    suspend fun update(pack: PackEntity)

    @Delete
    suspend fun delete(pack: PackEntity)

    /**
     * 打包提交版本号：把本次实际使用的版本号记为 [PackEntity.versionCode]，
     * 并清掉一次性的 [PackEntity.nextVersionCode]（用户显式指定只生效一次，
     * 之后回到自动 +1）。
     *
     * 刻意在**打包开始前**落库（而不是成功后）：若构建失败，只是跳过一个号；
     * 反过来"成功后忘递增"会直接导致下次产物装不上（versionCode 不高于已装版本）。
     */
    @Query(
        "UPDATE packs SET versionCode = :versionCode, nextVersionCode = NULL, updatedAt = :now " +
            "WHERE id = :packId",
    )
    suspend fun commitVersion(packId: Long, versionCode: Int, now: Long)

    /**
     * 设置版本信息（FR-02 补充）。
     *
     * [nextVersionCode] 传 null 表示"回到自动递增"——界面上的版本号输入框留空或
     * 恰好等于自动值时就传 null，避免多出一条无意义的显式指定。
     */
    @Query(
        "UPDATE packs SET versionName = :versionName, nextVersionCode = :nextVersionCode, " +
            "updatedAt = :now WHERE id = :packId",
    )
    suspend fun setVersionInfo(packId: Long, versionName: String, nextVersionCode: Int?, now: Long)

    @Query("UPDATE packs SET lastPackedAt = :now, updatedAt = :now WHERE id = :packId")
    suspend fun markPacked(packId: Long, now: Long)

    @Query("UPDATE packs SET name = :name, updatedAt = :now WHERE id = :packId")
    suspend fun rename(packId: Long, name: String, now: Long)

    /** 设置/清除图标包自身的启动器图标（path 为 null 表示回退到模板占位图） */
    @Query("UPDATE packs SET iconPath = :path, updatedAt = :now WHERE id = :packId")
    suspend fun setIcon(packId: Long, path: String?, now: Long)

    /** 设置图标包是否在应用抽屉显示 */
    @Query("UPDATE packs SET showInDrawer = :show, updatedAt = :now WHERE id = :packId")
    suspend fun setShowInDrawer(packId: Long, show: Boolean, now: Long)

    /**
     * 设置产物包名。
     *
     * 包名一旦写进产物清单就与已安装应用绑定，改动等同于"换一个应用"（旧包不会被覆盖，
     * 新版会另装一个）。因此调用方必须先用 [PackageNameRules] 校验语法、
     * 并用 [findOtherWithPackageName] 检查占用——见 `PackRepository.setPackageName`。
     */
    @Query("UPDATE packs SET packageName = :packageName, updatedAt = :now WHERE id = :packId")
    suspend fun setPackageName(packId: Long, packageName: String, now: Long)

    /**
     * 除自己以外占用同一包名的项目。
     *
     * 两个项目同包名 = 两套产物在系统里争同一个位置，后装的会覆盖先装的，
     * 所以这不是"允许但要提示"的可选项，而是必须阻断的冲突。
     */
    @Query("SELECT * FROM packs WHERE packageName = :packageName AND id != :excludePackId LIMIT 1")
    suspend fun findOtherWithPackageName(packageName: String, excludePackId: Long): PackEntity?

    /**
     * 新建项目并分配包名（同一事务）。
     *
     * 包名由自增 id 派生（id 唯一且不变，因此派生结果天然不重复），
     * 也就必须先插入拿到 id 再回写——放一个事务里，避免"项目已存在但包名还是默认值"
     * 的中间态被列表查询看到。
     */
    @Transaction
    suspend fun insertWithPackage(pack: PackEntity, now: Long): Long {
        val id = insert(pack)
        setPackageName(id, PackageNameRules.suggest(id), now)
        return id
    }
}

@Dao
interface MappingDao {

    @Query("SELECT * FROM mappings WHERE packId = :packId ORDER BY id")
    fun observeByPack(packId: Long): Flow<List<MappingEntity>>

    @Query("SELECT * FROM mappings WHERE packId = :packId ORDER BY id")
    suspend fun getByPack(packId: Long): List<MappingEntity>

    @Query("SELECT * FROM mappings WHERE packId = :packId AND component = :component LIMIT 1")
    suspend fun get(packId: Long, component: String): MappingEntity?

    /** 同组件重复指派即替换（唯一索引 packId+component） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: MappingEntity)

    @Insert
    suspend fun insertAll(mappings: List<MappingEntity>)

    @Query("DELETE FROM mappings WHERE packId = :packId AND component = :component")
    suspend fun delete(packId: Long, component: String)

    @Query("DELETE FROM mappings WHERE packId = :packId")
    suspend fun deleteByPack(packId: Long)
}

@Dao
interface IconAssetDao {

    @Query("SELECT * FROM icon_assets WHERE packId = :packId ORDER BY slot")
    fun observeByPack(packId: Long): Flow<List<IconAssetEntity>>

    @Query("SELECT * FROM icon_assets WHERE packId = :packId ORDER BY slot")
    suspend fun getByPack(packId: Long): List<IconAssetEntity>

    @Query("SELECT * FROM icon_assets WHERE packId = :packId AND slot = :slot")
    suspend fun get(packId: Long, slot: Int): IconAssetEntity?

    @Query("UPDATE icon_assets SET editedPath = :path WHERE packId = :packId AND slot = :slot")
    suspend fun updateEdit(packId: Long, slot: Int, path: String?)

    /**
     * 改写原图路径。仅由"导入压缩"的存量清理使用：压缩可能把 .jpg 换成 .webp，
     * 文件名变了就必须同步落库，否则打包与编辑都会找不到文件。
     */
    @Query("UPDATE icon_assets SET sourcePath = :path WHERE packId = :packId AND slot = :slot")
    suspend fun updateSource(packId: Long, slot: Int, path: String)

    @Insert
    suspend fun insertAll(assets: List<IconAssetEntity>): List<Long>

    @Query("DELETE FROM icon_assets WHERE packId = :packId AND slot = :slot")
    suspend fun delete(packId: Long, slot: Int)

    @Query("DELETE FROM icon_assets WHERE packId = :packId")
    suspend fun deleteByPack(packId: Long)
}
