package ceui.pixiv.ui.novel.reader.render

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.PageElement
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.paginate.InlineSpan
import ceui.pixiv.ui.novel.reader.paginate.InlineTag
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import com.bumptech.glide.Glide
import com.hjq.toast.Toaster
import kotlin.math.roundToInt

/**
 * Vertical infinite-scroll reader. Tokens are streamed through a
 * [RecyclerView] so only the on-screen (plus a small cache of) views exist at
 * any time — earlier this view inflated *every* paragraph of the whole novel
 * into a single [android.widget.LinearLayout], which made every frame's child
 * iteration and any child `requestLayout` O(total-length); a 15万字 novel
 * dropped frames in direct proportion to its word count. Recycling makes the
 * per-frame cost O(visible) regardless of novel length.
 *
 * Replaces [NovelReaderView] when [ceui.pixiv.ui.novel.reader.model.FlipMode]
 * vertical reading is active. Supports center-tap for chrome toggle, image
 * tap, and scroll-position tracking for reading-progress persistence.
 */
class NovelScrollReaderView(context: Context) : RecyclerView(context) {

    /** Extra top offset (e.g. search overlay height) so position restores
     *  land below the covered region. Set by the host fragment. */
    var topInset: Int = 0

    var onCenterTap: (() -> Unit)? = null
    var onTextDoubleTap: ((Int) -> Unit)? = null
    private var doubleTapChar: Int? = null
    private var ttsRange: IntRange? = null
    private var followGeneration = 0
    var onImageTap: ((PageElement.Image) -> Unit)? = null
    var onJumpTap: ((target: Int) -> Unit)? = null
    var onCharIndexChanged: ((Int) -> Unit)? = null
    /** Fraction of total scrollable range consumed, in [0f, 1f]. */
    var onScrollProgressChanged: ((Float) -> Unit)? = null

    /** Text selection callbacks — mirrors ReaderTextBlockView's interface. */
    var onSelectionStarted: ((absStart: Int, absEnd: Int, text: String) -> Unit)? = null
    var onSelectionChanged: ((absStart: Int, absEnd: Int, text: String) -> Unit)? = null
    var onSelectionEnded: (() -> Unit)? = null
    var selectionMenuEntries: List<Pair<Int, String>> = emptyList()
    var onSelectionMenuAction: ((id: Int) -> Unit)? = null

    /** 手指落下那一刻 RecyclerView 是否还在滚动，区分“滚动中点击停滚”与“静止时单击呼出菜单”（#1047）。 */
    private var wasScrollingOnTouchDown = false

    private val lm = LinearLayoutManager(context)
    private var contentAdapter: ContentAdapter? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (onTextDoubleTap == null || wasScrollingOnTouchDown) return false
            val child = findChildViewUnder(e.x, e.y) ?: return false
            val pos = getChildAdapterPosition(child)
            val token = contentAdapter?.tokens?.getOrNull(pos) ?: return false
            val tv = child as? TextView ?: return false
            val offset = tv.textOffsetAt(e.x - child.x, e.y - child.y) ?: return false
            doubleTapChar = when (token) {
                is ContentToken.Paragraph -> token.textSourceStart + offset
                is ContentToken.Chapter -> token.sourceStart
                else -> return false
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 滚动中点击只负责停滚，不呼出菜单；等滚动完全停下后再单击才呼出（#1047）。
            if (wasScrollingOnTouchDown) {
                wasScrollingOnTouchDown = false
                return true
            }
            // 纵向滚动没有左右翻页语义，整屏单击都呼出菜单——不是横向那套三分区
            //（只留中间一竖条反直觉，#1038）。落在插画/跳转按钮上的点击让给它们自己的
            // onClick，避免「打开大图的同时菜单也弹出来」。
            val child = findChildViewUnder(e.x, e.y)
            if (child != null) {
                val holder = getChildViewHolder(child)
                if (holder is ImageHolder || holder is JumpHolder) return false
            }
            onCenterTap?.invoke()
            return true
        }
    })

    init {
        layoutManager = lm
        isVerticalScrollBarEnabled = true
        // Off-screen text/image views are cheap to rebuild, but keeping a few
        // around either side of the viewport avoids inflate churn on flings.
        setItemViewCacheSize(6)
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                reportScrollProgress()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    onCharIndexChanged?.invoke(currentCharIndex())
                }
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // 在手指落下前先记住 RecyclerView 是否还在滚动（super.dispatchTouchEvent 里
            // onInterceptTouchEvent 会把 SETTLING 改成 DRAGGING，ACTION_UP 后更是 IDLE，
            // 再晚就判不出来了），所以必须在这里、交给 super 之前读 scrollState（#1047）。
            wasScrollingOnTouchDown = scrollState != SCROLL_STATE_IDLE
        }
        gestureDetector.onTouchEvent(ev)
        val charIndex = doubleTapChar
        if (charIndex != null) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                doubleTapChar = null
                onTextDoubleTap?.invoke(charIndex)
            } else if (ev.actionMasked == MotionEvent.ACTION_CANCEL) doubleTapChar = null
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 每次布局完成后补报一次进度：初始那次 pushScrollProgressNow 走的是 post{}，
        // 可能赶在首帧内容排版前执行（此时 scrollRange 还是 0），而部分机型上首次布局
        // 不派发 onScrolled(0,0)——常驻进度就一直空着，直到用户手动滚动/呼出菜单
        //（#1038）。fragment 侧 setText 前有等值去重，逐帧布局不会带来重复刷新。
        reportScrollProgress()
    }

    // ---- Public API --------------------------------------------------------

    fun bind(
        tokens: List<ContentToken>,
        style: TypeStyle,
        geometry: PageGeometry,
        imageResolver: (ContentToken) -> String?,
    ) {
        setBackgroundColor(style.backgroundColor)
        // Side padding = text margins (applies to every item); top/bottom
        // padding = end breathing room. clipToPadding=false lets content
        // scroll through the padded region instead of being clipped.
        setPadding(
            geometry.paddingLeft.toInt(),
            geometry.paddingTop.toInt(),
            geometry.paddingRight.toInt(),
            geometry.paddingBottom.toInt(),
        )
        clipToPadding = false
        val adapter = ContentAdapter(tokens, style, geometry, imageResolver)
        contentAdapter = adapter
        setAdapter(adapter)
    }

    fun scrollToCharIndex(charIndex: Int) {
        val pos = positionForCharIndex(charIndex) ?: return
        post {
            val first = lm.findFirstVisibleItemPosition()
            // Smooth-scroll only for nearby targets — LinearSmoothScroller
            // animates item-by-item, so a far chapter jump would crawl through
            // (and lay out) thousands of items. Far jumps teleport instead.
            if (first != RecyclerView.NO_POSITION && kotlin.math.abs(pos - first) <= SMOOTH_SCROLL_MAX_ITEMS) {
                smoothScrollToPosition(pos)
            } else {
                lm.scrollToPositionWithOffset(pos, topInset)
            }
        }
    }

    fun jumpToCharIndex(charIndex: Int) {
        val pos = positionForCharIndex(charIndex) ?: return
        post { lm.scrollToPositionWithOffset(pos, topInset) }
    }

    fun currentCharIndex(): Int {
        val toks = contentAdapter?.tokens ?: return 0
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return 0
        return anchorCharOf(toks[pos.coerceIn(0, toks.lastIndex)])
    }

    fun scrollByPage(forward: Boolean) {
        val distance = (height * 0.9f).toInt()
        smoothScrollBy(0, if (forward) distance else -distance)
    }

    /**
     * Jump to [fraction] of the book (0f = first item, 1f = last). Maps to an
     * item position and teleports via the layout manager — `scrollBy` over a
     * large pixel delta makes `LinearLayoutManager.fill()` lay out every
     * intervening item (O(N)), the exact cost virtualization removes. Position
     * teleport is O(1). The bottom-bar progress (pixel-estimated) may settle a
     * hair off the dragged value; that's expected for a variable-height list.
     */
    fun scrollToFraction(fraction: Float) {
        val count = contentAdapter?.itemCount ?: return
        if (count <= 0) return
        val pos = (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()
        post { lm.scrollToPositionWithOffset(pos, 0) }
    }

    /**
     * Force a scroll-progress callback emission. Used right after entering
     * vertical-scroll mode so the bottom-bar SeekBar picks up the current
     * scroll fraction even if no scroll event has fired yet.
     */
    fun pushScrollProgressNow() {
        post { reportScrollProgress() }
    }

    fun applySearchHighlights(hits: List<HighlightRange>) {
        contentAdapter?.searchHits = hits
        // Re-apply to the paragraph views currently attached; freshly bound
        // ones pick the hits up from the adapter in onBind.
        for (i in 0 until childCount) {
            val holder = getChildViewHolder(getChildAt(i)) as? ParagraphHolder ?: continue
            holder.applyHighlights(hits + ttsHighlights())
        }
    }

    fun setTtsRange(range: IntRange?) {
        ttsRange = range
        applySearchHighlights(contentAdapter?.searchHits.orEmpty())
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val token = contentAdapter?.tokens?.getOrNull(getChildAdapterPosition(child))
            if (token is ContentToken.Chapter) applyChapterHighlight(child as TextView, token)
        }
    }

    private fun ttsHighlights(): List<HighlightRange> = listOfNotNull(ttsRange?.let {
        HighlightRange(it.first, it.last + 1, contentAdapter?.style?.highlightColor ?: 0)
    })

    /** Check the spoken line, not just the paragraph: one paragraph can span screens. */
    fun isCharVisible(charIndex: Int): Boolean {
        val pos = positionForCharIndex(charIndex) ?: return false
        val child = lm.findViewByPosition(pos) as? TextView ?: return false
        val token = contentAdapter?.tokens?.getOrNull(pos) ?: return false
        val layout = child.layout ?: return false
        val offset = (charIndex - anchorCharOf(token)).coerceIn(0, child.text.length)
        val line = layout.getLineForOffset(offset)
        val top = child.top + child.totalPaddingTop + layout.getLineTop(line)
        val bottom = child.top + child.totalPaddingTop + layout.getLineBottom(line)
        return top >= paddingTop + topInset && bottom <= height - paddingBottom
    }

    fun cancelTtsFollow() { followGeneration++ }

    fun followTtsChar(charIndex: Int) {
        val generation = ++followGeneration
        if (isCharVisible(charIndex) || scrollState == SCROLL_STATE_DRAGGING) return
        val pos = positionForCharIndex(charIndex) ?: return
        val child = lm.findViewByPosition(pos) as? TextView
        if (child == null || child.layout == null) {
            lm.scrollToPositionWithOffset(pos, topInset)
            // The target paragraph must be laid out before locating its line.
            doOnNextLayout {
                if (isAttachedToWindow && generation == followGeneration) alignTtsLine(pos, charIndex)
            }
            return
        }
        alignTtsLine(pos, charIndex)
    }

    private fun alignTtsLine(pos: Int, charIndex: Int) {
        if (scrollState == SCROLL_STATE_DRAGGING) return
        val child = lm.findViewByPosition(pos) as? TextView ?: return
        val layout = child.layout ?: return
        val token = contentAdapter?.tokens?.getOrNull(pos) ?: return
        val offset = (charIndex - anchorCharOf(token)).coerceIn(0, child.text.length)
        val lineTop = layout.getLineTop(layout.getLineForOffset(offset))
        scrollBy(0, child.top + child.totalPaddingTop + lineTop - paddingTop - topInset)
    }

    // ---- Position helpers --------------------------------------------------

    private fun anchorCharOf(token: ContentToken): Int =
        if (token is ContentToken.Paragraph) token.textSourceStart else token.sourceStart

    /** Last token whose anchor char is <= [charIndex]. Anchors are monotonic
     *  in source order, so we can stop at the first one that overshoots. */
    private fun positionForCharIndex(charIndex: Int): Int? {
        val toks = contentAdapter?.tokens ?: return null
        if (toks.isEmpty()) return null
        var target = 0
        for (i in toks.indices) {
            if (anchorCharOf(toks[i]) <= charIndex) target = i else break
        }
        return target
    }

    private fun reportScrollProgress() {
        // post{} 排进主线程队列的 runnable 不随 view detach 取消:fragment view 销毁后
        // 才执行的话,回调链(setProgressPercent → refreshProgressOverlay)会去取已
        // 销毁的 binding,FragmentViewBindingDelegate.requireView() 直接崩。
        if (!isAttachedToWindow) return
        val cb = onScrollProgressChanged ?: return
        val range = computeVerticalScrollRange() - computeVerticalScrollExtent()
        val progress = if (range > 0) computeVerticalScrollOffset().toFloat() / range else 0f
        cb.invoke(progress.coerceIn(0f, 1f))
    }

    // ---- Adapter -----------------------------------------------------------

    private inner class ContentAdapter(
        val tokens: List<ContentToken>,
        val style: TypeStyle,
        val geometry: PageGeometry,
        val imageResolver: (ContentToken) -> String?,
    ) : Adapter<ViewHolder>() {

        var searchHits: List<HighlightRange> = emptyList()

        override fun getItemCount(): Int = tokens.size

        override fun getItemViewType(position: Int): Int = when (tokens[position]) {
            is ContentToken.Paragraph -> TYPE_PARAGRAPH
            is ContentToken.Chapter -> TYPE_CHAPTER
            is ContentToken.BlankLine -> TYPE_SPACER
            is ContentToken.PageBreak -> TYPE_DIVIDER
            is ContentToken.PixivImage -> TYPE_IMAGE
            is ContentToken.UploadedImage -> TYPE_IMAGE
            is ContentToken.Jump -> TYPE_JUMP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = when (viewType) {
            TYPE_PARAGRAPH -> ParagraphHolder(buildParagraphView(style))
            TYPE_CHAPTER -> SimpleHolder(buildChapterView(style))
            TYPE_SPACER -> SimpleHolder(buildSpacerView(style))
            TYPE_DIVIDER -> SimpleHolder(buildDividerView(style, geometry.contentWidth))
            TYPE_IMAGE -> ImageHolder(context, style.paragraphSpacingPx.roundToInt())
            else -> JumpHolder(buildJumpView(style))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (val token = tokens[position]) {
                is ContentToken.Paragraph -> (holder as ParagraphHolder).bind(token, style, searchHits + ttsHighlights())
                is ContentToken.Chapter -> bindChapter(holder.itemView as AppCompatTextView, token, style)
                is ContentToken.BlankLine -> Unit
                is ContentToken.PageBreak -> Unit
                is ContentToken.PixivImage -> (holder as ImageHolder).bind(token, style, imageResolver(token))
                is ContentToken.UploadedImage -> (holder as ImageHolder).bind(token, style, imageResolver(token))
                is ContentToken.Jump -> (holder as JumpHolder).bind(token, style)
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            if (holder is ParagraphHolder) holder.clearSelection()
        }

        override fun onViewAttachedToWindow(holder: ViewHolder) {
            // RecyclerView's item cache can reattach a clean holder without
            // onBind. Its highlights must still reflect the latest utterance.
            if (holder is ParagraphHolder) holder.applyHighlights(searchHits + ttsHighlights())
            val token = tokens.getOrNull(holder.bindingAdapterPosition)
            if (token is ContentToken.Chapter) applyChapterHighlight(holder.itemView as TextView, token)
        }
    }

    private class SimpleHolder(view: View) : ViewHolder(view)

    private inner class ParagraphHolder(val tv: AppCompatTextView) : ViewHolder(tv) {
        private var boundSourceStart: Int = 0

        init {
            tv.customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    populateMenu(menu)
                    notifyTvSelection(tv, boundSourceStart, onSelectionStarted)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    populateMenu(menu)
                    notifyTvSelection(tv, boundSourceStart, onSelectionChanged)
                    return true
                }

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    notifyTvSelection(tv, boundSourceStart, onSelectionChanged)
                    onSelectionMenuAction?.invoke(item.itemId)
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    onSelectionEnded?.invoke()
                }
            }
        }

        fun bind(token: ContentToken.Paragraph, style: TypeStyle, hits: List<HighlightRange>) {
            boundSourceStart = token.textSourceStart
            val spannable = SpannableString(token.text)
            val indent = style.firstLineIndentPx.toInt()
            if (indent > 0 && token.text.isNotEmpty()) {
                spannable.setSpan(
                    LeadingMarginSpan.Standard(indent, 0),
                    0, token.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            applyInlineSpans(spannable, token.inlineSpans, style)
            // LinkMovementMethod must be set BEFORE setTextIsSelectable,
            // otherwise ArrowKeyMovementMethod overwrites it.
            tv.movementMethod = if (token.inlineSpans.any { it.tag is InlineTag.Link }) {
                android.text.method.LinkMovementMethod.getInstance()
            } else {
                null
            }
            tv.setTextIsSelectable(true)
            tv.text = TextMeasurer.wrapWithFixedLineHeight(
                spannable, style.textPaint, style.lineSpacingMultiplier, style.lineSpacingExtra,
            )
            applyHighlights(hits)
        }

        fun applyHighlights(hits: List<HighlightRange>) {
            val spannable = tv.text as? Spannable ?: return
            spannable.getSpans(0, spannable.length, ScrollSearchSpan::class.java)
                .forEach { spannable.removeSpan(it) }
            if (hits.isEmpty()) return
            val anchorStart = boundSourceStart
            val anchorEnd = anchorStart + spannable.length
            for (hit in hits) {
                val s = maxOf(hit.absoluteStart, anchorStart)
                val e = minOf(hit.absoluteEnd, anchorEnd)
                if (e <= s) continue
                spannable.setSpan(
                    ScrollSearchSpan(hit.color),
                    s - anchorStart, e - anchorStart,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        fun clearSelection() {
            val spannable = tv.text as? Spannable ?: return
            android.text.Selection.removeSelection(spannable)
        }
    }

    /** Hosts one (recycled) inline image. A margin-carrying FrameLayout keeps
     *  the paragraph-spacing gap regardless of which image it currently shows. */
    private inner class ImageHolder(context: Context, gap: Int) : ViewHolder(
        android.widget.FrameLayout(context).apply {
            layoutParams = itemParams(topMargin = gap, bottomMargin = gap)
        },
    ) {
        private val host = itemView as ViewGroup

        fun bind(token: ContentToken, style: TypeStyle, url: String?) {
            host.removeAllViews()
            host.addView(buildImageView(token, style, url))
        }
    }

    private inner class JumpHolder(val tv: AppCompatTextView) : ViewHolder(tv) {
        fun bind(token: ContentToken.Jump, style: TypeStyle) {
            tv.text = context.getString(ceui.lisa.R.string.reader_jump_button, token.target)
            val target = token.target
            tv.setOnClickListener { onJumpTap?.invoke(target) }
        }
    }

    // ---- View factories -----------------------------------------------------

    private fun itemParams(
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        topMargin: Int = 0,
        bottomMargin: Int = 0,
        leftMargin: Int = 0,
        rightMargin: Int = 0,
    ): LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height).apply {
        this.topMargin = topMargin
        this.bottomMargin = bottomMargin
        this.leftMargin = leftMargin
        this.rightMargin = rightMargin
    }

    private fun buildParagraphView(style: TypeStyle): AppCompatTextView =
        AppCompatTextView(context).apply {
            TextMeasurer.applyLayoutSettings(this)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textPaint.textSize)
            typeface = style.textPaint.typeface
            setTextColor(style.textPaint.color)
            letterSpacing = style.textPaint.letterSpacing
            setLineSpacing(0f, 1f)
            setBackgroundColor(Color.TRANSPARENT)
            highlightColor = style.selectionColor
            layoutParams = itemParams(bottomMargin = style.paragraphSpacingPx.roundToInt())
        }

    private fun bindChapter(tv: AppCompatTextView, token: ContentToken.Chapter, style: TypeStyle) {
        tv.text = SpannableString(token.title)
        applyChapterHighlight(tv, token)
    }

    private fun applyChapterHighlight(tv: TextView, token: ContentToken.Chapter) {
        val text = tv.text as? Spannable ?: return
        text.getSpans(0, text.length, ScrollSearchSpan::class.java).forEach(text::removeSpan)
        if (text.isNotEmpty() && ttsRange?.let { it.first < token.sourceEnd && it.last >= token.sourceStart } == true) {
            text.setSpan(ScrollSearchSpan(contentAdapter?.style?.highlightColor ?: 0), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun buildChapterView(style: TypeStyle): AppCompatTextView =
        AppCompatTextView(context).apply {
            TextMeasurer.applyLayoutSettings(this)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, style.chapterPaint.textSize)
            typeface = style.chapterPaint.typeface
            setTextColor(style.chapterPaint.color)
            paint.isFakeBoldText = style.chapterPaint.isFakeBoldText
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = itemParams(
                topMargin = style.chapterTopGapPx.toInt(),
                bottomMargin = style.chapterBottomGapPx.toInt(),
            )
        }

    private fun buildSpacerView(style: TypeStyle): View {
        val h = style.paragraphSpacingPx.coerceAtLeast(
            style.textLineHeightPx.toFloat(),
        )
        return View(context).apply { layoutParams = itemParams(height = h.roundToInt()) }
    }

    private fun buildDividerView(style: TypeStyle, contentWidth: Float): View {
        val hInset = (contentWidth * 0.25f).toInt().coerceAtLeast(0)
        val vGap = (style.chapterTopGapPx * 0.8f).toInt()
        return View(context).apply {
            setBackgroundColor(style.dividerColor)
            layoutParams = itemParams(
                height = (1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                topMargin = vGap,
                bottomMargin = vGap,
                leftMargin = hInset,
                rightMargin = hInset,
            )
        }
    }

    private fun buildJumpView(style: TypeStyle): AppCompatTextView {
        val density = resources.displayMetrics.density
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = style.textPaint.textSize * 1.2f
            setStroke((2 * density).toInt(), style.linkColor)
            setColor(Color.TRANSPARENT)
        }
        return AppCompatTextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textPaint.textSize)
            typeface = style.textPaint.typeface
            setTextColor(style.linkColor)
            gravity = Gravity.CENTER
            background = drawable
            isClickable = true
            isFocusable = true
            // Padding gives the button visible breathing room; the inner
            // padding plus stroke produce a comfortably tappable height
            // (~48dp at default font size).
            val padV = (style.textPaint.textSize * 0.5f).toInt()
            val padH = (style.textPaint.textSize * 1f).toInt()
            setPadding(padH, padV, padH, padV)
            val side = (style.textPaint.textSize * 2f).toInt()
            layoutParams = itemParams(
                topMargin = style.paragraphSpacingPx.roundToInt(),
                bottomMargin = style.paragraphSpacingPx.roundToInt(),
                leftMargin = side,
                rightMargin = side,
            )
        }
    }

    private fun buildImageView(
        token: ContentToken,
        style: TypeStyle,
        url: String?,
    ): View {
        if (url == null) {
            return TextView(context).apply {
                text = context.getString(ceui.lisa.R.string.reader_image_placeholder)
                setTextColor(style.secondaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textPaint.textSize)
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        }
        return ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            if (token is ContentToken.PixivImage && token.isMix) {
                // 混排插画带圆角（issue #999）；adjustViewBounds 让 view 贴着图，outline 裁的就是图本身。
                val radius = resources.displayMetrics.density * PageRenderer.MIX_IMAGE_CORNER_DP
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                clipToOutline = true
            }
            Glide.with(context.applicationContext)
                .load(GlideUrlChild(url))
                .into(this)

            val imageElement = when (token) {
                is ContentToken.UploadedImage -> PageElement.Image(
                    top = 0f, bottom = 0f,
                    absoluteCharStart = token.sourceStart, absoluteCharEnd = token.sourceEnd,
                    imageType = PageElement.Image.ImageType.UploadedImage,
                    resourceId = token.imageId, pageIndexInIllust = 0, imageUrl = url,
                )
                is ContentToken.PixivImage -> PageElement.Image(
                    top = 0f, bottom = 0f,
                    absoluteCharStart = token.sourceStart, absoluteCharEnd = token.sourceEnd,
                    imageType = PageElement.Image.ImageType.PixivImage,
                    resourceId = token.illustId, pageIndexInIllust = token.pageIndex, imageUrl = url,
                    isMix = token.isMix,
                )
                else -> null
            }
            if (imageElement != null) {
                setOnClickListener { onImageTap?.invoke(imageElement) }
            }
        }
    }

    // ---- Selection helpers (paragraph TextViews) ---------------------------

    private fun populateMenu(menu: Menu) {
        menu.clear()
        selectionMenuEntries.forEachIndexed { index, (id, title) ->
            menu.add(Menu.NONE, id, index, title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    private fun notifyTvSelection(
        tv: AppCompatTextView,
        sourceStart: Int,
        cb: ((Int, Int, String) -> Unit)?,
    ) {
        if (cb == null) return
        val s = tv.selectionStart.coerceAtLeast(0)
        val e = tv.selectionEnd.coerceAtLeast(s)
        if (e <= s || e > tv.text.length) return
        val sliced = tv.text.subSequence(s, e).toString()
        cb(sourceStart + s, sourceStart + e, sliced)
    }

    // ---- Inline markup spans ------------------------------------------------

    private fun applyInlineSpans(
        spannable: SpannableString,
        inlineSpans: List<InlineSpan>,
        style: TypeStyle,
    ) {
        for (span in inlineSpans) {
            if (span.start < 0 || span.end > spannable.length || span.start >= span.end) continue
            when (val tag = span.tag) {
                is InlineTag.Link -> {
                    val linkColor = style.linkColor
                    spannable.setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                try {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(tag.url),
                                    )
                                    widget.context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toaster.showShort(tag.url)
                                }
                            }
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                ds.color = linkColor
                                ds.isUnderlineText = true
                            }
                        },
                        span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is InlineTag.Ruby -> {
                    // Ruby (furigana) — append as parenthesized annotation for now.
                    // Full ruby rendering would require custom spans; this is a
                    // readable fallback that preserves the info.
                }
            }
        }
    }

    private class ScrollSearchSpan(color: Int) : BackgroundColorSpan(color)

    private companion object {
        /** Above this gap (in items) a chapter jump teleports instead of
         *  animating — keeps far jumps O(1) rather than item-by-item. */
        const val SMOOTH_SCROLL_MAX_ITEMS = 40

        const val TYPE_PARAGRAPH = 0
        const val TYPE_CHAPTER = 1
        const val TYPE_SPACER = 2
        const val TYPE_DIVIDER = 3
        const val TYPE_IMAGE = 4
        const val TYPE_JUMP = 5
    }
}
