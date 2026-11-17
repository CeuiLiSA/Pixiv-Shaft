package ceui.pixiv.services

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import ceui.lisa.database.AppDatabase
import ceui.pixiv.chat.data.ChatDatabase
import ceui.pixiv.communication.CommunicationScope
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.ui.bulk.ugoiraVariantChangeTopic
import com.tencent.mmkv.MMKV
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * 校验 [ServicesModule] 补齐后的全部绑定：
 * - [ServicesProvider] 转手项严格返回同一进程级实例（不做二次包装或新建）；
 * - [AppDatabase] 全部 15 个 DAO 与 [ChatDatabase] / [ChatMessageDao] 均直接委托给对应库单例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ServicesModuleTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var appDb: AppDatabase
    private lateinit var chatDb: ChatDatabase

    @Before
    fun setUp() {
        appDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatDb = Room.inMemoryDatabaseBuilder(app, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", appDb)
        ReflectionHelpers.setStaticField(ChatDatabase::class.java, "instance", chatDb)
    }

    @After
    fun tearDown() {
        AppDatabase.destroyInstance()
        appDb.close()
        ReflectionHelpers.setStaticField(ChatDatabase::class.java, "instance", null)
        chatDb.close()
    }

    @Test
    fun `appDatabase and all 15 DAOs resolve to the shared AppDatabase instance`() {
        assertSame(appDb, ServicesModule.appDatabase(app))
        assertSame(appDb.downloadQueueDao(), ServicesModule.downloadQueueDao(appDb))
        assertSame(appDb.downloadDao(), ServicesModule.downloadDao(appDb))
        assertSame(appDb.synonymDao(), ServicesModule.synonymDao(appDb))
        assertSame(appDb.searchDao(), ServicesModule.searchDao(appDb))
        assertSame(appDb.generalDao(), ServicesModule.generalDao(appDb))
        assertSame(appDb.discoveryDao(), ServicesModule.discoveryDao(appDb))
        assertSame(appDb.novelBookmarkDao(), ServicesModule.novelBookmarkDao(appDb))
        assertSame(appDb.novelAnnotationDao(), ServicesModule.novelAnnotationDao(appDb))
        assertSame(appDb.novelReadingStatsDao(), ServicesModule.novelReadingStatsDao(appDb))
        assertSame(appDb.dailyReadingStatsDao(), ServicesModule.dailyReadingStatsDao(appDb))
        assertSame(appDb.novelCustomThemeDao(), ServicesModule.novelCustomThemeDao(appDb))
        assertSame(appDb.novelCustomFontDao(), ServicesModule.novelCustomFontDao(appDb))
        assertSame(appDb.comicBookmarkDao(), ServicesModule.comicBookmarkDao(appDb))
        assertSame(appDb.bookmarkMirrorDao(), ServicesModule.bookmarkMirrorDao(appDb))
        assertSame(appDb.comicReadingStatsDao(), ServicesModule.comicReadingStatsDao(appDb))
    }

    @Test
    fun `chatDatabase and chatMessageDao resolve to the shared ChatDatabase instance`() {
        assertSame(chatDb, ServicesModule.chatDatabase(app))
        assertSame(chatDb.chatMessageDao(), ServicesModule.chatMessageDao(chatDb))
    }

    @Test
    fun `ServicesProvider bridges forward exact host instances`() {
        val scope = CommunicationScope()
        val topic = scope.ugoiraVariantChangeTopic()
        val wrapper = EntityWrapper(app)
        val mmkv = ReflectionHelpers.callConstructor(
            MMKV::class.java,
            ClassParameter.from(Long::class.javaPrimitiveType, 0L),
        )

        val provider = Proxy.newProxyInstance(
            ServicesProvider::class.java.classLoader,
            arrayOf(ServicesProvider::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getEntityWrapper" -> wrapper
                "getPrefStore" -> mmkv
                "getCommunicationScope" -> scope
                "getUgoiraVariantChangePublisher" -> topic.publisher
                "getUgoiraVariantChanges" -> topic.source
                "toString" -> "FakeServicesProvider"
                else -> error("unexpected ServicesProvider.${method.name}")
            }
        } as ServicesProvider

        val context: Context = object : ContextWrapper(app), ServicesProvider by provider {
            override fun getApplicationContext(): Context = this
        }

        assertSame(wrapper, ServicesModule.entityWrapper(context))
        assertSame(mmkv, ServicesModule.prefStore(context))
        assertSame(scope, ServicesModule.communicationScope(context))
        assertSame(topic.publisher, ServicesModule.ugoiraVariantChangePublisher(context))
        assertSame(topic.source, ServicesModule.ugoiraVariantChanges(context))
        scope.close()
    }
}
