package com.muxiao.iconpacker

import android.app.Application
import android.content.Context
import com.muxiao.iconpacker.data.AppInfoProvider
import com.muxiao.iconpacker.data.IconFileStore
import com.muxiao.iconpacker.data.IconPackerDatabase
import com.muxiao.iconpacker.data.InstalledPackChecker
import com.muxiao.iconpacker.data.PackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手动依赖注入（无 Hilt：本项目依赖面很小，手写 ServiceLocator 可读性更好，
 * 也避免引入第二注解处理器叠加构建风险）。
 */
object ServiceLocator {
    lateinit var repository: PackRepository
        private set
    lateinit var installedPackChecker: InstalledPackChecker
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return
        val db = IconPackerDatabase.build(context)
        repository = PackRepository(
            packDao = db.packDao(),
            mappingDao = db.mappingDao(),
            assetDao = db.iconAssetDao(),
            fileStore = IconFileStore(context),
            appInfoProvider = AppInfoProvider(context),
        )
        installedPackChecker = InstalledPackChecker(context)
    }
}

class IconPackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // 存量维护：① 压缩升级前导入的相机原图（3.3MB/张 → 约 0.2MB）；
        // ② 清理历史上"删图标只删记录"留下的孤儿文件。幂等且全程 IO，
        // 失败静默——维护任务不该影响启动。
        maintenanceScope.launch {
            runCatching { ServiceLocator.repository.compressAllImportedSources() }
            runCatching { ServiceLocator.repository.cleanupAllOrphanIcons() }
        }
    }

    companion object {
        /** 维护任务作用域：只用于启动时的幂等清理，不需要随界面生命周期取消 */
        private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
