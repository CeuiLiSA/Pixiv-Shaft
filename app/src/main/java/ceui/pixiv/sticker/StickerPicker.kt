package ceui.pixiv.sticker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import android.os.Parcelable
import android.view.Gravity
import android.view.KeyEvent
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.witstudio.dialog.WitBottomSheet
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.widget.WitRoundButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object StickerPicker {
    fun show(context: Context, onSelected: (Sticker) -> Unit) =
        show(context, StickerRepository.state, { StickerRepository.prepare(recheck = true) }, onSelected)

    internal fun show(context: Context, stateFlow: StateFlow<StickerState>, prepare: () -> Unit, onSelected: (Sticker) -> Unit): WitBottomSheet {
        StickerLog.i("panel_requested state=%s", stateFlow.value.javaClass.simpleName)
        prepare()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val dialog = WitBottomSheet(context)
        val content = PickerContent(context, prepare) { dialog.dismiss() }
        dialog.setSheetContent(content, padBottomInset = false)
        ViewCompat.setAccessibilityPaneTitle(content, context.getString(R.string.sticker_title))
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        scope.launch {
            var displayed: String? = null
            stateFlow.collect { state ->
                when (state) {
                    is StickerState.Ready -> if (displayed != state.data.generation) {
                        StickerLog.i("panel_open generation=%s", state.data.generation)
                        content.body.removeAllViews()
                        // No grid, thumbnail requests or catalog flattening before the global gate.
                        val panel = StickerPanel(context, state.data) {
                            val current = stateFlow.value as? StickerState.Ready
                            if (current?.data?.generation == state.data.generation) {
                                dialog.dismiss()
                                StickerLog.i("sticker_selected id=%d", it.stickerId)
                                onSelected(it)
                            }
                        }
                        content.body.addView(panel)
                        content.tabSlot.removeAllViews()
                        content.tabSlot.addView(panel.tabs, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
                        displayed = state.data.generation
                    }
                    else -> {
                        if (displayed != null || content.loading.parent == null) {
                            content.body.removeAllViews()
                            content.tabSlot.removeAllViews()
                            content.body.addView(content.loading)
                            displayed = null
                            StickerLog.i("panel_blocked state=%s", state.javaClass.simpleName)
                        }
                        content.spinner.visibility = if (state is StickerState.Failed) View.GONE else View.VISIBLE
                        content.retry.visibility = if (state is StickerState.Failed) View.VISIBLE else View.GONE
                        content.status.text = when (state) {
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

    private class PickerContent(context: Context, prepare: () -> Unit, close: () -> Unit) : LinearLayout(context) {
        val body = FrameLayout(context)
        val tabSlot = FrameLayout(context)
        val spinner = ProgressBar(context)
        val status = TextView(context).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.v3_text_2))
            typeface = ResourcesCompat.getFont(context, R.font.montserrat_regular)
        }
        val retry = WitRoundButton(context).apply {
            setText(R.string.sticker_retry)
            val palette = V3Palette.from(context)
            setWitBackgroundColor(palette.primary)
            setTextColor(palette.onPrimary)
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 24), 0, dp(context, 24), 0)
            setOnClickListener { prepare() }
            visibility = View.GONE
        }
        val loading = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 16))
            addView(spinner, LayoutParams(dp(context, 32), dp(context, 32)))
            addView(status, LayoutParams(-1, -2).apply { topMargin = dp(context, 16) })
            addView(retry, LayoutParams(-2, -2).apply { topMargin = dp(context, 16) })
        }
        init {
            orientation = VERTICAL
            val palette = V3Palette.from(context)
            val header = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(context, 20), 0)
                minimumHeight = dp(context, 56)
                addView(TextView(context).apply {
                    setText(R.string.sticker_close)
                    textSize = 16f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(palette.textAccent)
                    val selectable = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)
                    setBackgroundResource(selectable.resourceId)
                    setPadding(dp(context, 20), 0, dp(context, 20), 0)
                    minimumHeight = dp(context, 48)
                    isFocusable = true
                    setOnClickListener { close() }
                }, LayoutParams(-2, -2))
                addView(tabSlot, LayoutParams(0, -2, 1f))
            }
            addView(header, LayoutParams(-1, -2))
            addView(body, LayoutParams(-1, 0, 1f))
            body.addView(loading, FrameLayout.LayoutParams(-1, -1))
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desired = minOf(dp(context, 420), (resources.displayMetrics.heightPixels * .68f).toInt())
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) desired
                else minOf(desired, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
}

/** One grid; lists and scroll positions are retained for the lifetime of this sheet. */
internal class StickerPanel(context: Context, ready: StickerStore.Ready, selected: (Sticker) -> Unit) : LinearLayout(context) {
    private val lists = StickerCatalog.TYPES.map { ready.catalog.packs.getValue(it).stickers() }
    private val positions = arrayOfNulls<Parcelable>(lists.size)
    private var selectedIndex = 0
    private val adapter = StickerAdapter(lists.first(), selected)
    private val manager = GridLayoutManager(context, 4).apply { isItemPrefetchEnabled = false }
    val tabs = StickerCategoryTabs(context, ::select)
    private val grid = RecyclerView(context).apply {
        layoutManager = manager
        adapter = this@StickerPanel.adapter
        itemAnimator = null
        setHasFixedSize(true)
        setItemViewCacheSize(0) // Detached animated stickers must not keep decoders running.
        clipToPadding = false
        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 8))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
            view.updatePadding(bottom = dp(context, 8) + bottom)
            insets
        }
        doOnAttach { ViewCompat.requestApplyInsets(it) }
    }
    init {
        orientation = VERTICAL
        addView(grid, LayoutParams(-1, 0, 1f))
    }
    private fun select(index: Int) {
        if (index == selectedIndex) return
        grid.stopScroll()
        positions[selectedIndex] = manager.onSaveInstanceState()
        selectedIndex = index
        adapter.replace(lists[index])
        positions[index]?.let(manager::onRestoreInstanceState) ?: manager.scrollToPositionWithOffset(0, 0)
        StickerLog.d("panel_tab type=%s items=%d", StickerCatalog.TYPES[index], lists[index].size)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val columns = ((w - grid.paddingLeft - grid.paddingRight) / dp(context, 48)).coerceIn(3, 12)
        if (manager.spanCount != columns) manager.spanCount = columns
    }
}

/** Reader settings' 42dp track / 36dp segment, inset inside 48dp touch targets. */
internal class StickerCategoryTabs(context: Context, private val onSelected: (Int) -> Unit) : HorizontalScrollView(context) {
    private val cells = ArrayList<TextView>(3)
    private var selectedIndex = 0
    init {
        isHorizontalScrollBarEnabled = false
        val palette = V3Palette.from(context)
        val row = LinearLayout(context).apply {
            background = InsetDrawable(ContextCompat.getDrawable(context, R.drawable.bg_reader_segment_track), 0, dp(context, 3), 0, dp(context, 3))
            setPadding(dp(context, 3), 0, dp(context, 3), 0)
        }
        val font = ResourcesCompat.getFont(context, R.font.montserrat_medium)
        listOf(R.string.sticker_customized, R.string.sticker_static, R.string.sticker_animation).forEachIndexed { index, label ->
            val cell = TextView(context).apply {
                setText(label)
                textSize = 13f
                typeface = font
                gravity = Gravity.CENTER
                setSingleLine()
                minWidth = dp(context, 64)
                minimumHeight = dp(context, 48)
                // Reuse the reader's selected-state drawable, including its rounded ripple mask.
                background = InsetDrawable(ContextCompat.getDrawable(context, R.drawable.bg_reader_segment_option), 0, dp(context, 6), 0, dp(context, 6))
                setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6))
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                    intArrayOf(palette.onPrimary, ContextCompat.getColor(context, R.color.v3_text_1))))
                isSelected = index == 0
                isFocusable = true
                setOnClickListener { select(index) }
                setOnKeyListener { _, key, event ->
                    val direction = when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 1 else -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                        else -> 0
                    }
                    if (direction != 0 && event.action == KeyEvent.ACTION_DOWN) {
                        val target = (index + direction).coerceIn(0, cells.lastIndex)
                        select(target)
                        cells[target].requestFocus()
                        true
                    } else false
                }
                ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true
                        info.isChecked = host.isSelected
                    }
                })
            }
            cells.add(cell)
            row.addView(cell, LinearLayout.LayoutParams(-2, -2))
        }
        addView(row, LayoutParams(-2, -2))
    }
    fun select(index: Int) {
        if (index !in cells.indices || index == selectedIndex) return
        cells[selectedIndex].isSelected = false
        selectedIndex = index
        cells[index].isSelected = true
        onSelected(index)
    }
}

private class StickerAdapter(private var items: List<Sticker>, private val selected: (Sticker) -> Unit) : RecyclerView.Adapter<StickerAdapter.Holder>() {
    init { setHasStableIds(true) }
    class Holder(val image: StickerImageView) : RecyclerView.ViewHolder(image)
    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].stickerId
    fun replace(next: List<Sticker>) {
        items = next
        notifyDataSetChanged() // A different category; no whole-list diff or change animation.
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(StickerImageView(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(-1, dp(context, 48))
        setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        isFocusable = true
    }).also { holder ->
        holder.image.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) selected(items[position])
        }
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val sticker = items[position]
        holder.image.bind(sticker.stickerId, sticker.name, resourceSize = 64)
    }
    override fun onViewRecycled(holder: Holder) { holder.image.bind(null) }
}

internal fun dp(context: Context, size: Int) = (size * context.resources.displayMetrics.density).toInt()
