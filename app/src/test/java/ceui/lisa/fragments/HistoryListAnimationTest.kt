package ceui.lisa.fragments

import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import ceui.lisa.R
import ceui.pixiv.feeds.R as FeedsR
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.Settings
import ceui.pixiv.feeds.FeedFragment
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HistoryListAnimationTest.TestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HistoryListAnimationTest {
    // 必须是 Shaft：AndroidViewModelFactory 是静态单例，会缓存首个 Application 并跨测试类留在同一 SDK 沙箱里。
    // 这里若用 android.app.Application，排在后面的 ImageDetail*Test 建 ImageTranslationViewModel 时
    // appServices() 强转 ServicesProvider 就会 ClassCastException。
    class TestApplication : Shaft() {
        override fun onCreate() = Unit // 列表动画测试不需要原生服务与网络初始化。
    }

    private lateinit var db: AppDatabase
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Shaft.sSettings = Settings()
        Shaft.sGson = Gson()
    }

    @After
    fun tearDown() {
        AppDatabase.destroyInstance()
        db.close()
    }

    @Test
    fun `FragmentHistoryList retains itemAnimator and enables resetScrollOnRefresh`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val activity = controller.get()

        val fragment = FragmentHistoryList.newInstance(0)
        activity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .commitNow()

        val listView = fragment.requireView().findViewById<RecyclerView>(FeedsR.id.feed_list_view)
        assertNotNull("itemAnimator must NOT be null so item animations can run", listView.itemAnimator)

        val resetMethod = FeedFragment::class.java.getDeclaredMethod("getResetScrollOnRefresh")
        resetMethod.isAccessible = true
        assertTrue("resetScrollOnRefresh must be true", resetMethod.invoke(fragment) as Boolean)
    }

    @Test
    fun `FragmentHistoryUserList retains itemAnimator and enables resetScrollOnRefresh`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val activity = controller.get()

        val fragment = FragmentHistoryUserList()
        activity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .commitNow()

        val listView = fragment.requireView().findViewById<RecyclerView>(FeedsR.id.feed_list_view)
        assertNotNull("itemAnimator must NOT be null so item animations can run", listView.itemAnimator)

        val resetMethod = FeedFragment::class.java.getDeclaredMethod("getResetScrollOnRefresh")
        resetMethod.isAccessible = true
        assertTrue("resetScrollOnRefresh must be true", resetMethod.invoke(fragment) as Boolean)
    }
}
