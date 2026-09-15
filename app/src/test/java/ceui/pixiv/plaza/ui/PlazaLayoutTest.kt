package ceui.pixiv.plaza.ui

import android.app.Application
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.lisa.databinding.CellPlazaPostBinding
import ceui.lisa.databinding.FragmentPlazaBinding
import ceui.lisa.databinding.FragmentPlazaPostDetailBinding
import ceui.lisa.fragments.BaseFragment
import ceui.lisa.network.*
import com.blankj.utilcode.util.Utils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class PlazaLayoutTest {
    @Before fun setup() { Utils.init(RuntimeEnvironment.getApplication()) }

    @Test fun `plaza and detail bindings inflate with the settings toolbar geometry`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val inflater = LayoutInflater.from(context)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).get()
        val roots = listOf(
            inflater.inflate(R.layout.fragment_settings_hub, null),
            FragmentPlazaBinding.inflate(inflater).root,
            FragmentPlazaPostDetailBinding.inflate(inflater).root,
        )
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 72, 0, 0))
            .build()
        val positions = roots.map { root ->
            BaseFragment.applyToolbarInsets(activity, root)
            val toolbar = root.findViewById<Toolbar>(R.id.toolbar)
            repeat(3) { ViewCompat.dispatchApplyWindowInsets(toolbar, insets) }
            measure(root, 360)
            val title = toolbar.findViewById<TextView>(R.id.toolbar_title)
            assertEquals(72, toolbar.paddingTop)
            assertTrue(title.top >= 72)
            toolbar.height to title.top
        }
        assertEquals(positions[0], positions[1])
        assertEquals(positions[0], positions[2])
    }

    @Test
    @Config(qualifiers = "night")
    fun `night theme keeps the same toolbar geometry`() {
        `plaza and detail bindings inflate with the settings toolbar geometry`()
    }

    @Test fun `image grid appears on first bind and after recycling a text only card`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        val binding = CellPlazaPostBinding.inflate(LayoutInflater.from(context))
        fun bind(count: Int) {
            bindPlazaPostCard(binding, PlazaPost(1, 1, "author", "body", 1,
                PlazaPostRefs(illust = (1..count).map { PlazaIllustRef(it.toLong(), null) })),
                0, null, null)
            measure(binding.root, 360)
            // A detached unit-test view queues View.post until attachment; use its same callback.
            binding.illustGrid.onMeasured?.invoke(binding.illustGrid.width)
            measure(binding.root, 360)
            shadowOf(Looper.getMainLooper()).idle()
        }
        for (count in listOf(1, 0, 2, 3, 4, 9)) {
            bind(count)
            if (count == 0) assertEquals(View.GONE, binding.illustGrid.visibility)
            else {
                assertEquals(View.VISIBLE, binding.illustGrid.visibility)
                assertTrue(binding.illustGrid.childCount > 0)
                assertTrue(binding.illustGrid.height > 0)
            }
        }
    }

    private fun measure(root: View, width: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
}
