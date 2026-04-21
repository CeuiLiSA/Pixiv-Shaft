package ceui.pixiv.ui.novel.reader.render

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.GestureDetectorCompat
import androidx.core.widget.NestedScrollView
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import com.bumptech.glide.Glide

/**
 * Vertical infinite-scroll reader — no pagination, all tokens laid out
 * sequentially in a [LinearLayout] inside a [NestedScrollView].
 *
 * Replaces [NovelReaderView] when [ceui.pixiv.ui.novel.reader.model.FlipMode.Scroll]
 * is active. Supports center-tap for chrome toggle, image tap, and scroll
 * position tracking for reading progress persistence.
 */
class NovelScrollReaderView(context: Context) : NestedScrollView(context) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** charStart → child view, for scroll-to-position and position reporting. */
    private val charAnchors = mutableListOf<CharAnchor>()

    var onCenterTap: (() -> Unit)? = null
    var onImageTap: ((ceui.pixiv.ui.novel.reader.model.PageElement.Image) -> Unit)? = null
    var onCharIndexChanged: ((Int) -> Unit)? = null

    /** Text selection callbacks — mirrors ReaderTextBlockView's interface. */
    var onSelectionStarted: ((absStart: Int, absEnd: Int, text: String) -> Unit)? = null
    var onSelectionChanged: ((absStart: Int, absEnd: Int, text: String) -> Unit)? = null
    var onSelectionEnded: (() -> Unit)? = null
    var selectionMenuEntries: List<Pair<Int, String>> = emptyList()
    var onSelectionMenuAction: ((id: Int) -> Unit)? = null

    private val gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
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
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isVerticalScrollBarEnabled = true
        isFillViewport = false
        setOnScrollChangeListener { _, _, _, _, _ -> reportVisibleCharIndex() }
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
        container.removeAllViews()
        charAnchors.clear()
        setBackgroundColor(style.backgroundColor)
        container.setPadding(
            geometry.paddingLeft.toInt(),
            geometry.paddingTop.toInt(),
            geometry.paddingRight.toInt(),
            geometry.paddingBottom.toInt(),
        )
        val contentWidth = geometry.contentWidth
        for (token in tokens) {
            val child = when (token) {
                is ContentToken.Paragraph -> createParagraph(token, style)
                is ContentToken.Chapter -> createChapter(token, style)
                is ContentToken.BlankLine -> createSpacer(style)
                is ContentToken.PageBreak -> createDivider(style, contentWidth)
                is ContentToken.PixivImage -> createImage(token, style, imageResolver)
                is ContentToken.UploadedImage -> createImage(token, style, imageResolver)
            }
            container.addView(child)
            charAnchors += CharAnchor(token.sourceStart, child)
        }
    }

    fun scrollToCharIndex(charIndex: Int) {
        val target = charAnchors.lastOrNull { it.charStart <= charIndex }?.view ?: return
        post { smoothScrollTo(0, target.top) }
    }

    fun jumpToCharIndex(charIndex: Int) {
        val target = charAnchors.lastOrNull { it.charStart <= charIndex }?.view ?: return
        post { scrollTo(0, target.top) }
    }

    fun currentCharIndex(): Int {
        val scrollTop = scrollY
        return charAnchors.lastOrNull { it.view.top <= scrollTop + height / 4 }?.charStart ?: 0
    }

    // ---- View factories -----------------------------------------------------

    private fun createParagraph(token: ContentToken.Paragraph, style: TypeStyle): AppCompatTextView {
        val sourceStart = token.sourceStart
        return AppCompatTextView(context).apply {
            TextMeasurer.applyLayoutSettings(this)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textPaint.textSize)
            typeface = style.textPaint.typeface
            setTextColor(style.textPaint.color)
            letterSpacing = style.textPaint.letterSpacing
            setLineSpacing(style.lineSpacingExtra, style.lineSpacingMultiplier)
            setBackgroundColor(Color.TRANSPARENT)
            highlightColor = style.selectionColor
            setTextIsSelectable(true)

            val indent = style.firstLineIndentPx.toInt()
            if (indent > 0 && token.text.isNotEmpty()) {
                val spannable = SpannableString(token.text)
                spannable.setSpan(
                    LeadingMarginSpan.Standard(indent, 0),
                    0, token.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                text = spannable
            } else {
                text = token.text
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = style.paragraphSpacingPx.toInt()
            }

            customSelectionActionModeCallback = buildSelectionCallback(this, sourceStart)
        }
    }

    private fun buildSelectionCallback(tv: AppCompatTextView, sourceStart: Int): ActionMode.Callback {
        return object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                populateMenu(menu)
                notifySelection(tv, sourceStart, onSelectionStarted)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                populateMenu(menu)
                notifySelection(tv, sourceStart, onSelectionChanged)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                notifySelection(tv, sourceStart, onSelectionChanged)
                onSelectionMenuAction?.invoke(item.itemId)
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                onSelectionEnded?.invoke()
            }
        }
    }

    private fun populateMenu(menu: Menu) {
        menu.clear()
        selectionMenuEntries.forEachIndexed { index, (id, title) ->
            menu.add(Menu.NONE, id, index, title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    private fun notifySelection(
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

    private fun createChapter(token: ContentToken.Chapter, style: TypeStyle): AppCompatTextView {
        return AppCompatTextView(context).apply {
            TextMeasurer.applyLayoutSettings(this)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, style.chapterPaint.textSize)
            typeface = style.chapterPaint.typeface
            setTextColor(style.chapterPaint.color)
            paint.isFakeBoldText = style.chapterPaint.isFakeBoldText
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            text = token.title
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = style.chapterTopGapPx.toInt()
                bottomMargin = style.chapterBottomGapPx.toInt()
            }
        }
    }

    private fun createSpacer(style: TypeStyle): android.view.View {
        val height = style.paragraphSpacingPx.coerceAtLeast(
            style.textPaint.fontMetrics.bottom - style.textPaint.fontMetrics.top,
        )
        return android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height.toInt(),
            )
        }
    }

    private fun createDivider(style: TypeStyle, contentWidth: Float): android.view.View {
        return android.view.View(context).apply {
            setBackgroundColor(style.dividerColor)
            val hInset = (contentWidth * 0.25f).toInt().coerceAtLeast(0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1.5f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
            ).apply {
                val vGap = (style.chapterTopGapPx * 0.8f).toInt()
                topMargin = vGap
                bottomMargin = vGap
                leftMargin = hInset
                rightMargin = hInset
            }
        }
    }

    private fun createImage(
        token: ContentToken,
        style: TypeStyle,
        imageResolver: (ContentToken) -> String?,
    ): android.view.View {
        val url = imageResolver(token)
        if (url == null) {
            return TextView(context).apply {
                text = "[图片]"
                setTextColor(style.secondaryTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textPaint.textSize)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = style.paragraphSpacingPx.toInt()
                    bottomMargin = style.paragraphSpacingPx.toInt()
                }
            }
        }
        return ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = style.paragraphSpacingPx.toInt()
                bottomMargin = style.paragraphSpacingPx.toInt()
            }
            Glide.with(context.applicationContext)
                .load(GlideUrlChild(url))
                .into(this)

            val imageElement = when (token) {
                is ContentToken.UploadedImage -> ceui.pixiv.ui.novel.reader.model.PageElement.Image(
                    top = 0f, bottom = 0f,
                    absoluteCharStart = token.sourceStart, absoluteCharEnd = token.sourceEnd,
                    imageType = ceui.pixiv.ui.novel.reader.model.PageElement.Image.ImageType.UploadedImage,
                    resourceId = token.imageId, pageIndexInIllust = 0, imageUrl = url,
                )
                is ContentToken.PixivImage -> ceui.pixiv.ui.novel.reader.model.PageElement.Image(
                    top = 0f, bottom = 0f,
                    absoluteCharStart = token.sourceStart, absoluteCharEnd = token.sourceEnd,
                    imageType = ceui.pixiv.ui.novel.reader.model.PageElement.Image.ImageType.PixivImage,
                    resourceId = token.illustId, pageIndexInIllust = token.pageIndex, imageUrl = url,
                )
                else -> null
            }
            if (imageElement != null) {
                setOnClickListener { onImageTap?.invoke(imageElement) }
            }
        }
    }

    // ---- Position tracking --------------------------------------------------

    private var pendingReport: Runnable? = null

    private fun reportVisibleCharIndex() {
        if (pendingReport != null) return // already scheduled
        val runnable = Runnable {
            pendingReport = null
            val scrollTop = scrollY
            val anchor = charAnchors.lastOrNull { it.view.top <= scrollTop + height / 4 }
            anchor?.let { onCharIndexChanged?.invoke(it.charStart) }
        }
        pendingReport = runnable
        postDelayed(runnable, 500L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pendingReport?.let { removeCallbacks(it) }
        pendingReport = null
    }

    private data class CharAnchor(val charStart: Int, val view: android.view.View)
}
