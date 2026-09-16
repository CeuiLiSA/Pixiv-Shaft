package ceui.pixiv.panel

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
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

    @Test
    fun `idle navigation inset belongs to composer and does not accumulate`() {
        withComposer { composer ->
            dispatchInsets(nav = 24)
            dispatchInsets(nav = 24)
            assertEquals(0, root.paddingBottom)
            assertEquals(32, composer.paddingBottom)

            dispatchInsets(nav = 48)
            assertEquals(0, root.paddingBottom)
            assertEquals(56, composer.paddingBottom)

            dispatchInsets(nav = 0)
            assertEquals(8, composer.paddingBottom)
        }
    }

    @Test
    fun `panel and keyboard transitions transfer navigation inset without doubling it`() {
        withComposer { composer ->
            dispatchInsets(nav = 24)
            coordinator.showPanel()
            assertEquals(8, composer.paddingBottom)
            assertEquals(24, panel.paddingBottom)
            assertEquals(0, root.paddingBottom)

            coordinator.hidePanel()
            assertEquals(32, composer.paddingBottom)
            assertEquals(0, root.paddingBottom)

            dispatchInsets(nav = 24, ime = 300)
            assertEquals(PanelState.KEYBOARD, coordinator.state)
            assertEquals(8, composer.paddingBottom)
            assertEquals(300, root.paddingBottom)

            coordinator.switchToPanelFromKeyboard()
            dispatchInsets(nav = 24)
            assertEquals(8, composer.paddingBottom)
            assertEquals(0, root.paddingBottom)

            coordinator.switchToKeyboard()
            dispatchInsets(nav = 24, ime = 300)
            assertEquals(View.GONE, panel.visibility)
            assertEquals(8, composer.paddingBottom)
            assertEquals(300, root.paddingBottom)

            dispatchInsets(nav = 24)
            assertEquals(PanelState.NONE, coordinator.state)
            assertEquals(32, composer.paddingBottom)
            assertEquals(0, root.paddingBottom)
        }
    }

    private fun dispatchInsets(nav: Int, ime: Int = 0) {
        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .build(),
        )
    }

    @Test
    fun `hosts without an input bar container keep navigation padding on root`() {
        withComposer(extendComposer = false) { composer ->
            dispatchInsets(nav = 24)
            assertEquals(24, root.paddingBottom)
            assertEquals(8, composer.paddingBottom)
            coordinator.showPanel()
            coordinator.hidePanel()
            assertEquals(24, root.paddingBottom)
            assertEquals(8, composer.paddingBottom)
        }
    }

    private fun withComposer(extendComposer: Boolean = true, check: (View) -> Unit) {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val composer = FrameLayout(root.context).apply { setPadding(0, 0, 0, 8) }
            root.addView(composer)
            val fragment = InsetTestFragment().apply { testRoot = root }
            activity.get().supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment).commitNow()
            coordinator = BottomPanelCoordinator(
                object : PanelHost {
                    override val panelRoot: View = root
                    override val panelView: View = panel
                    override val panelComposerView: View? = composer.takeIf { extendComposer }
                    override val panelInputView: View = input
                },
                animDurationMs = 0,
            )
            coordinator.attach(fragment)
            check(composer)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    class InsetTestFragment : Fragment() {
        lateinit var testRoot: View

        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
        ): View = testRoot
    }
}
