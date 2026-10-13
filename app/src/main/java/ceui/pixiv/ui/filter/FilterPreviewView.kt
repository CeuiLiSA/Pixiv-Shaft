package ceui.pixiv.ui.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * 滤镜舞台：完整显示（fit-center）当前渲染好的预览帧；[showOriginal] 时换成原图，用于「按住看原图」。
 *
 * 不用 ImageView：预览帧在三只缓冲之间轮换，每次 `setImageBitmap` 都会新建一个 BitmapDrawable；
 * 这里换帧只是换个引用再 invalidate。
 */
class FilterPreviewView(context: Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private var original: Bitmap? = null
    private var rendered: Bitmap? = null

    var showOriginal: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    fun setImages(original: Bitmap?, rendered: Bitmap?) {
        if (this.original === original && this.rendered === rendered) return
        this.original = original
        this.rendered = rendered
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = (if (showOriginal) original else rendered ?: original) ?: return
        if (bmp.isRecycled) return
        val s = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val w = bmp.width * s
        val h = bmp.height * s
        src.set(0, 0, bmp.width, bmp.height)
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(bmp, src, dst, paint)
    }
}
