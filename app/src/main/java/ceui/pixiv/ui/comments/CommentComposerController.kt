package ceui.pixiv.ui.comments

import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.ui.common.launchSuspend
import ceui.pixiv.chat.base.panel.BottomPanelCoordinator
import ceui.pixiv.chat.base.panel.PanelHost
import ceui.pixiv.chat.base.panel.PanelState
import ceui.pixiv.chat.base.panel.WindowSoftInputModeLease
import ceui.pixiv.chat.base.panel.attachBottomPanel
import ceui.pixiv.utils.setOnClick

enum class CommentComposerPresentation {
    PERSISTENT,
    ON_DEMAND_OVERLAY,
}

/**
 * Owns the complete comment-composer interaction for a Fragment view lifecycle.
 *
 * The host supplies only the layout roots and page-specific result/state callbacks. Text input,
 * emoji insertion, stamp loading/sending, keyboard-panel coordination, button state, and temporary
 * `adjustResize` handling stay local to this module.
 */
class CommentComposerController private constructor(
    private val fragment: Fragment,
    private val view: CommentComposerView,
    private val composer: CommentsComposerViewModel,
    private val hostPanelRoot: View,
    private val hostPanelContentView: RecyclerView?,
    palette: V3Palette,
    private val presentation: CommentComposerPresentation,
    private val onSent: (SentComment) -> Unit,
    private val onStateChanged: (PanelState) -> Unit,
    private val onDismissStarted: (PanelState) -> Unit,
    private val onDismissCancelled: (PanelState) -> Unit,
) : DefaultLifecycleObserver {

    private val panelCoordinator: BottomPanelCoordinator
    private var softInputModeLease: WindowSoftInputModeLease.Handle? = null
    private var sendInFlight = false
    private var stampsLoaded = false
    private var stampsLoading = false
    private lateinit var stampAdapter: CommentStampPickerAdapter

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val stampSelected = position == STAMP_PAGE
            styleTabs(stampSelected)
            if (stampSelected && !stampsLoaded && !stampsLoading) {
                stampsLoading = true
                fragment.launchSuspend {
                    try {
                        val stamps = StampCatalog.get()
                        stampAdapter.submit(stamps)
                        stampsLoaded = stamps.isNotEmpty()
                    } finally {
                        stampsLoading = false
                    }
                }
            }
        }
    }

    init {
        view.applyPresentation(presentation)
        styleComposer(palette)
        panelCoordinator = fragment.attachBottomPanel(
            host = object : PanelHost {
                override val panelRoot get() = hostPanelRoot
                override val panelView get() = view.emojiPanel
                override val panelInputView get() = view.commentInput
                override val panelContentView get() = hostPanelContentView
                override val panelToggleButton get() = view.emojiToggle
                override val panelToggleIconRes get() = R.drawable.chat_ic_emoji
                override val keyboardToggleIconRes get() = R.drawable.chat_ic_keyboard
                override fun onPanelStateChanged(state: PanelState) {
                    if (state != PanelState.NONE) view.showInputBar()
                    onStateChanged(state)
                }
                override fun onPanelDismissStarted(state: PanelState) {
                    if (presentation == CommentComposerPresentation.ON_DEMAND_OVERLAY && isEmpty) {
                        view.animateInputBarOut()
                    }
                    onDismissStarted(state)
                }
                override fun onPanelDismissCancelled(state: PanelState) {
                    view.showInputBar()
                    onDismissCancelled(state)
                }
            },
        )
        setUpEmojiPanel()
        setUpTextInput()
        fragment.viewLifecycleOwner.lifecycle.addObserver(this)
    }

    /** Opens the system keyboard without replaying host-specific show animations. */
    fun showKeyboard() {
        view.showForEditing()
        if (panelCoordinator.state != PanelState.KEYBOARD) {
            panelCoordinator.switchToKeyboard()
        }
    }

    /** Removes the composer visuals after the host's on-demand editing session ends. */
    fun hide() {
        view.hideComposer()
    }

    val isEmpty: Boolean
        get() = view.commentInput.text.isNullOrBlank()

    /** Closes either the keyboard or the custom panel. */
    fun dismiss() = panelCoordinator.dismiss()

    override fun onResume(owner: LifecycleOwner) {
        if (softInputModeLease == null) {
            softInputModeLease =
                WindowSoftInputModeLease.acquireAdjustResize(fragment.requireActivity().window)
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        restoreSoftInputMode()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        restoreSoftInputMode()
        view.emojiPager.unregisterOnPageChangeCallback(pageChangeCallback)
        view.emojiPager.adapter = null
        owner.lifecycle.removeObserver(this)
    }

    private fun styleComposer(palette: V3Palette) {
        val density = fragment.resources.displayMetrics.density
        view.commentInput.background =
            palette.settingsCardBg(COMPOSER_RADIUS_DP * density, (COMPOSER_STROKE_DP * density).toInt())
        view.sendButton.background = palette.pillPrimary()
        // v3_surface_2 is intentionally translucent. Pre-composite it over v3_bg so the panel
        // owns its visual background instead of relying on an opaque host root. This lets an
        // overlay input row fade out without leaving a root-sized black placeholder underneath.
        val context = fragment.requireContext()
        view.emojiPanel.setBackgroundColor(
            ColorUtils.compositeColors(
                ContextCompat.getColor(context, R.color.v3_surface_2),
                ContextCompat.getColor(context, R.color.v3_bg),
            ),
        )
    }

    private fun setUpTextInput() {
        val restoredText = composer.editingComment.value.orEmpty()
        if (view.commentInput.text.toString() != restoredText) {
            view.commentInput.setText(restoredText)
            view.commentInput.setSelection(restoredText.length)
        }
        renderEmojiSpans(view.commentInput.text)
        updateSendEnabled(restoredText)

        view.commentInput.addTextChangedListener { editable ->
            renderEmojiSpans(editable)
            composer.updateDraft(editable?.toString().orEmpty())
            updateSendEnabled(editable)
        }

        view.sendButton.setOnClick { sender ->
            if (!beginSend()) return@setOnClick
            fragment.launchSuspend(sender) {
                try {
                    composer.sendComment()?.let(onSent)
                    syncInputFromModel()
                } finally {
                    finishSend()
                }
            }
        }
    }

    private fun setUpEmojiPanel() {
        val kaomojiAdapter = CommentEmojiPickerAdapter { code ->
            val editable = view.commentInput.text ?: return@CommentEmojiPickerAdapter
            val start = view.commentInput.selectionStart.coerceIn(0, editable.length)
            val end = view.commentInput.selectionEnd.coerceIn(0, editable.length)
            val replaceStart = minOf(start, end)
            editable.replace(replaceStart, maxOf(start, end), code)
            view.commentInput.setSelection(replaceStart + code.length)
        }

        stampAdapter = CommentStampPickerAdapter { stamp ->
            if (!beginSend()) return@CommentStampPickerAdapter
            panelCoordinator.dismiss()
            fragment.launchSuspend {
                try {
                    composer.sendStamp(stamp.stamp_id)?.let(onSent)
                } finally {
                    finishSend()
                }
            }
        }
        view.emojiPager.adapter = CommentEmojiStampPagerAdapter(kaomojiAdapter, stampAdapter)
        view.emojiPager.registerOnPageChangeCallback(pageChangeCallback)
        styleTabs(stampSelected = false)
        view.tabKaomoji.setOnClick { view.emojiPager.setCurrentItem(KAOMOJI_PAGE, true) }
        view.tabStamp.setOnClick { view.emojiPager.setCurrentItem(STAMP_PAGE, true) }
        view.panelDismiss.setOnClick { dismiss() }
    }

    private fun styleTabs(stampSelected: Boolean) {
        val context = fragment.requireContext()
        view.tabKaomoji.isSelected = !stampSelected
        view.tabStamp.isSelected = stampSelected
        view.tabKaomoji.setTextColor(
            ContextCompat.getColor(
                context,
                if (stampSelected) R.color.v3_text_2 else R.color.v3_text_1,
            ),
        )
        view.tabKaomoji.setTypeface(null, if (stampSelected) Typeface.NORMAL else Typeface.BOLD)
        view.tabStamp.setTextColor(
            ContextCompat.getColor(
                context,
                if (stampSelected) R.color.v3_text_1 else R.color.v3_text_2,
            ),
        )
        view.tabStamp.setTypeface(null, if (stampSelected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun renderEmojiSpans(editable: android.text.Editable?) {
        if (editable == null) return
        // Mutate spans in place so IME composition and cursor position remain intact.
        CommentEmojiSpanner.clearSpans(editable)
        CommentEmojiSpanner.applySpans(
            fragment.requireContext(),
            editable,
            view.commentInput.textSize.toInt(),
        )
    }

    private fun updateSendEnabled(text: CharSequence?) {
        val enabled = !sendInFlight && !text.isNullOrBlank()
        view.sendButton.isEnabled = enabled
        view.sendButton.alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
    }

    private fun beginSend(): Boolean {
        if (sendInFlight) return false
        sendInFlight = true
        updateSendEnabled(view.commentInput.text)
        return true
    }

    private fun finishSend() {
        sendInFlight = false
        updateSendEnabled(view.commentInput.text)
    }

    private fun syncInputFromModel() {
        val text = composer.editingComment.value.orEmpty()
        view.commentInput.setText(text)
        view.commentInput.setSelection(text.length)
    }

    private fun restoreSoftInputMode() {
        softInputModeLease?.release()
        softInputModeLease = null
    }

    companion object {
        private const val KAOMOJI_PAGE = 0
        private const val STAMP_PAGE = 1
        private const val COMPOSER_RADIUS_DP = 24f
        private const val COMPOSER_STROKE_DP = 1f
        private const val ENABLED_ALPHA = 1f
        private const val DISABLED_ALPHA = 0.4f

        fun attach(
            fragment: Fragment,
            view: CommentComposerView,
            panelRoot: View,
            panelContentView: RecyclerView? = null,
            palette: V3Palette,
            presentation: CommentComposerPresentation = CommentComposerPresentation.PERSISTENT,
            composer: CommentsComposerViewModel,
            onSent: (SentComment) -> Unit,
            onPanelStateChanged: (PanelState) -> Unit = {},
            onPanelDismissStarted: (PanelState) -> Unit = {},
            onPanelDismissCancelled: (PanelState) -> Unit = {},
        ): CommentComposerController = CommentComposerController(
            fragment = fragment,
            view = view,
            composer = composer,
            hostPanelRoot = panelRoot,
            hostPanelContentView = panelContentView,
            palette = palette,
            presentation = presentation,
            onSent = onSent,
            onStateChanged = onPanelStateChanged,
            onDismissStarted = onPanelDismissStarted,
            onDismissCancelled = onPanelDismissCancelled,
        )
    }
}
