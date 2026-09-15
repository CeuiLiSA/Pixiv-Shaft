package ceui.pixiv.sticker

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogBuilder
import ceui.pixiv.witstudio.dialog.WitDialogView
import ceui.pixiv.witstudio.theme.V3Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

object StickerPicker {
    fun show(context: Context, onSelected: (Sticker) -> Unit) =
        show(context, StickerRepository.state, { StickerRepository.prepare(recheck = true) }, onSelected)

    internal fun show(context: Context, stateFlow: StateFlow<StickerState>, prepare: () -> Unit, onSelected: (Sticker) -> Unit): WitDialog {
        StickerLog.i("panel_requested state=%s", stateFlow.value.javaClass.simpleName)
        prepare()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val builder = PickerDialog(context, prepare)
        val dialog = builder.setTitle(R.string.sticker_title)
            .addAction(R.string.sticker_close) { d, _ -> d.dismiss() }.create()
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        scope.launch {
            var displayed: String? = null
            stateFlow.collect { state ->
                when (state) {
                    is StickerState.Ready -> {
                        if (displayed != state.data.generation) {
                            StickerLog.i("panel_open generation=%s", state.data.generation)
                            builder.body.removeAllViews()
                            // The panel is only constructed after the global ready marker.
                            builder.body.addView(StickerPanel(context, state.data) {
                                if (stateFlow.value is StickerState.Ready) {
                                    dialog.dismiss()
                                    StickerLog.i("sticker_selected id=%d", it.stickerId)
                                    onSelected(it)
                                }
                            })
                            displayed = state.data.generation
                        }
                    }
                    else -> {
                        if (displayed != null || builder.status.parent == null) {
                            builder.body.removeAllViews()
                            builder.body.addView(builder.loading)
                            displayed = null
                            StickerLog.i("panel_blocked state=%s", state.javaClass.simpleName)
                        }
                        builder.spinner.visibility = if (state is StickerState.Failed) View.GONE else View.VISIBLE
                        builder.retry.visibility = if (state is StickerState.Failed) View.VISIBLE else View.GONE
                        builder.status.text = when (state) {
                            is StickerState.Failed -> context.getString(R.string.sticker_failed, state.error.localizedMessage ?: state.error.javaClass.simpleName)
                            is StickerState.Loading -> when (state.phase) {
                                "download" -> context.getString(R.string.sticker_downloading, if (state.total == 0L) 0 else (100 * state.bytes / state.total).toInt())
                                "extract" -> context.getString(R.string.sticker_extracting)
                                else -> context.getString(R.string.sticker_checking)
                            }
                            else -> context.getString(R.string.sticker_checking)
                        }
                    }
                }
            }
        }
        return dialog
    }

    private class PickerDialog(context: Context, prepare: () -> Unit) : WitDialogBuilder<PickerDialog>(context) {
        val body = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val desired = minOf(dp(context, 420), resources.displayMetrics.heightPixels / 2)
                val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) desired
                    else minOf(desired, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }
        }
        val spinner = ProgressBar(context)
        val status = TextView(context).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.v3_text_2))
        }
        val retry = Button(context).apply {
            setText(R.string.sticker_retry)
            setOnClickListener { prepare() }
        }
        val loading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
            addView(spinner)
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 16) })
            addView(retry)
        }
        override fun onCreateContent(dialog: WitDialog, parent: WitDialogView, context: Context): View = body.apply {
            layoutParams = ViewGroup.LayoutParams(-1, minOf(dp(context, 420), context.resources.displayMetrics.heightPixels / 2))
            addView(loading)
        }
    }
}

/** Receives a fully validated generation; it never fetches API/image URLs. */
private class StickerPanel(context: Context, ready: StickerStore.Ready, selected: (Sticker) -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        val palette = V3Palette.from(context)
        val grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, maxOf(3, minOf(6, resources.displayMetrics.widthPixels / dp(context, 72))))
            clipToPadding = false
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        }
        fun display(type: String) {
            grid.adapter = StickerAdapter(ready.catalog.packs.getValue(type).stickers(), selected)
        }
        val tabs = LinearLayout(context)
        val labels = listOf(R.string.sticker_customized, R.string.sticker_static, R.string.sticker_animation)
        StickerCatalog.TYPES.forEachIndexed { index, type ->
            tabs.addView(Button(context).apply {
                setText(labels[index])
                isAllCaps = false
                setTextColor(palette.textAccent)
                setOnClickListener { display(type) }
            }, LayoutParams(-2, -2))
        }
        addView(HorizontalScrollView(context).apply { addView(tabs); isHorizontalScrollBarEnabled = false }, LayoutParams(-1, -2))
        addView(grid, LayoutParams(-1, 0, 1f))
        display(StickerCatalog.TYPES.first())
    }
}

private class StickerAdapter(private val items: List<Sticker>, private val selected: (Sticker) -> Unit) : RecyclerView.Adapter<StickerAdapter.Holder>() {
    class Holder(val image: StickerImageView) : RecyclerView.ViewHolder(image)
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(StickerImageView(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(-1, dp(context, 72))
        setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        isFocusable = true
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val sticker = items[position]
        holder.image.bind(sticker.stickerId, sticker.name, resourceSize = 64)
        holder.image.setOnClickListener { selected(sticker) }
    }
    override fun onViewRecycled(holder: Holder) { holder.image.bind(null) }
}

internal fun dp(context: Context, size: Int) = (size * context.resources.displayMetrics.density).toInt()
