package ceui.pixiv.ui.artlist

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.utils.GlideUtil
import ceui.lisa.helper.IllustNovelFilter
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.SkeletonBlock
import ceui.pixiv.witstudio.theme.*
import com.bumptech.glide.Glide

internal class ArtlistViewBinding(private val view: ArtlistCardView) : ViewBinding {
    override fun getRoot() = view
}

/** Covers have no reveal overlay; keep masked works out of this image surface. */
internal fun artlistCoverUrl(cover: Illust?): String? = cover?.takeUnless {
    IllustMuteStore.isMuted(it.id) || IllustNovelFilter.shouldBlurAi(it)
}?.image_urls?.medium

internal fun Context.artlistButton(title: CharSequence, primary: Boolean = false, action: () -> Unit): TextView {
    val palette = V3Palette.from(this)
    return label(title, 14f, 600, if (primary) palette.onPrimary else palette.textAccent).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = if (primary) palette.pillPrimary() else palette.pillSecondary()
        lineHeightRatio(1.35f)
        isFocusable = true
        pressScale()
        setOnClickListener { action() }
    }
}

/** Cover and typography share V3 resources; only the content column is capped on tablets. */
internal class ArtlistCardView(context: Context, private val header: Boolean = false) : FrameLayout(context) {
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        if (!header) background = context.cardSurface()
    }
    private val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = shape(context.dpF(17f), context.color(R.color.v3_surface_2))
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = context.label("", if (header) 26f else 19f, 700, context.color(R.color.v3_text_1)).apply {
        lineHeightRatio(1.3f)
        if (!header) { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    }
    private val owner = context.label("", 13f, 500, context.color(R.color.v3_text_2)).apply { lineHeightRatio(1.4f) }
    private val description = context.label("", 14f, 400, context.color(R.color.v3_text_2)).apply {
        lineHeightRatio(1.5f)
        if (!header) { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    }
    private val counts = context.label("", 13f, 500, context.color(R.color.v3_text_2)).apply { lineHeightRatio(1.4f) }
    private val status = context.label("", 14f, 600, V3Palette.from(context).textAccent)
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val empty = context.label("", 14f, 400, context.color(R.color.v3_text_2)).apply {
        gravity = Gravity.CENTER; lineHeightRatio(1.6f)
        setPadding(0, context.dp(32), 0, context.dp(32))
    }

    init {
        layoutParams = android.view.ViewGroup.LayoutParams(-1, -2)
        setPadding(context.dp(16), context.dp(6), context.dp(16), context.dp(6))
        addView(body, LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        body.addView(cover, LinearLayout.LayoutParams(-1, context.dp(if (header) 180 else 150)))
        listOf(title, owner, description, counts, status, actions, empty).forEach { view ->
            body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        }
        if (!header) { body.isClickable = true; body.isFocusable = true }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        body.layoutParams.width = minOf(available, context.dp(720))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun bind(item: Artlist, picker: Boolean = false, onClick: (() -> Unit)? = null) {
        title.text = item.name
        val author = item.ownerName.ifBlank { context.getString(R.string.artlist_creator_uid, item.uid) }
        owner.text = context.getString(R.string.artlist_creator, author)
        description.text = item.description
        description.isVisible = item.description.isNotBlank()
        counts.text = context.getString(R.string.artlist_counts, item.workCount, item.saveCount)
        status.isVisible = picker
        status.setText(if (item.containsWork) R.string.artlist_added else R.string.artlist_add)
        body.setOnClickListener { onClick?.invoke() }
        val url = artlistCoverUrl(item.cover)
        Glide.with(this).load(url?.let(GlideUtil::getUrl)).centerCrop()
            .placeholder(ColorDrawable(context.color(R.color.v3_surface_2))).into(cover)
        actions.removeAllViews()
        empty.isVisible = false
    }

    fun action(title: CharSequence, primary: Boolean = false, callback: () -> Unit) {
        actions.addView(context.artlistButton(title, primary, callback), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = context.dp(8)
        })
    }

    fun empty(text: CharSequence) { empty.text = text; empty.isVisible = true }
    fun recycle() { Glide.with(this).clear(cover) }
}

internal class ArtlistSkeleton(context: Context) : FeedSkeletonView(context) {
    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val width = minOf(w - 64 * density, 688 * density)
        if (width <= 0) return
        val left = (w - width) / 2
        var top = 22 * density
        while (top < h) {
            out.add(block(left, top, width, 150 * density, 17 * density))
            out.add(block(left, top + 164 * density, width * .65f, 20 * density, 4 * density))
            out.add(block(left, top + 196 * density, width * .45f, 13 * density, 4 * density))
            out.add(block(left, top + 224 * density, width * .8f, 14 * density, 4 * density))
            top += 284 * density
        }
    }
}
