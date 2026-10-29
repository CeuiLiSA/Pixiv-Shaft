package ceui.pixiv.services

import android.content.Context
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.synonym.SynonymDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Hilt 图里**不能**靠 `@Inject constructor` 自己建出来的那几样。
 *
 * [ServicesProvider] 里的服务大多已是 `@Singleton` + `@Inject constructor`，由 Hilt 直接构造，
 * 不在这里登记（再登记一遍就是重复绑定）。这里只剩：
 * - 仍由 [ceui.lisa.activities.Shaft] 在 onCreate 固定位置手工构造的（[EntityWrapper]），
 *   只转手、不加 `@Singleton`——单例语义由 Shaft 保证；
 * - Room：库与 DAO 由 Room 生成，没法加 `@Inject`。
 */
@Module
@InstallIn(SingletonComponent::class)
object ServicesModule {

    @Provides
    fun entityWrapper(@ApplicationContext context: Context): EntityWrapper =
        context.appServices().entityWrapper

    /**
     * 只转手：[AppDatabase.getAppDatabase] 自己持有进程单例。首次调用会建库，
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
