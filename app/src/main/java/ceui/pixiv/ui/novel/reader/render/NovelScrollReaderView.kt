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

    private val lm = LinearLayoutManager(context)
    private var contentAdapter: ContentAdapter? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val w = width.toFloat()
            val third = w / 3f
            if (e.x > third && e.x < w - third) {
                onCenterTap?.invoke()
                return true
            }
            return false
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
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
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
            holder.applyHighlights(hits)
        }
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
            TYPE_IMAGE -> ImageHolder(context, style.paragraphSpacingPx.toInt())
            else -> JumpHolder(buildJumpView(style))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (val token = tokens[position]) {
                is ContentToken.Paragraph -> (holder as ParagraphHolder).bind(token, style, searchHits)
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
            boundSourceStart = token.sourceStart
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
            tv.text = spannable
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
            setLineSpacing(style.lineSpacingExtra, style.lineSpacingMultiplier)
            setBackgroundColor(Color.TRANSPARENT)
            highlightColor = style.selectionColor
            layoutParams = itemParams(bottomMargin = style.paragraphSpacingPx.toInt())
        }

    private fun bindChapter(tv: AppCompatTextView, token: ContentToken.Chapter, style: TypeStyle) {
        tv.text = token.title
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
            style.textPaint.fontMetrics.bottom - style.textPaint.fontMetrics.top,
        )
        return View(context).apply { layoutParams = itemParams(height = h.toInt()) }
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
                topMargin = style.paragraphSpacingPx.toInt(),
                bottomMargin = style.paragraphSpacingPx.toInt(),
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
