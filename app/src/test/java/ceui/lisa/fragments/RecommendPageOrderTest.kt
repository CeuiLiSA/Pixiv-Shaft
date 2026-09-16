package ceui.lisa.fragments

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentLeftBinding
import ceui.lisa.databinding.FragmentSettingsAppearanceBinding
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.lisa.utils.Settings
import ceui.pixiv.ui.home.RecmdIllustFeedFragment
import ceui.pixiv.ui.home.RecmdNovelFeedFragment
import ceui.pixiv.ui.trending.HotTagsFeedFragment
import com.google.gson.Gson
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@Suppress("DEPRECATION")
class RecommendPageOrderTest {
    private var oldSettings: Settings? = null
    private var oldContext: Context? = null

    @Before
    fun setUp() {
        oldSettings = Shaft.sSettings
        oldContext = Shaft.getContext()
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        Shaft.sSettings = oldSettings
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", oldContext)
    }

    @Test
    fun `existing settings keep works first and both choices survive serialization`() {
        val gson = Gson()
        assertFalse(gson.fromJson("{}", Settings::class.java).isRecommendHotTagsFirst)
        for (tagsFirst in listOf(true, false)) {
            val settings = Settings().apply { isRecommendHotTagsFirst = tagsFirst }
            assertEquals(tagsFirst, gson.fromJson(gson.toJson(settings), Settings::class.java).isRecommendHotTagsFirst)
        }
    }

    @Test
    fun `both recommendation pagers match titles to content and restore by content identity`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
        val activity = controller.get()
        val context = ContextThemeWrapper(activity, R.style.AppTheme)
        val inflater = LayoutInflater.from(context)
        try {
            for (novel in listOf(false, true)) {
                for (tagsFirst in listOf(false, true)) {
                    Shaft.sSettings.isRecommendHotTagsFirst = tagsFirst
                    // Attach only the host; do not render feeds or start network/native storage.
                    val host = if (novel) NovelHost() else IllustHost()
                    activity.supportFragmentManager.beginTransaction().add(host, "host").commitNow()
                    val pager: ViewPager
                    if (host is IllustHost) {
                        val binding = FragmentLeftBinding.inflate(inflater)
                        ReflectionHelpers.setField(host, "baseBind", binding)
                        host.lazyData()
                        pager = binding.viewPager
                    } else {
                        val binding = ViewpagerWithTablayoutBinding.inflate(inflater)
                        ReflectionHelpers.setField(host, "baseBind", binding)
                        (host as NovelHost).initView()
                        pager = binding.viewPager
                    }
                    val adapter = pager.adapter as FragmentPagerAdapter
                    assertEquals(2, adapter.count)
                    for (position in 0..1) {
                        val isTags = (position == 0) == tagsFirst
                        assertEquals(context.getString(if (isTags) R.string.hot_tag else R.string.recommend_illust),
                            adapter.getPageTitle(position))
                        val expectedType = if (isTags) HotTagsFeedFragment::class.java
                            else if (novel) RecmdNovelFeedFragment::class.java else RecmdIllustFeedFragment::class.java
                        assertEquals(expectedType, adapter.getItem(position).javaClass)

                        // FragmentPagerAdapter restores fragments using the historical content IDs
                        // (works=0, tags=1), independent of their new positions.
                        val restored = Fragment()
                        val contentId = if (isTags) 1L else 0L
                        host.childFragmentManager.beginTransaction()
                            .add(restored, "android:switcher:${pager.id}:$contentId").commitNow()
                        adapter.startUpdate(pager)
                        assertSame(restored, adapter.instantiateItem(pager, position))
                        adapter.finishUpdate(pager)
                        assertSame("Refresh/reselect must use the restored instance", restored, adapter.getItem(position))
                    }
                    activity.supportFragmentManager.beginTransaction().remove(host).commitNow()
                }
            }
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `order choices fit a narrow settings row in both themes and enlarged fonts`() {
        val app = RuntimeEnvironment.getApplication()
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            for (scale in listOf(1f, 2f)) {
                for (language in listOf("zh", "en", "ja", "ko", "ru", "tr", "zh-TW")) {
                    val config = Configuration(app.resources.configuration).apply {
                        uiMode = Configuration.UI_MODE_TYPE_NORMAL or night
                        fontScale = scale
                        setLocale(Locale.forLanguageTag(language))
                    }
                    val context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme)
                    val binding = FragmentSettingsAppearanceBinding.inflate(LayoutInflater.from(context))
                    val row = binding.recommendPageOrderRela
                    val width = (320 * context.resources.displayMetrics.density).toInt()
                    for (choice in listOf(R.string.recommend_page_works_first, R.string.recommend_page_tags_first)) {
                        binding.recommendPageOrder.setText(choice)
                        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                        val texts = row.getChildAt(0) as ViewGroup
                        for (index in 0 until texts.childCount) {
                            val text = texts.getChildAt(index) as TextView
                            assertTrue(text.layout.lineCount > 0)
                            assertEquals(text.text.length, text.layout.getLineEnd(text.layout.lineCount - 1))
                            assertTrue(text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                            assertTrue(texts.top + text.bottom <= row.height - row.paddingBottom)
                        }
                    }
                }
            }
        }
    }

    class IllustHost : FragmentLeft() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View? = null
    }

    class NovelHost : FragmentNewNovel() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View? = null
    }
}
