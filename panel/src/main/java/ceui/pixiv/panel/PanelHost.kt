package ceui.pixiv.panel

import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * View contract for [BottomPanelCoordinator].
 *
 * Describes the Views the coordinator needs to manage keyboard ↔ panel
 * transitions. Any Fragment can implement this or construct an anonymous
 * instance from its binding.
 */
interface PanelHost {

    /** Root view that receives window insets and padding adjustments. */
    val panelRoot: View

    /** The bottom panel view (emoji, sticker, etc.) below the input bar. */
    val panelView: View

    /** Input view to focus when switching to keyboard. Null for voice-only panels. */
    val panelInputView: View?

    /**
     * The scrollable content area above the input bar. A confirmed tap while the keyboard or
     * custom panel is open dismisses that surface and is consumed; drag gestures keep their normal
     * scrolling behavior. Null disables tap-to-dismiss.
     */
    val panelContentView: RecyclerView? get() = null

    /**
     * Toggle button that switches between keyboard and panel.
     * If provided, the coordinator automatically:
     * - Calls [BottomPanelCoordinator.toggle] on click
     * - Swaps between [panelToggleIconRes] and [keyboardToggleIconRes]
     *   on state changes
     * - Handles input-view click → switchToKeyboard when panel is open
     *
     * Null to handle toggle wiring manually.
     */
    val panelToggleButton: ImageView? get() = null

    /** Icon resource shown when tapping the button would open the panel. */
    val panelToggleIconRes: Int get() = 0

    /** Icon resource shown when tapping the button would open the keyboard. */
    val keyboardToggleIconRes: Int get() = 0

    /**
     * Called each frame during transitions so the host can keep content
     * anchored (e.g. `recyclerView.scrollToPosition(0)` for reverse layouts).
     */
    fun onAnchorContent() {}

    /** Called when the panel state changes so the host can update UI (e.g. toggle button icons). */
    fun onPanelStateChanged(state: PanelState) {}

    /**
     * Called when the currently visible keyboard or custom panel starts closing.
     *
     * This is intentionally separate from [onPanelStateChanged]: [PanelState.NONE] is committed
     * only after the closing surface reaches its final frame, while hosts may want to begin a
     * coordinated exit animation immediately. Implementations must be idempotent because an IME
     * dismissal can be observed both from an explicit request and from its insets animation.
     */
    fun onPanelDismissStarted(state: PanelState) {}

    /**
     * Called when a keyboard dismissal that already emitted [onPanelDismissStarted] ends with the
     * keyboard still visible. Hosts should restore any visuals they retired at dismissal start.
     */
    fun onPanelDismissCancelled(state: PanelState) {}
}
