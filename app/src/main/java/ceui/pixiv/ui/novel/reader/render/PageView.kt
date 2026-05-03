package ceui.pixiv.ui.novel.reader.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.FrameLayout
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageElement
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle

/**
 * A [FrameLayout] that renders exactly one [Page].
 *
 * Layout split:
 *  - **Text** elements are hosted as [ReaderTextBlockView] children so the
 *    platform provides native selection (handles + magnifier + action mode).
 *  - **Non-text** elements (chapter titles, inline images, spaces, background,
 *    overlays like search/annotation/TTS) are still painted by [PageRenderer]
 *    in [dispatchDraw] before the children draw.
 *
 * This view is cheap: the flip animator pools three instances (prev / curr /
 * next) and swaps their [page] as the user turns.
 */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var page: Page? = null
    private var style: TypeStyle? = null
    private var geometry: PageGeometry? = null
    private var bitmapSource: ImageBitmapSource = ImageBitmapSource.EMPTY
    private var overlays: PageOverlays = PageOverlays.EMPTY
    private var backgroundBitmap: Bitmap? = null

    private val textBlocks = mutableListOf<ReaderTextBlockView>()

    /** Snapshot of the page rendered as a Bitmap. Used by flip animators that
     *  need a static picture of a page while another view is being animated. */
    private var snapshotCache: Bitmap? = null

    var onBlockSelectionStart: ((block: ReaderTextBlockView, absStart: Int, absEnd: Int, text: CharSequence) -> Unit)? = null
    var onBlockSelectionChange: ((block: ReaderTextBlockView, absStart: Int, absEnd: Int, text: CharSequence) -> Unit)? = null
    var onBlockSelectionEnd: ((block: ReaderTextBlockView) -> Unit)? = null
    var blockMenuEntries: List<ReaderTextBlockView.MenuEntry> = emptyList()
    var onBlockMenuAction: ((id: Int) -> Unit)? = null
    /** Tap that landed on a text block and produced neither a selection nor a
     *  selection-dismissal — forwarded here in PageView coordinates so the
     *  host can run its tap-zone logic. */
    var onBlockBareTap: ((xInPage: Float, yInPage: Float) -> Unit)? = null

    init {
        // We render background + non-text ourselves; let children paint on top.
        setWillNotDraw(false)
    }

    fun bind(
        page: Page?,
        style: TypeStyle,
        geometry: PageGeometry,
        bitmapSource: ImageBitmapSource = this.bitmapSource,
        overlays: PageOverlays = PageOverlays.EMPTY,
        backgroundBitmap: Bitmap? = null,
    ) {
        this.page = page
        this.style = style
        this.geometry = geometry
        this.bitmapSource = bitmapSource
        this.overlays = overlays
        this.backgroundBitmap = backgroundBitmap
        rebuildTextBlocks()
        invalidateSnapshot()
        invalidate()
    }

    fun updateOverlays(overlays: PageOverlays) {
        this.overlays = overlays
        applyOverlayHighlightsToBlocks()
        invalidateSnapshot()
        invalidate()
    }

    private fun applyOverlayHighlightsToBlocks() {
        if (textBlocks.isEmpty()) return
        for (block in textBlocks) {
            block.applyOverlayHighlights(overlays.searchHits)
        }
    }

    fun updateBitmapSource(source: ImageBitmapSource) {
        this.bitmapSource = source
        invalidateSnapshot()
        invalidate()
    }

    fun currentPage(): Page? = page

    /** True iff any hosted text block has a non-empty native selection. */
    fun hasActiveTextSelection(): Boolean =
        textBlocks.any { it.visibility == VISIBLE && it.selectionStart != it.selectionEnd }

    /**
     * Scan the active page's elements and produce one [ReaderTextBlockView]
     * per contiguous run of text elements. Chapter / Image elements break a
     * run (they render via [PageRenderer] on the canvas). Merging consecutive
     * paragraphs into a single view is what lets native selection drag across
     * paragraph boundaries on the same page.
     */
    private fun rebuildTextBlocks() {
        val style = this.style
        val geometry = this.geometry
        val page = this.page
        if (style == null || geometry == null || page == null) {
            removeAllTextBlocks()
            return
        }

        val groups = groupTextElements(page.elements)

        while (textBlocks.size < groups.size) {
            val block = ReaderTextBlockView(context)
            textBlocks.add(block)
            addView(block)
        }
        while (textBlocks.size > groups.size) {
            val removed = textBlocks.removeAt(textBlocks.lastIndex)
            removeView(removed)
        }

        groups.forEachIndexed { i, group ->
            val block = textBlocks[i]
            block.visibility = VISIBLE
            block.onSelectionStarted = onBlockSelectionStart
            block.onSelectionChanged = onBlockSelectionChange
            block.onSelectionEnded = onBlockSelectionEnd
            block.menuEntries = blockMenuEntries
            block.onMenuAction = onBlockMenuAction
            block.onBareTap = { xInBlock, yInBlock ->
                // Translate from block coords → PageView coords: the block's
                // own (x, y) is the top-left of its layout within the page.
                onBlockBareTap?.invoke(block.x + xInBlock, block.y + yInBlock)
            }

            block.bindTextGroup(group, style)

            // Size the block to *exactly* the rect the paginator budgeted for
            // this group. Using WRAP_CONTENT + MATCH_PARENT + TextView padding
            // meant (a) the TextView could measure one line taller than the
            // page when any sub-pixel drift crept in between the paginator's
            // StaticLayout and the TextView's internal one, turning the block
            // into an internally scrollable area via ArrowKeyMovementMethod
            // (installed by setTextIsSelectable(true)); and (b) the effective
            // text width was `pageW - padL.toInt() - padR.toInt()` while the
            // paginator measured with `(pageW - padL - padR).toInt()` — up to
            // 2 px of disagreement that could shift line breaks.
            //
            // Fixing width to the paginator's `contentWidth.toInt()` and
            // positioning via `leftMargin = paddingLeft.toInt()` makes the two
            // measurement paths agree. Fixing height to `last.bottom -
            // first.top` caps any residual drift at a clipped sub-pixel at the
            // page's bottom edge instead of letting the block grow scrollable.
            val first = group.first()
            val last = group.last()
            val blockWidth = geometry.contentWidth.toInt().coerceAtLeast(1)
            val blockHeight = (last.bottom - first.top).toInt().coerceAtLeast(1)
            val lp = (block.layoutParams as? LayoutParams) ?: LayoutParams(blockWidth, blockHeight)
            lp.width = blockWidth
            lp.height = blockHeight
            lp.leftMargin = geometry.paddingLeft.toInt()
            lp.topMargin = first.top.toInt()
            block.layoutParams = lp
        }
        applyOverlayHighlightsToBlocks()
    }

    /**
     * Walk [elements] in order, grouping consecutive [PageElement.Text]
     * entries. [PageElement.Space] is transparent to the grouping — the blank
     * line it represents is already implicit in the paragraph separator we
     * insert in the merged text. [PageElement.Chapter] / [PageElement.Image]
     * break the current group.
     */
    private fun groupTextElements(elements: List<PageElement>): List<List<PageElement.Text>> {
        val groups = mutableListOf<MutableList<PageElement.Text>>()
        var current: MutableList<PageElement.Text>? = null
        for (element in elements) {
            when (element) {
                is PageElement.Text -> {
                    val group = current ?: mutableListOf<PageElement.Text>().also {
                        groups += it
                        current = it
                    }
                    group += element
                }
                is PageElement.Space -> Unit
                is PageElement.Chapter,
                is PageElement.Image,
                is PageElement.Jump,
                -> current = null
            }
        }
        return groups
    }

    private fun removeAllTextBlocks() {
        if (textBlocks.isEmpty()) return
        textBlocks.forEach { removeView(it) }
        textBlocks.clear()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val style = this.style
        val geo = this.geometry
        val page = this.page
        if (style != null && geo != null) {
            PageRenderer.drawBackground(canvas, width, height, style, backgroundBitmap)
            if (page != null) {
                PageRenderer.drawNonTextElements(
                    canvas = canvas,
                    page = page,
                    paddingLeft = geo.paddingLeft,
                    style = style,
                    overlays = overlays,
                    imageSource = bitmapSource,
                )
            }
        }
        super.dispatchDraw(canvas)
    }

    /** Rasterize the current content. Callers must release by calling
     *  [invalidateSnapshot] when the page contents change. */
    fun captureSnapshot(): Bitmap? {
        val cache = snapshotCache
        if (cache != null && !cache.isRecycled && cache.width == width && cache.height == height) {
            return cache
        }
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas)
        snapshotCache = bitmap
        return bitmap
    }

    fun invalidateSnapshot() {
        snapshotCache?.takeIf { !it.isRecycled }?.recycle()
        snapshotCache = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        invalidateSnapshot()
    }
}
