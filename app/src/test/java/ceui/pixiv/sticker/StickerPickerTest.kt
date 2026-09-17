package ceui.pixiv.sticker

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StickerPickerTest {
    @Test fun `inline picker gates readiness detaches when hidden and reuses tabs on reopen`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        try {
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
            val context = ContextThemeWrapper(activity, R.style.AppTheme_Index0)
            val container = InlineStickerContainer(context).apply { visibility = View.GONE }
            activity.setContentView(container)
            val state = MutableStateFlow<StickerState>(StickerState.Loading())
            var prepares = 0
            val picker = InlineStickerPicker(context, container, owner, state, {
                prepares++
                state.value = StickerState.Loading()
            }, {})
            container.onPanelVisibilityChanged = picker::setActive
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, prepares)
            container.visibility = View.VISIBLE
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, prepares)
            assertTrue(descendants(container).none { it is RecyclerView })
            val catalog = StickerCatalog(StickerVersions(emptyList()),
                StickerCatalog.TYPES.associateWith { StickerPack(emptyList(), emptyList(), emptyList()) })
            val ready = StickerState.Ready(StickerStore.Ready("verified", catalog, emptyMap()))
            state.value = ready
            shadowOf(Looper.getMainLooper()).idle()
            container.measure(View.MeasureSpec.makeMeasureSpec(dp(context, 320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(context, 270), View.MeasureSpec.EXACTLY))
            container.layout(0, 0, container.measuredWidth, container.measuredHeight)
            val grid = descendants(container).filterIsInstance<RecyclerView>().first { it.layoutManager is GridLayoutManager }
            val tabs = descendants(container).filterIsInstance<StickerCategoryTabs>().single()
            val pager = descendants(container).filterIsInstance<ViewPager2>().single()
            assertEquals(dp(context, 270), container.getChildAt(0).height)
            assertTrue(descendants(container).filterIsInstance<android.widget.TextView>()
                .none { it.text == context.getString(R.string.sticker_close) })
            tabs.select(2)
            // The chat coordinator owns the navigation/IME insets: do not add them a second time.
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, dp(context, 300))).build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(grid, insets)
            assertEquals(dp(context, 8), grid.paddingBottom)
            container.visibility = View.GONE
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, container.childCount)
            container.visibility = View.VISIBLE
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(descendants(container).none { it is RecyclerView })
            state.value = ready
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(pager, descendants(container).filterIsInstance<ViewPager2>().single())
            assertEquals(2, pager.currentItem)
            assertTrue(descendants(tabs).filterIsInstance<android.widget.TextView>().last().isSelected)
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, container.childCount)
            state.value = StickerState.Failed(java.io.IOException("Local files removed while in background"))
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(descendants(container).none { it is RecyclerView })
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, container.childCount)
        } finally { activity.finish() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `reader segment backgrounds follow host theme and stay compact`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            for (theme in listOf(R.style.AppTheme_Index0, R.style.AppTheme_Index3, R.style.AppTheme_Index4, R.style.AppTheme_Index5)) {
                val config = Configuration(activity.resources.configuration).apply { fontScale = 1f }
                val context = ContextThemeWrapper(activity.createConfigurationContext(config), theme)
                val tabs = StickerCategoryTabs(context) {}
                tabs.measure(View.MeasureSpec.makeMeasureSpec(dp(context, 320), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(context, 80), View.MeasureSpec.AT_MOST))
                tabs.layout(0, 0, tabs.measuredWidth, tabs.measuredHeight)
                val track = tabs.getChildAt(0)
                assertEquals(dp(context, 48), track.measuredHeight)
                assertTrue("Track wraps its labels instead of filling the sheet", track.measuredWidth < dp(context, 280))
                val cells = descendants(track).filterIsInstance<android.widget.TextView>()
                for (index in cells.indices) {
                    tabs.select(index)
                    val cell = cells[index]
                    assertTrue(cell.isSelected)
                    tabs.setScrollPosition(index, 0f)
                    val bitmap = android.graphics.Bitmap.createBitmap(track.width, track.height, android.graphics.Bitmap.Config.ARGB_8888)
                    track.draw(android.graphics.Canvas(bitmap))
                    val palette = ceui.pixiv.witstudio.theme.V3Palette.from(context)
                    assertEquals("Moving fill must equal the host primary", palette.primary,
                        bitmap.getPixel(cell.left + cell.width / 2, dp(context, 8)))
                    assertEquals(palette.onPrimary, cell.currentTextColor)
                    bitmap.recycle()
                }
            }
        } finally { activity.finish() }
    }

    @Test fun `panel stays absent until ready and closes on failure in both themes and large fonts`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = scale
                    uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.AppTheme)
                val state = MutableStateFlow<StickerState>(StickerState.Loading())
                val dialog = StickerPicker.show(context, state, {}, {})
                try {
                    shadowOf(Looper.getMainLooper()).idle()
                    val root = dialog.window!!.decorView
                    assertEquals(0, descendants(root).filterIsInstance<RecyclerView>().size)
                    val catalog = StickerCatalog(StickerVersions(emptyList()),
                        StickerCatalog.TYPES.associateWith { StickerPack(emptyList(), emptyList(), emptyList()) })
                    state.value = StickerState.Ready(StickerStore.Ready("verified", catalog, emptyMap()))
                    shadowOf(Looper.getMainLooper()).idle()
                    root.measure(View.MeasureSpec.makeMeasureSpec(dp(context, 320), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dp(context, 640), View.MeasureSpec.AT_MOST))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val grid = descendants(root).filterIsInstance<RecyclerView>().first { it.layoutManager is GridLayoutManager }
                    assertTrue("Grid has usable height", grid.measuredHeight > 0)
                    assertFalse("No visible title row", descendants(root).filterIsInstance<android.widget.TextView>()
                        .any { it.text == context.getString(R.string.sticker_title) })
                    val adapter = grid.adapter!!
                    val tabs = descendants(root).filterIsInstance<StickerCategoryTabs>().single()
                    val close = descendants(root).filterIsInstance<android.widget.TextView>()
                        .single { it.text == context.getString(R.string.sticker_close) }
                    val tabBounds = android.graphics.Rect()
                    val closeBounds = android.graphics.Rect()
                    tabs.getGlobalVisibleRect(tabBounds)
                    close.getGlobalVisibleRect(closeBounds)
                    assertTrue("Tabs share the close row", tabBounds.top < closeBounds.bottom && closeBounds.top < tabBounds.bottom)
                    assertTrue("Tabs are to the left of close", tabBounds.right <= closeBounds.left)
                    assertEquals("Tabs use the shared left inset", dp(context, 20), tabBounds.left)
                    val insets = androidx.core.view.WindowInsetsCompat.Builder()
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, dp(context, 24))).build()
                    val sheet = dialog.findViewById<ViewGroup>(com.google.android.material.R.id.design_bottom_sheet)!!
                    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(sheet, insets)
                    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(grid, insets)
                    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(grid, insets)
                    assertEquals("Surface must not reserve a fixed blank strip", 0, sheet.getChildAt(0).paddingBottom)
                    assertFalse("Items can draw through the bottom padding while scrolling", grid.clipToPadding)
                    assertEquals("Grid owns exactly one navigation inset", dp(context, 32), grid.paddingBottom)
                    var changes = 0
                    adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                        override fun onChanged() { changes++ }
                    })
                    tabs.select(2)
                    tabs.select(2)
                    val pager = descendants(root).filterIsInstance<ViewPager2>().single()
                    assertEquals(2, pager.currentItem)
                    assertSame("Each page retains its grid adapter", adapter, grid.adapter)
                    assertEquals("Switching categories does not replace grid contents", 0, changes)
                    tabs.select(0)
                    assertEquals(0, pager.currentItem)
                    assertEquals(0, changes)
                    // A real scroll layout verifies the final row can clear the gesture bar.
                    grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 100
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                            object : RecyclerView.ViewHolder(View(context).apply {
                                layoutParams = RecyclerView.LayoutParams(-1, dp(context, 48))
                            }) {}
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                    }
                    grid.scrollToPosition(99)
                    grid.measure(View.MeasureSpec.makeMeasureSpec(grid.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(grid.height, View.MeasureSpec.EXACTLY))
                    grid.layout(grid.left, grid.top, grid.right, grid.bottom)
                    val last = grid.layoutManager!!.findViewByPosition(99)!!
                    assertTrue("Last row stops above the safe area", last.bottom <= grid.height - grid.paddingBottom)
                    state.value = StickerState.Failed(java.io.IOException("Incomplete extraction"))
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(0, descendants(root).filterIsInstance<RecyclerView>().size)
                } finally { dialog.dismiss() }
            }
        } finally { activity.finish() }
    }


    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `indicator follows fractional progress in both directions and cancelled drags`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            for (rtl in listOf(false, true)) for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = scale
                    uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.AppTheme_Index0)
                val tabs = StickerCategoryTabs(context) {}
                tabs.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                tabs.measure(View.MeasureSpec.makeMeasureSpec(dp(context, 280), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(context, 100), View.MeasureSpec.AT_MOST))
                tabs.layout(0, 0, tabs.measuredWidth, tabs.measuredHeight)
                val track = tabs.getChildAt(0)
                val cells = descendants(track).filterIsInstance<android.widget.TextView>()
                val primary = ceui.pixiv.witstudio.theme.V3Palette.from(context).primary
                val bitmap = android.graphics.Bitmap.createBitmap(track.width, track.height, android.graphics.Bitmap.Config.ARGB_8888)
                for (progress in listOf(0f, .25f, .5f, .75f, 1f, 1.5f, 2f, 1.5f, 1f, .5f, 0f)) {
                    val index = progress.toInt()
                    val offset = progress - index
                    tabs.setScrollPosition(index, offset)
                    track.draw(android.graphics.Canvas(bitmap))
                    val from = cells[index]
                    val to = cells[(index + 1).coerceAtMost(2)]
                    val expectedCenter = ((from.left + from.right) * (1 - offset) + (to.left + to.right) * offset) / 2
                    val colored = (0 until bitmap.width).filter { bitmap.getPixel(it, dp(context, 8)) == primary }
                    assertTrue("Indicator exists at $progress", colored.isNotEmpty())
                    assertEquals("Indicator continuously follows page position $progress", expectedCenter,
                        (colored.first() + colored.last()) / 2f, 2f)
                }
                bitmap.recycle()
            }
        } finally { activity.finish() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
