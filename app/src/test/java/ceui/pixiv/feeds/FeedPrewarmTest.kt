package ceui.pixiv.feeds

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * 首屏预建（[FeedFragment.firstScreenPrewarmItemClass]）：按条目类型选 viewType，不按下标猜。
 * 原缺陷：写死预建 0 号 renderer，而 0 号常是页头（首页榜单条、详情页大图）——首页白建 8 个
 * 榜单条，真正铺满首屏的插画卡一个没建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FeedPrewarmTest {
    private lateinit var activity: ActivityController<FragmentActivity>

    @After
    fun tearDown() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

    @Test
    fun `页头占 0 号时预建落在声明的主力卡上`() {
        val fragment = open(PrewarmFeedFragment().apply { prewarmClass = Row::class.java })
        val pool = fragment.list.recycledViewPool
        assertEquals("页头一个都不预建", 0, pool.getRecycledViewCount(HEADER_TYPE))
        assertEquals("主力卡预建满一屏", 8, pool.getRecycledViewCount(ROW_TYPE))
    }

    @Test
    fun `没声明的页面不预建`() {
        val fragment = open(PrewarmFeedFragment())
        val pool = fragment.list.recycledViewPool
        assertEquals(0, pool.getRecycledViewCount(HEADER_TYPE))
        assertEquals(0, pool.getRecycledViewCount(ROW_TYPE))
    }

    @Test
    fun `声明的类型本页没注册时不预建`() {
        val fragment = open(PrewarmFeedFragment().apply { prewarmClass = Unregistered::class.java })
        val pool = fragment.list.recycledViewPool
        assertEquals(0, pool.getRecycledViewCount(HEADER_TYPE))
        assertEquals(0, pool.getRecycledViewCount(ROW_TYPE))
    }

    /** 打开页面并让首屏停在加载态（数据源不回包），跑够预建的分帧。 */
    private fun open(fragment: PrewarmFeedFragment): PrewarmFeedFragment {
        activity = Robolectric.buildActivity(FragmentActivity::class.java)
        activity.get().setTheme(R.style.AppTheme)
        activity.setup()
        activity.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment).commitNow()
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
        check(fragment.model.uiState.value.showFullscreenLoading) { "首屏应仍在加载态" }
        return fragment
    }

    data class Header(val id: Int) : FeedItem {
        override val feedKey: Any get() = id
    }

    data class Row(val id: Int) : FeedItem {
        override val feedKey: Any get() = id
    }

    data class Unregistered(val id: Int) : FeedItem {
        override val feedKey: Any get() = id
    }

    class TextBinding(private val text: TextView) : ViewBinding {
        override fun getRoot(): TextView = text
    }

    class PendingSource : FeedSource<String> {
        private val never = Channel<FeedPage<String>>()
        override suspend fun load(cursor: String?): FeedPage<String> = never.receive()
    }

    class PrewarmFeedFragment : FeedFragment() {
        var prewarmClass: Class<out FeedItem>? = null
        override val feedViewModel by feedViewModels { PendingSource() }
        val model get() = feedViewModel
        val list: RecyclerView get() = feedBinding.feedListView
        override val loadMoreEnabled = false
        override val refreshEnabled = false
        override val firstScreenPrewarmItemClass: Class<out FeedItem>? get() = prewarmClass
        override fun onCreateLayoutManager() = LinearLayoutManager(requireContext())
        override fun onCreateRenderers() = listOf(
            feedRenderer<Header, TextBinding>(inflate = { _, parent, _ ->
                TextBinding(TextView(parent.context))
            }) { },
            feedRenderer<Row, TextBinding>(inflate = { _, parent, _ ->
                TextBinding(TextView(parent.context))
            }) { },
        )
    }

    private companion object {
        const val HEADER_TYPE = 0
        const val ROW_TYPE = 1
    }
}
