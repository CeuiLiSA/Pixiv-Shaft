package ceui.lisa.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.databinding.CellHistoryIllustV3Binding
import ceui.lisa.databinding.RecyIllustStaggerBinding
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.snapshot.SnapshotIndex
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HistorySnapshotCardTest.TestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HistorySnapshotCardTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        app.setTheme(R.style.AppTheme)
        Shaft.sSettings = Settings()
        Shaft.sGson = Gson()
        SnapshotIndex.onSnapshotWritten(900001, "history-test", false, 1)
    }

    @After fun tearDown() {
        SnapshotIndex.onSnapshotDeleted("history-test")
    }

    @Test fun `selection and combined payloads hide and restore snapshot without rebinding cover`() {
        val fragment = FragmentHistoryList.newInstance(0)
        val adapter = FeedAdapter(listOf(fragment.historyIllustRenderer()))
        val item = HistoryIllustFeedItem(
            IllustHistoryEntity().apply { illustID = 900001 },
            Illust(id = 900001, visible = true),
        )
        adapter.submitList(listOf(item))
        val cell = adapter.createViewHolder(FrameLayout(app), 0)
        val binding = cell.binding as CellHistoryIllustV3Binding
        val coverSentinel = Any()
        binding.illustImage.tag = coverSentinel
        adapter.onBindViewHolder(cell, 0, mutableListOf(PAYLOAD_HISTORY_SNAPSHOT))
        assertEquals(View.VISIBLE, binding.snapshotButton.visibility)
        adapter.submitList(null)
        adapter.submitList(listOf(item.copy(isSelectionMode = true)))
        adapter.onBindViewHolder(cell, 0, mutableListOf(PAYLOAD_HISTORY_SELECTION))
        assertEquals(View.GONE, binding.snapshotButton.visibility)
        assertEquals(View.GONE, binding.deleteItem.visibility)
        assertEquals(View.VISIBLE, binding.selectCheck.visibility)
        adapter.submitList(null)
        adapter.submitList(listOf(item))
        adapter.onBindViewHolder(cell, 0, mutableListOf(PAYLOAD_HISTORY_SELECTION, PAYLOAD_HISTORY_SNAPSHOT))
        assertEquals(View.VISIBLE, binding.snapshotButton.visibility)
        assertEquals(View.VISIBLE, binding.deleteItem.visibility)
        assertEquals(View.GONE, binding.selectCheck.visibility)
        assertSame(coverSentinel, binding.illustImage.tag)
    }

    @Test fun `snapshot and like buttons fit narrow four column cards`() {
        val binding = RecyIllustStaggerBinding.inflate(LayoutInflater.from(app))
        binding.snapshotButton.visibility = View.VISIBLE
        binding.illustImage.setHeightRatio(1f)
        val density = app.resources.displayMetrics.density
        val width = (70 * density).toInt()
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        binding.root.layout(0, 0, width, binding.root.measuredHeight)
        assertInside(binding.root, binding.snapshotButton)
        assertInside(binding.root, binding.likeButton)
        assertTrue(binding.snapshotButton.width >= 48 * density)
        assertTrue(binding.snapshotButton.height >= 48 * density)
    }

    @Test fun `history actions fit dark narrow cards with large font and long titles`() {
        val config = android.content.res.Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            fontScale = 2f
        }
        val context = android.view.ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme)
        val binding = CellHistoryIllustV3Binding.inflate(LayoutInflater.from(context))
        binding.snapshotButton.visibility = View.VISIBLE
        binding.illustImage.setHeightRatio(0.5f)
        binding.title.text = "很长的作品标题 Long artwork title 123456789"
        binding.author.text = "很长的作者名称 Long author name"
        val density = context.resources.displayMetrics.density
        val width = (70 * density).toInt()
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        binding.root.layout(0, 0, width, binding.root.measuredHeight)
        assertInside(binding.root, binding.snapshotButton)
        assertInside(binding.root, binding.deleteItem)
        assertTrue(binding.snapshotButton.width >= 48 * density)
        assertTrue(binding.snapshotButton.height >= 48 * density)
        assertTrue(binding.snapshotButton.isFocusable)
    }

    @Test fun `hiding like keeps snapshot visible icon at the original right edge`() {
        val binding = RecyIllustStaggerBinding.inflate(LayoutInflater.from(app))
        binding.snapshotButton.visibility = View.VISIBLE
        binding.likeButton.visibility = View.GONE
        binding.illustImage.setHeightRatio(1f)
        val density = app.resources.displayMetrics.density
        val width = (160 * density).toInt()
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        binding.root.layout(0, 0, width, binding.root.measuredHeight)
        val icon = binding.snapshotButton.getChildAt(0)
        val bounds = android.graphics.Rect(0, 0, icon.width, icon.height)
        binding.root.offsetDescendantRectToMyCoords(icon, bounds)
        assertEquals(width - (4 * density).toInt(), bounds.right)
    }

    private fun assertInside(root: ViewGroup, child: View) {
        val bounds = android.graphics.Rect(0, 0, child.width, child.height)
        root.offsetDescendantRectToMyCoords(child, bounds)
        assertTrue("button clipped: $bounds root=${root.width}x${root.height}",
            bounds.left >= 0 && bounds.right <= root.width && bounds.top >= 0 && bounds.bottom <= root.height)
    }
}
