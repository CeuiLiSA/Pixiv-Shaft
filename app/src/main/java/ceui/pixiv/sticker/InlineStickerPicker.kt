package ceui.pixiv.sticker

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.pixiv.services.appServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Chat's existing panel coordinator owns height, navigation insets and keyboard transitions. */
internal class InlineStickerPicker(
    context: Context,
    container: FrameLayout,
    owner: LifecycleOwner,
    state: StateFlow<StickerState> = context.appServices().stickerRepository.state,
    prepare: () -> Unit = { context.appServices().stickerRepository.prepare(recheck = true) },
    selected: (Sticker) -> Unit,
) {
    private val active = MutableStateFlow(false)

    init {
        owner.lifecycleScope.launch {
            // Lazily build once; retain category and scroll position across keyboard switches.
            var content: StickerPicker.PickerContent? = null
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                active.collectLatest { visible ->
                    if (!visible) return@collectLatest
                    StickerLog.i("panel_requested inline=true state=%s", state.value.javaClass.simpleName)
                    prepare()
                    val picker = content ?: StickerPicker.PickerContent(context, prepare, inline = true)
                        .also { content = it }
                    // Validate before attaching, including after a generation changes while hidden.
                    val onSelected: (Sticker) -> Unit = { if (active.value) selected(it) }
                    picker.render(state.value, { state.value }, onSelected)
                    container.addView(picker, FrameLayout.LayoutParams(-1, -1))
                    try {
                        state.collect { picker.render(it, { state.value }, onSelected) }
                    } finally {
                        // GONE alone does not detach children: explicitly release Glide requests.
                        container.removeView(picker)
                        StickerLog.i("panel_hidden inline=true")
                    }
                }
            }
        }
    }

    fun setActive(value: Boolean) { active.value = value }
}

/** Observes GONE directly; a GONE child is not guaranteed another layout callback. */
class InlineStickerContainer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    internal var onPanelVisibilityChanged: ((Boolean) -> Unit)? = null

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        onPanelVisibilityChanged?.invoke(isShown)
    }
}
