package ceui.pixiv.ui.comments

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.loxia.ProgressImageButton

/** Shared visual implementation of the Pixiv text/emoji/stamp comment composer. */
class CommentComposerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val inputBar: LinearLayout
    internal val commentInput: EditText
    internal val sendButton: ProgressImageButton
    internal val emojiToggle: ImageView
    internal val emojiPanel: LinearLayout
    internal val emojiPager: ViewPager2
    internal val tabKaomoji: TextView
    internal val tabStamp: TextView
    internal val panelDismiss: ImageView
    private var inputAnimationGeneration = 0

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_comment_composer, this, true)
        // ViewCompat.requireViewById, not View.requireViewById: the latter is API 28+ and blows up
        // with NoSuchMethodError on minSdk-24 devices while this view is being inflated.
        inputBar = ViewCompat.requireViewById(this, R.id.comment_input_bar)
        commentInput = ViewCompat.requireViewById(this, R.id.comment_input)
        sendButton = ViewCompat.requireViewById(this, R.id.send_button)
        emojiToggle = ViewCompat.requireViewById(this, R.id.emoji_toggle)
        emojiPanel = ViewCompat.requireViewById(this, R.id.emoji_panel_container)
        // Consume touches not handled by tabs/ViewPager children. Without this touch sink, events
        // on panel whitespace continue to the artwork RecyclerView underneath the overlay.
        emojiPanel.isClickable = true
        emojiPanel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        emojiPager = ViewCompat.requireViewById(this, R.id.emoji_pager)
        tabKaomoji = ViewCompat.requireViewById(this, R.id.tab_kaomoji)
        tabStamp = ViewCompat.requireViewById(this, R.id.tab_stamp)
        panelDismiss = ViewCompat.requireViewById(this, R.id.panel_dismiss)
    }

    internal fun applyPresentation(presentation: CommentComposerPresentation) {
        val overlay = presentation == CommentComposerPresentation.ON_DEMAND_OVERLAY
        isVisible = !overlay
        inputBar.elevation = if (overlay) OVERLAY_ELEVATION_DP * resources.displayMetrics.density else 0f
        inputBar.setBackgroundColor(if (overlay) context.getColor(R.color.v3_bg) else 0)
    }

    internal fun showForEditing() {
        isVisible = true
        showInputBar()
    }

    internal fun showInputBar() {
        inputAnimationGeneration++
        inputBar.animate().cancel()
        inputBar.visibility = View.VISIBLE
        inputBar.alpha = 1f
        inputBar.translationY = 0f
    }

    /**
     * Visually retires only the input row while retaining its measured space. The custom panel can
     * therefore finish shrinking without a parent relayout jump; the whole composer is removed once
     * the coordinator commits NONE.
     */
    internal fun animateInputBarOut() {
        if (inputBar.visibility != View.VISIBLE || inputBar.alpha == 0f) return
        val generation = ++inputAnimationGeneration
        inputBar.animate().cancel()
        inputBar.animate()
            .alpha(0f)
            .translationY(INPUT_EXIT_TRANSLATION_DP * resources.displayMetrics.density)
            .setDuration(INPUT_EXIT_DURATION_MS)
            .setInterpolator(FastOutLinearInInterpolator())
            .withLayer()
            .withEndAction {
                if (inputAnimationGeneration == generation && inputBar.alpha == 0f) {
                    inputBar.visibility = View.INVISIBLE
                }
            }
            .start()
    }

    internal fun hideComposer() {
        inputAnimationGeneration++
        inputBar.animate().cancel()
        isVisible = false
        inputBar.visibility = View.VISIBLE
        inputBar.alpha = 1f
        inputBar.translationY = 0f
    }

    private companion object {
        const val OVERLAY_ELEVATION_DP = 12f
        const val INPUT_EXIT_TRANSLATION_DP = 12f
        const val INPUT_EXIT_DURATION_MS = 140L
    }
}
