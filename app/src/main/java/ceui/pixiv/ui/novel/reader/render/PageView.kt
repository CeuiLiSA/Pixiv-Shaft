package ceui.pixiv.ui.novel.reader.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle

/**
 * A View that renders exactly one [Page].
 *
 * - Drawing is delegated to [PageRenderer] (stateless).
 * - Bitmap lookups for image pages go through [bitmapSource]; swap the source to
 *   rewire without touching the page data.
 * - Overlays (search / selection / annotation / TTS) are set from outside via
 *   [overlays] and trigger a redraw.
 *
 * This view is cheap: the flip animator pools three instances (prev / curr /
 * next) and swaps their [page] as the user turns.
 */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var page: Page? = null
    private var style: TypeStyle? = null
    private var geometry: PageGeometry? = null
    private var bitmapSource: ImageBitmapSource = ImageBitmapSource.EMPTY
    private var overlays: PageOverlays = PageOverlays.EMPTY
    private var backgroundBitmap: Bitmap? = null

    /** Snapshot of the page rendered as a Bitmap. Used by flip animators that
     *  need a static picture of a page while another view is being animated. */
    private var snapshotCache: Bitmap? = null

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
        invalidateSnapshot()
        invalidate()
    }

    fun updateOverlays(overlays: PageOverlays) {
        this.overlays = overlays
        invalidateSnapshot()
        invalidate()
    }

    fun updateBitmapSource(source: ImageBitmapSource) {
        this.bitmapSource = source
        invalidateSnapshot()
        invalidate()
    }

    fun currentPage(): Page? = page

    override fun onDraw(canvas: Canvas) {
        val style = this.style ?: return
        val geo = this.geometry ?: return
        PageRenderer.drawBackground(canvas, width, height, style, backgroundBitmap)
        val page = this.page ?: return
        PageRenderer.drawPage(
            canvas = canvas,
            page = page,
            paddingLeft = geo.paddingLeft,
            style = style,
            overlays = overlays,
            imageSource = bitmapSource,
        )
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
