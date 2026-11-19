package ceui.pixiv.ui.novel

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.RecyNovelBinding
import ceui.lisa.utils.Settings
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.session.WebSessionManagerTest.MemoryMMKV
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.NovelMuteStore
import ceui.pixiv.ui.novel.reader.ReaderProgressStore
import com.blankj.utilcode.util.Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [24, 35],
    application = Application::class,
    qualifiers = "w320dp-h640dp",
    shadows = [MemoryMMKV::class],
    instrumentedPackages = ["com.tencent.mmkv"],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelReadingProgressTest {

    class Host : NovelFeedFragment() {
        override val feedViewModel: FeedViewModel<String> get() = error("Renderer test has no data source")
        fun renderer() = novelCardRenderer()
    }

    private fun initApp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
    }

    @Before
    fun setUp() {
        MemoryMMKV.values.clear()
        ReaderProgressStore.resetForTest()
    }

    @After
    fun tearDown() {
        MemoryMMKV.values.clear()
        ReaderProgressStore.resetForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `cold start loadFromDisk indexes persisted progress and ignores series keys`() {
        MemoryMMKV.values["char_101"] = 2800
        MemoryMMKV.values["page_101"] = 11
        MemoryMMKV.values["total_101"] = 30
        MemoryMMKV.values["time_101"] = 1_700_000_000_000L
        // Scroll-mode entry (total = 0)
        MemoryMMKV.values["char_202"] = 3470
        MemoryMMKV.values["page_202"] = 0
        MemoryMMKV.values["total_202"] = 0
        MemoryMMKV.values["time_202"] = 1_700_000_001_000L
        // Negative local-novel id
        MemoryMMKV.values["char_-99"] = 500
        MemoryMMKV.values["page_-99"] = 2
        MemoryMMKV.values["total_-99"] = 10
        // Series last-opened key must be ignored
        MemoryMMKV.values["series_last_500"] = 101L

        // Before loadFromDisk: peek is non-blocking (returns null), direct load still reads MMKV
        assertNull(ReaderProgressStore.peekReadingPercent(101L, 6940))
        assertEquals(2800, ReaderProgressStore.loadCharIndex(101L))

        val revBefore = ReaderProgressStore.revision
        ReaderProgressStore.loadFromDisk()
        assertTrue(ReaderProgressStore.revision > revBefore)

        assertEquals(40, ReaderProgressStore.peekReadingPercent(101L, 6940))
        assertEquals(50, ReaderProgressStore.peekReadingPercent(202L, 6940))
        assertEquals(30, ReaderProgressStore.peekReadingPercent(-99L, 1000))
        assertNull(ReaderProgressStore.peekReadingPercent(500L, 1000))
        assertNull(ReaderProgressStore.peekReadingPercent(999L, 1000))
    }

    @Test
    fun `writes and clears before loadFromDisk win over stale persisted entries`() {
        MemoryMMKV.values["char_10"] = 500
        MemoryMMKV.values["page_10"] = 1
        MemoryMMKV.values["total_10"] = 10
        MemoryMMKV.values["char_20"] = 1000
        MemoryMMKV.values["page_20"] = 4
        MemoryMMKV.values["total_20"] = 10

        // User advances novel 10 and clears novel 20 before warm-up completes
        ReaderProgressStore.saveProgress(10L, 4500, 8, 10)
        ReaderProgressStore.clear(20L)
        // Simulate stale disk snapshot by putting old values back in MMKV before loadFromDisk
        MemoryMMKV.values["char_10"] = 500
        MemoryMMKV.values["page_10"] = 1
        MemoryMMKV.values["char_20"] = 1000
        MemoryMMKV.values["page_20"] = 4

        ReaderProgressStore.loadFromDisk()

        assertEquals(90, ReaderProgressStore.peekReadingPercent(10L, 5000))
        assertNull(ReaderProgressStore.peekReadingPercent(20L, 5000))
    }

    @Test
    fun `identical progress save does not redundantly bump revision`() {
        ReaderProgressStore.loadFromDisk()
        val rev0 = ReaderProgressStore.revision
        ReaderProgressStore.saveProgress(42L, 1000, 3, 10)
        val rev1 = ReaderProgressStore.revision
        assertEquals(rev0 + 1, rev1)

        ReaderProgressStore.saveProgress(42L, 1000, 3, 10)
        assertEquals(rev1, ReaderProgressStore.revision)

        ReaderProgressStore.saveProgress(42L, 1500, 4, 10)
        assertEquals(rev1 + 1, ReaderProgressStore.revision)
    }

    @Test
    fun `all theme primaries and extremes satisfy WCAG AA contrast in light and dark mode`() {
        val primaries = intArrayOf(
            0xFF686BDD.toInt(), // 默认紫
            0xFFE91E63.toInt(), // 樱桃粉
            0xFF03D0BF.toInt(), // 青绿
            0xFFFEE65E.toInt(), // 盛夏黄（高感知亮度极端档）
            0xFF4CAF50.toInt(), // 绿
            0xFF1A237E.toInt(), // 深靛蓝
            0xFFFF6F3C.toInt(), // 活力橙
            0xFFFFFFFF.toInt(), // 纯白自定义 HEX
            0xFF000000.toInt(), // 纯黑自定义 HEX
        )
        val modes = listOf(
            Triple(false, 0xFFFAFAFA.toInt(), 0xFF1DA88A.toInt()),
            Triple(true, 0xFF08080C.toInt(), 0xFF2DD4A8.toInt()),
        )
        for ((isDark, pageBg, green) in modes) {
            for (primary in primaries) {
                val p = NovelReadingProgressColors.resolve(primary, isDark, pageBg, green)
                val worstFill = ColorUtils.compositeColors(p.fillEnd, p.trackBg)
                val doneWorstFill = ColorUtils.compositeColors(p.doneFillEnd, p.doneTrackBg)

                assertTrue(
                    "in-progress text vs track failed for 0x${Integer.toHexString(primary)} dark=$isDark",
                    ColorUtils.calculateContrast(p.textColor, p.trackBg) >= 4.5,
                )
                assertTrue(
                    "in-progress text vs fill failed for 0x${Integer.toHexString(primary)} dark=$isDark",
                    ColorUtils.calculateContrast(p.textColor, worstFill) >= 4.5,
                )
                assertTrue(
                    "done text vs track failed for 0x${Integer.toHexString(primary)} dark=$isDark",
                    ColorUtils.calculateContrast(p.doneTextColor, p.doneTrackBg) >= 4.5,
                )
                assertTrue(
                    "done text vs fill failed for 0x${Integer.toHexString(primary)} dark=$isDark",
                    ColorUtils.calculateContrast(p.doneTextColor, doneWorstFill) >= 4.5,
                )
                assertTrue(
                    "done check vs badge failed for 0x${Integer.toHexString(primary)} dark=$isDark",
                    ColorUtils.calculateContrast(p.doneBadgeCheck, p.doneBadgeFill) >= 4.5,
                )
                assertTrue(
                    "glass ring arc vs glass failed for 0x${Integer.toHexString(primary)} dark=$isDark",
                    ColorUtils.calculateContrast(p.glassRingArc, 0xFF141418.toInt()) >= 4.5,
                )
            }
        }
    }

    @Test
    fun `novel card displays progress, handles reuse, hides under spoiler, and updates via payload`() {
        initApp()
        val wasLoaded = ReflectionHelpers.getField<Boolean>(NovelMuteStore, "loaded")
        ReflectionHelpers.setField(NovelMuteStore, "loaded", true)
        val muted = ReflectionHelpers.getField<MutableSet<Long>>(NovelMuteStore, "mutedIds")

        val readNovel = Novel(id = 13821L, title = "已读小说", text_length = 10000, visible = true)
        val unreadNovel = Novel(id = 13822L, title = "未读小说", text_length = 8000, visible = true)
        val hadMute = readNovel.id in muted

        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val activity = controller.get()
        val host = Host()
        activity.supportFragmentManager.beginTransaction().add(host, "progress")
            .setMaxLifecycle(host, Lifecycle.State.CREATED).commitNow()

        val adapter = FeedAdapter(listOf(host.renderer()))
        adapter.submitList(listOf(NovelFeedItem(readNovel), NovelFeedItem(unreadNovel)))
        val parent = FrameLayout(activity)
        val cell = adapter.createViewHolder(parent, 0)
        val binding = cell.binding as RecyNovelBinding
        val payload = ReflectionHelpers.getStaticField<Any>(
            Class.forName("ceui.pixiv.ui.common.NovelFeedFragmentKt"),
            "PAYLOAD_NOVEL_PROGRESS",
        )

        try {
            // 1. 初始未读 → GONE
            ReaderProgressStore.loadFromDisk()
            adapter.onBindViewHolder(cell, 0)
            assertEquals(View.GONE, binding.readingProgress.visibility)
            assertNull(binding.readingProgress.currentPercent())

            // 2. 写入 64% 阅读进度并走局部 payload 更新 → VISIBLE & 64%
            ReaderProgressStore.saveProgress(readNovel.id, 6400, 0, 0)
            adapter.onBindViewHolder(cell, 0, mutableListOf(payload))
            assertEquals(View.VISIBLE, binding.readingProgress.visibility)
            assertEquals(64, binding.readingProgress.currentPercent())
            assertNotNull(binding.readingProgress.contentDescription)

            // 3. 在 320dp / 2x 字体 / 日夜双模 / LTR & RTL 下完成测量与真实绘制不崩溃
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 2f
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                for (dir in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    for (pct in listOf(1, 64, 99, 100)) {
                        binding.readingProgress.layoutDirection = dir
                        binding.readingProgress.setProgress(pct)
                        renderOffscreen(binding.readingProgress, 200)
                    }
                }
            }
            // Glass pill 形态同样渲染校验
            val glassPill = NovelReadingProgressView(activity).apply {
                setStyleMode(NovelReadingProgressView.STYLE_GLASS_PILL)
            }
            for (pct in listOf(1, 50, 100)) {
                glassPill.setProgress(pct)
                renderOffscreen(glassPill, 180)
                assertTrue(glassPill.measuredWidth > 0)
                assertTrue(glassPill.measuredHeight > 0)
            }

            // 4. 屏蔽打码态必须隐藏进度，且 payload 刷新绝不泄密
            muted += readNovel.id
            adapter.onBindViewHolder(cell, 0)
            assertEquals(View.GONE, binding.readingProgress.visibility)
            adapter.onBindViewHolder(cell, 0, mutableListOf(payload))
            assertEquals(View.GONE, binding.readingProgress.visibility)

            // 5. 取消屏蔽 → 恢复进度显示
            muted -= readNovel.id
            adapter.onBindViewHolder(cell, 0)
            assertEquals(View.VISIBLE, binding.readingProgress.visibility)
            assertEquals(64, binding.readingProgress.currentPercent())

            // 6. Holder 复用到未读小说 → 彻底隐藏无残影
            adapter.onBindViewHolder(cell, 1)
            assertEquals(View.GONE, binding.readingProgress.visibility)
            assertNull(binding.readingProgress.currentPercent())
        } finally {
            if (hadMute) muted += readNovel.id else muted -= readNovel.id
            ReflectionHelpers.setField(NovelMuteStore, "loaded", wasLoaded)
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun renderOffscreen(view: View, maxWidthPx: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = view.measuredWidth.coerceAtLeast(1)
        val h = view.measuredHeight.coerceAtLeast(1)
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        bmp.recycle()
    }
}
