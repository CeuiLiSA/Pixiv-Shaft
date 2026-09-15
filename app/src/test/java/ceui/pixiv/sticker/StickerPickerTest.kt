package ceui.pixiv.sticker

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
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
                    val ripple = (cell.background as android.graphics.drawable.InsetDrawable).drawable as android.graphics.drawable.RippleDrawable
                    val selected = ripple.getDrawable(1).current as android.graphics.drawable.GradientDrawable
                    val palette = ceui.pixiv.witstudio.theme.V3Palette.from(context)
                    assertEquals("Selected fill must equal the host primary", palette.primary, selected.color!!.defaultColor)
                    assertEquals(palette.onPrimary, cell.currentTextColor)
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
                    val grid = descendants(root).filterIsInstance<RecyclerView>().single()
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
                    assertTrue("Tabs are to the right of close", tabBounds.left >= closeBounds.right)
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
                    assertSame("Switching tabs reuses the grid adapter", adapter, grid.adapter)
                    assertEquals("Reselection does not rebind visible images", 1, changes)
                    tabs.select(0)
                    assertEquals(2, changes)
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

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
