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
