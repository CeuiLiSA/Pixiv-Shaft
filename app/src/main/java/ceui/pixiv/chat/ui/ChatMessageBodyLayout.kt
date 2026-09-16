package ceui.pixiv.chat.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import ceui.lisa.R
import ceui.pixiv.sticker.StickerImageView
import kotlin.math.ceil
import kotlin.math.max

/** Place the clock beside the final text line, falling back to a row inside the bubble.
 * Measure real text and metadata widths, including font scale and the failure marker.
 * No placeholder characters are added to message text or link spans.
 */
class ChatMessageBodyLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val gap get() = (6 * resources.displayMetrics.density).toInt()
    private val rowGap get() = (2 * resources.displayMetrics.density).toInt()
    private var metadataTop = 0
    private var contentLeft = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val text = findViewById<TextView>(R.id.tv_content)
        val metadata = findViewById<View>(R.id.message_meta)
        val image = (0 until childCount).map(::getChildAt)
            .firstOrNull { it is StickerImageView && it.visibility != GONE }
        val limit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            text.maxWidth
        } else minOf(MeasureSpec.getSize(widthMeasureSpec), text.maxWidth)
        val looseHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        metadata.measure(MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST), looseHeight)
        val content: View = image ?: text
        if (image != null) {
            image.measure(MeasureSpec.makeMeasureSpec(minOf(image.layoutParams.width, limit), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(image.layoutParams.height, MeasureSpec.EXACTLY))
        } else {
            text.measure(MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST), looseHeight)
        }
        val layout = if (image == null) text.layout else null
        val last = (layout?.lineCount ?: 1) - 1
        // Put RTL paragraphs on a separate metadata row; an empty visual right edge can be
        // occupied by bidi runs and isn't a safe place to infer a clock-sized gap.
        val canShare = image != null || (last >= 0 && layout?.getParagraphDirection(last) == 1)
        val desiredWidth = if (canShare && (image != null || last == 0)) {
            minOf(limit, content.measuredWidth + gap + metadata.measuredWidth)
        } else max(content.measuredWidth, metadata.measuredWidth)
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val endOfLine = if (image != null) image.measuredWidth else
            if (last >= 0 && layout != null) ceil(layout.getLineRight(last)).toInt() + text.totalPaddingLeft
            else content.measuredWidth
        val inline = canShare && endOfLine + gap + metadata.measuredWidth <= width
        metadataTop = if (!inline) {
            content.measuredHeight + rowGap
        } else if (image != null) {
            max(0, image.measuredHeight - metadata.measuredHeight)
        } else {
            val clock = findViewById<TextView>(R.id.tv_time)
            val clockBaseline = metadata.paddingTop +
                (metadata.measuredHeight - metadata.paddingTop - metadata.paddingBottom - clock.measuredHeight) / 2 +
                clock.baseline
            max(0, text.totalPaddingTop + checkNotNull(layout).getLineBaseline(last) - clockBaseline)
        }
        contentLeft = if (image != null && (image.layoutParams as LayoutParams).gravity == Gravity.END) {
            max(0, width - image.measuredWidth - if (inline) metadata.measuredWidth + gap else 0)
        } else 0
        setMeasuredDimension(width, resolveSize(max(content.measuredHeight, metadataTop + metadata.measuredHeight), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val x = if (child.id == R.id.message_meta) width - child.measuredWidth else contentLeft
            val y = if (child.id == R.id.message_meta) metadataTop else 0
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
