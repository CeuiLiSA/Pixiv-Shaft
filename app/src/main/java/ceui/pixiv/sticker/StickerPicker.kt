package ceui.pixiv.sticker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
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
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
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
        val content = PickerContent(context, prepare, close = { dialog.dismiss() })
        dialog.setSheetContent(content, padBottomInset = false)
        ViewCompat.setAccessibilityPaneTitle(content, context.getString(R.string.sticker_title))
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        scope.launch {
            stateFlow.collect { state ->
                content.render(state, { stateFlow.value }) {
                    dialog.dismiss()
                    onSelected(it)
                }
            }
        }

        return dialog
    }

    internal class PickerContent(
        context: Context,
        prepare: () -> Unit,
        private val inline: Boolean = false,
        close: (() -> Unit)? = null,
    ) : LinearLayout(context) {
        private var displayed: String? = null
        private var cachedGeneration: String? = null
        private var cachedPanel: StickerPanel? = null

        fun render(state: StickerState, currentState: () -> StickerState, selected: (Sticker) -> Unit) {
            when (state) {
                is StickerState.Ready -> if (displayed != state.data.generation) {
                    StickerLog.i("panel_open inline=%s generation=%s", inline, state.data.generation)
                    body.removeAllViews()
                    // No grid, thumbnail requests or catalog flattening before the global gate.
                    val panel = cachedPanel?.takeIf { cachedGeneration == state.data.generation }
                        ?: StickerPanel(context, state.data, applyBottomInset = !inline) {
                            val current = currentState() as? StickerState.Ready
                            if (current?.data?.generation == state.data.generation) {
                                StickerLog.i("sticker_selected id=%d", it.stickerId)
                                selected(it)
                            }
                        }
                    cachedPanel = panel
                    cachedGeneration = state.data.generation
                    body.addView(panel)
                    tabSlot.removeAllViews()
                    tabSlot.addView(panel.tabs, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
                    displayed = state.data.generation
                }
                else -> {
                    if (displayed != null || loading.parent == null) {
                        body.removeAllViews()
                        tabSlot.removeAllViews()
                        body.addView(loading)
                        displayed = null
                        StickerLog.i("panel_blocked state=%s", state.javaClass.simpleName)
                    }
                    spinner.visibility = if (state is StickerState.Failed) View.GONE else View.VISIBLE
                    retry.visibility = if (state is StickerState.Failed) View.VISIBLE else View.GONE
                    status.text = when (state) {
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
                setPadding(dp(context, 20), 0, 0, 0)
                minimumHeight = dp(context, if (inline) 48 else 56)
                addView(tabSlot, LayoutParams(0, -2, 1f))
                if (close != null) addView(TextView(context).apply {
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
            }
            addView(header, LayoutParams(-1, -2))
            addView(body, LayoutParams(-1, 0, 1f))
            body.addView(loading, FrameLayout.LayoutParams(-1, -1))
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (inline) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val desired = minOf(dp(context, 420), (resources.displayMetrics.heightPixels * .68f).toInt())
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) desired
                else minOf(desired, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
}

/** Each category owns its grid, preserving vertical scroll position across horizontal swipes. */
internal class StickerPanel(context: Context, ready: StickerStore.Ready, applyBottomInset: Boolean = true, selected: (Sticker) -> Unit) : LinearLayout(context) {
    private val grids = StickerCatalog.TYPES.map { type ->
        RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4).apply { isItemPrefetchEnabled = false }
            adapter = StickerAdapter(ready.catalog.packs.getValue(type).stickers(), selected)
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(0)
            clipToPadding = false
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 8))
            if (applyBottomInset) ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
                view.updatePadding(bottom = dp(context, 8) + bottom)
                insets
            }
            doOnAttach { ViewCompat.requestApplyInsets(it) }
        }
    }
    private val pager = ViewPager2(context)
    val tabs = StickerCategoryTabs(context) { pager.setCurrentItem(it, true) }

    init {
        orientation = VERTICAL
        pager.adapter = object : RecyclerView.Adapter<PageHolder>() {
            override fun getItemCount() = grids.size
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                PageHolder(FrameLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                })
            override fun onBindViewHolder(holder: PageHolder, position: Int) {
                val grid = grids[position]
                if (grid.parent !== holder.frame) {
                    (grid.parent as? ViewGroup)?.removeView(grid)
                    holder.frame.removeAllViews()
                    holder.frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
                }
            }
            override fun onViewRecycled(holder: PageHolder) { holder.frame.removeAllViews() }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                tabs.setScrollPosition(position, positionOffset)
            }
            override fun onPageSelected(position: Int) {
                tabs.setSelectedIndex(position)
                StickerLog.d("panel_tab type=%s", StickerCatalog.TYPES[position])
            }
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) tabs.setScrollPosition(pager.currentItem, 0f)
            }
        })
        addView(pager, LayoutParams(-1, 0, 1f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        grids.forEach { grid ->
            val columns = ((w - grid.paddingLeft - grid.paddingRight) / dp(context, 48)).coerceIn(3, 12)
            val manager = grid.layoutManager as GridLayoutManager
            if (manager.spanCount != columns) manager.spanCount = columns
        }
    }

    private class PageHolder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
}

/** Reader settings' 42dp track / 36dp segment, inset inside 48dp touch targets. */
internal class StickerCategoryTabs(context: Context, private val onSelected: (Int) -> Unit) : HorizontalScrollView(context) {
    private val cells = ArrayList<TextView>(3)
    private var selectedIndex = 0
    private var scrollPosition = 0f
    private val palette = V3Palette.from(context)
    private val normalTextColor = ContextCompat.getColor(context, R.color.v3_text_1)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.primary }
    private val indicatorBounds = RectF()
    private val row = object : LinearLayout(context) {
        override fun dispatchDraw(canvas: Canvas) {
            if (cells.isNotEmpty()) {
                val start = scrollPosition.toInt().coerceIn(cells.indices)
                val end = (start + 1).coerceAtMost(cells.lastIndex)
                val offset = scrollPosition - start
                val from = cells[start]
                val to = cells[end]
                indicatorBounds.set(
                    from.left + (to.left - from.left) * offset,
                    dp(context, 6).toFloat(),
                    from.right + (to.right - from.right) * offset,
                    height - dp(context, 6).toFloat(),
                )
                val radius = dp(context, 11).toFloat()
                canvas.drawRoundRect(indicatorBounds, radius, radius, indicatorPaint)
            }
            super.dispatchDraw(canvas)
        }
    }
    init {
        isHorizontalScrollBarEnabled = false
        row.apply {
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
                // The shared indicator moves beneath labels; each label only draws its ripple.
                val rippleColor = TypedValue().also {
                    context.theme.resolveAttribute(android.R.attr.colorControlHighlight, it, true)
                }.data
                val mask = GradientDrawable().apply {
                    cornerRadius = dp(context, 11).toFloat()
                    setColor(android.graphics.Color.WHITE)
                }
                background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask),
                    0, dp(context, 6), 0, dp(context, 6))
                setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6))
                setTextColor(if (index == 0) palette.onPrimary else normalTextColor)
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
        setSelectedIndex(index)
        onSelected(index)
    }

    fun setSelectedIndex(index: Int) {
        if (index !in cells.indices) return
        selectedIndex = index
        cells.forEachIndexed { i, cell -> cell.isSelected = i == index }
    }

    /** Driven by the pager for dragging, settling, reversing and cancelled swipes alike. */
    fun setScrollPosition(position: Int, offset: Float) {
        scrollPosition = (position + offset).coerceIn(0f, cells.lastIndex.toFloat())
        cells.forEachIndexed { index, cell ->
            val coverage = (1f - abs(index - scrollPosition)).coerceIn(0f, 1f)
            cell.setTextColor(ColorUtils.blendARGB(normalTextColor, palette.onPrimary, coverage))
        }
        row.invalidate()
        // Keep the moving selection visible when translated labels or large fonts overflow.
        val from = cells[scrollPosition.toInt()]
        val to = cells[(scrollPosition.toInt() + 1).coerceAtMost(cells.lastIndex)]
        val fraction = scrollPosition % 1f
        val center = (from.left + from.right) / 2f * (1f - fraction) + (to.left + to.right) / 2f * fraction
        scrollTo((center - width / 2f).toInt().coerceAtLeast(0), 0)
    }
}

private class StickerAdapter(private val items: List<Sticker>, private val selected: (Sticker) -> Unit) : RecyclerView.Adapter<StickerAdapter.Holder>() {
    init { setHasStableIds(true) }
    class Holder(val image: StickerImageView) : RecyclerView.ViewHolder(image)
    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].stickerId
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
