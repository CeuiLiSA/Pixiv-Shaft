package ceui.pixiv.services

import android.content.Context
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.ComicBookmarkDao
import ceui.lisa.database.ComicReadingStatsDao
import ceui.lisa.database.DailyReadingStatsDao
import ceui.lisa.database.DownloadDao
import ceui.lisa.database.NovelAnnotationDao
import ceui.lisa.database.NovelBookmarkDao
import ceui.lisa.database.NovelCustomFontDao
import ceui.lisa.database.NovelCustomThemeDao
import ceui.lisa.database.NovelReadingStatsDao
import ceui.lisa.database.SearchDao
import ceui.pixiv.chat.data.ChatDatabase
import ceui.pixiv.chat.data.ChatMessageDao
import ceui.pixiv.communication.CommunicationScope
import ceui.pixiv.communication.EventPublisher
import ceui.pixiv.communication.EventSource
import ceui.pixiv.db.DiscoveryDao
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.db.GeneralDao
import ceui.pixiv.db.mirror.BookmarkMirrorDao
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.synonym.SynonymDao
import ceui.pixiv.ui.bulk.UgoiraVariantChanged
import com.tencent.mmkv.MMKV
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
 * - 仍由 [ceui.lisa.activities.Shaft] 在 onCreate / 初始化阶段手工构造或持有的对象
 *   （[EntityWrapper]、[MMKV]、[CommunicationScope]、动图变体事件收发端），
 *   只转手、不加 `@Singleton`——单例语义由 Shaft 保证；
 * - Room：库与 DAO 由 Room 生成，没法加 `@Inject`。
 */
@Module
@InstallIn(SingletonComponent::class)
object ServicesModule {

    @Provides
    fun entityWrapper(@ApplicationContext context: Context): EntityWrapper =
        context.appServices().entityWrapper

    @Provides
    fun prefStore(@ApplicationContext context: Context): MMKV =
        context.appServices().prefStore

    @Provides
    fun communicationScope(@ApplicationContext context: Context): CommunicationScope =
        context.appServices().communicationScope

    @Provides
    fun ugoiraVariantChangePublisher(
        @ApplicationContext context: Context,
    ): EventPublisher<UgoiraVariantChanged> =
        context.appServices().ugoiraVariantChangePublisher

    @Provides
    fun ugoiraVariantChanges(
        @ApplicationContext context: Context,
    ): EventSource<UgoiraVariantChanged> =
        context.appServices().ugoiraVariantChanges

    /**
     * 只转手：[AppDatabase.getAppDatabase] 自己持有进程单例。首次调用会建库，
     * 注入方想保持懒加载就用 `dagger.Lazy<…Dao>`。
     */
    @Provides
    fun appDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getAppDatabase(context)

    @Provides
    fun downloadQueueDao(db: AppDatabase): DownloadQueueDao = db.downloadQueueDao()

    /** 已完成 tab 的作品数要查 illust_download_table。 */
    @Provides
    fun downloadDao(db: AppDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun synonymDao(db: AppDatabase): SynonymDao = db.synonymDao()

    @Provides
    fun searchDao(db: AppDatabase): SearchDao = db.searchDao()

    @Provides
    fun generalDao(db: AppDatabase): GeneralDao = db.generalDao()

    @Provides
    fun discoveryDao(db: AppDatabase): DiscoveryDao = db.discoveryDao()

    @Provides
    fun novelBookmarkDao(db: AppDatabase): NovelBookmarkDao = db.novelBookmarkDao()

    @Provides
    fun novelAnnotationDao(db: AppDatabase): NovelAnnotationDao = db.novelAnnotationDao()

    @Provides
    fun novelReadingStatsDao(db: AppDatabase): NovelReadingStatsDao = db.novelReadingStatsDao()

    @Provides
    fun dailyReadingStatsDao(db: AppDatabase): DailyReadingStatsDao = db.dailyReadingStatsDao()

    @Provides
    fun novelCustomThemeDao(db: AppDatabase): NovelCustomThemeDao = db.novelCustomThemeDao()

    @Provides
    fun novelCustomFontDao(db: AppDatabase): NovelCustomFontDao = db.novelCustomFontDao()

    @Provides
    fun comicBookmarkDao(db: AppDatabase): ComicBookmarkDao = db.comicBookmarkDao()

    @Provides
    fun bookmarkMirrorDao(db: AppDatabase): BookmarkMirrorDao = db.bookmarkMirrorDao()

    @Provides
    fun comicReadingStatsDao(db: AppDatabase): ComicReadingStatsDao = db.comicReadingStatsDao()

    /**
     * 只转手：[ChatDatabase.getInstance] 自己持有进程单例；注入方想延迟开库用 `dagger.Lazy<ChatMessageDao>`。
     */
    @Provides
    fun chatDatabase(@ApplicationContext context: Context): ChatDatabase =
        ChatDatabase.getInstance(context)

    @Provides
    fun chatMessageDao(db: ChatDatabase): ChatMessageDao = db.chatMessageDao()
}
