package ceui.pixiv.panel

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BottomPanelCoordinatorTest {

    private lateinit var root: FrameLayout
    private lateinit var panel: FrameLayout
    private lateinit var input: EditText
    private lateinit var states: MutableList<PanelState>
    private lateinit var dismissals: MutableList<PanelState>
    private lateinit var coordinator: BottomPanelCoordinator

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        root = FrameLayout(context)
        panel = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            visibility = View.GONE
        }
        input = EditText(context)
        root.addView(panel)
        root.addView(input)
        states = mutableListOf()
        dismissals = mutableListOf()
        coordinator = BottomPanelCoordinator(
            host = object : PanelHost {
                override val panelRoot: View = root
                override val panelView: View = panel
                override val panelInputView: View = input

                override fun onPanelStateChanged(state: PanelState) {
                    states += state
                }

                override fun onPanelDismissStarted(state: PanelState) {
                    dismissals += state
                }
            },
            fallbackHeightDp = 200,
            animDurationMs = 0,
        )
    }

    @Test
    fun `show and hide publish stable states and final visibility`() {
        coordinator.showPanel()

        assertEquals(PanelState.PANEL, coordinator.state)
        assertTrue(panel.visibility == View.VISIBLE)
        assertTrue(panel.layoutParams.height > 0)

        coordinator.hidePanel()

        assertEquals(PanelState.NONE, coordinator.state)
        assertFalse(panel.visibility == View.VISIBLE)
        assertEquals(0, panel.layoutParams.height)
        assertEquals(listOf(PanelState.PANEL, PanelState.NONE), states)
        assertEquals(listOf(PanelState.PANEL), dismissals)
    }

    @Test
    fun `toggle follows none panel keyboard panel state cycle`() {
        coordinator.toggle()
        assertEquals(PanelState.PANEL, coordinator.state)

        coordinator.toggle()
        assertEquals(PanelState.KEYBOARD, coordinator.state)

        coordinator.toggle()
        assertEquals(PanelState.PANEL, coordinator.state)
    }
}
