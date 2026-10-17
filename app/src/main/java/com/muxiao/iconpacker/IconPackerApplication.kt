package com.muxiao.iconpacker

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import com.muxiao.iconpacker.data.AppInfoProvider
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.IconFileStore
import com.muxiao.iconpacker.data.IconPackerDatabase
import com.muxiao.iconpacker.data.InstalledPackChecker
import com.muxiao.iconpacker.data.PackRepository
import com.muxiao.iconpacker.data.ProjectBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手动依赖注入（无 Hilt：本项目依赖面很小，手写 ServiceLocator 可读性更好，
 * 也避免引入第二注解处理器叠加构建风险）。
 *
 * 内存泄漏说明：[ServiceLocator] 是进程级单例（Kotlin `object` 即静态单例），
 * 其持有的 [repository] / [installedPackChecker] 都间接保有一个 `Context`。
 * 但 [init] 只会在 [IconPackerApplication.onCreate] 中以 `Application` 身份被调用，
 * 下方一律传入 `context.applicationContext`，所以锁存的是 **Application 上下文**——
 * 它与进程同寿，而 ServiceLocator 也随进程同寿，二者生命周期完全对齐，不存在泄漏。
 * 该 lint（StaticFieldLeak）无法静态证明"只持有 Application 上下文"，故在此显式抑制，
 * 属预期、可控、非隐患。
 */
@SuppressLint("StaticFieldLeak")
object ServiceLocator {
    lateinit var repository: PackRepository
        private set
    lateinit var installedPackChecker: InstalledPackChecker
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var projectBackup: ProjectBackup
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return
        // 只锁存 Application 上下文，杜绝把 Activity/View 等短生命周期上下文存进静态单例
        val appContext = context.applicationContext
        val db = IconPackerDatabase.build(appContext)
        val fileStore = IconFileStore(appContext)
        repository = PackRepository(
            packDao = db.packDao(),
            mappingDao = db.mappingDao(),
            assetDao = db.iconAssetDao(),
            fileStore = fileStore,
            appInfoProvider = AppInfoProvider(appContext),
        )
        installedPackChecker = InstalledPackChecker(appContext)
        settings = AppSettings(appContext)
        projectBackup = ProjectBackup(
            packDao = db.packDao(),
            mappingDao = db.mappingDao(),
            assetDao = db.iconAssetDao(),
            fileStore = fileStore,
            context = appContext,
        )
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
