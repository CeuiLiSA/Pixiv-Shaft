package ceui.pixiv.services

import android.content.Context
import ceui.lisa.database.AppDatabase
import ceui.lisa.viewmodel.AppLevelState
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.synonym.SynonymDao
import ceui.pixiv.ui.bulk.QueueDownloadManager
import ceui.pixiv.ui.translate.MangaBatchTranslateCenter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * 把 [ServicesProvider] 里的进程级服务桥接进 Hilt 图。
 *
 * 构造与生命周期仍归 [ceui.lisa.activities.Shaft]（在 onCreate 里按顺序 new 出来，见
 * [ServicesProvider] 的注释）；这里只是转手，所以 **不加 `@Singleton`**——单例语义已由
 * Shaft 保证，再套一层 DoubleCheck 只是多一次加锁。
 *
 * 按需登记：只放已经有注入点在用的服务，别一次性把整个接口镜像过来。
 */
@Module
@InstallIn(SingletonComponent::class)
object ServicesModule {

    @Provides
    fun servicesProvider(@ApplicationContext context: Context): ServicesProvider =
        context.appServices()

    @Provides
    fun appLevelState(services: ServicesProvider): AppLevelState =
        services.appLevelState

    @Provides
    fun entityWrapper(services: ServicesProvider): EntityWrapper =
        services.entityWrapper

    @Provides
    fun queueDownloadManager(services: ServicesProvider): QueueDownloadManager =
        services.queueDownloadManager

    @Provides
    fun mangaBatchTranslateCenter(services: ServicesProvider): MangaBatchTranslateCenter =
        services.mangaBatchTranslateCenter

    /**
     * 同样只是转手：[AppDatabase.getAppDatabase] 自己持有进程单例。首次调用会建库，
     * 注入方想保持懒加载就用 `dagger.Lazy<…Dao>`。
     */
    @Provides
    fun appDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getAppDatabase(context)

    @Provides
    fun downloadQueueDao(db: AppDatabase): DownloadQueueDao = db.downloadQueueDao()

    @Provides
    fun synonymDao(db: AppDatabase): SynonymDao = db.synonymDao()
}
