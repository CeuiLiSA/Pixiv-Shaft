package ceui.pixiv.chat.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.SendState
import ceui.pixiv.plaza.PlazaImage
import ceui.pixiv.plaza.ui.PlazaMediaUrl
import ceui.pixiv.session.SessionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class ChatAttachmentView(context: Context) : ViewGroup(context) {
    private var message: ChatMessageEntity? = null
    private var aspects = emptyList<Float>()
    private var cells = emptyList<ChatPhotoCell>()
    private var loadJob: Job? = null
    private var viewerUid = 0L
    private var previewExpiry: Long? = null
    private var retriedExpiry: Long? = null
    private val palette = chatPalette(context)

    init {
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(palette.alpha08)
        }
        clipToOutline = true
        visibility = GONE
    }

    fun bind(row: ChatMessageEntity?) {
        val viewer = SessionManager.loggedInUid
        if (message?.uid == row?.uid && message?.clientMsgId == row?.clientMsgId &&
            message?.localKey == row?.localKey && message?.state == row?.state && viewerUid == viewer) {
            return
        }
        loadJob?.cancel()
        clearImages()
        message = row
        viewerUid = viewer
        previewExpiry = null
        retriedExpiry = null
        aspects = emptyList()
        visibility = if (row == null) GONE else VISIBLE
        if (row != null) addView(preview(single = true))
        load()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (message != null && viewerUid != SessionManager.loggedInUid) {
            bind(message)
        } else if (aspects.isEmpty()) {
            load()
        } else if ((0 until childCount).any { (getChildAt(it) as ImageView).drawable == null }) {
            refreshExpiredPreviews()
        }
    }

    override fun onDetachedFromWindow() {
        loadJob?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cells = if (childCount == 0) emptyList() else
            layoutChatPhotoAlbum(width, dp(2), dp(48), aspects.ifEmpty { listOf(1.5f) })
        cells.forEachIndexed { index, cell ->
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(cell.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cell.height, MeasureSpec.EXACTLY),
            )
        }
        val height = cells.maxOfOrNull { it.y + it.height } ?: 0
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        cells.forEachIndexed { index, cell ->
            val x = if (rtl) width - cell.x - cell.width else cell.x
            getChildAt(index).layout(x, cell.y, x + cell.width, cell.y + cell.height)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    private fun load() {
        val row = message ?: return
        val ref = row.clientMsgId ?: return
        val viewer = viewerUid
        val scope = findViewTreeLifecycleOwner()?.lifecycleScope ?: return
        if (viewer <= 0 || viewer != SessionManager.loggedInUid || !isAttachedToWindow ||
            row.state != SendState.Delivered || loadJob?.isActive == true) return
        loadJob = scope.launch {
            try {
                val attachments = ChatImageViewer.images(row.uid, ref).take(9)
                if (message === row && isAttachedToWindow && viewer == SessionManager.loggedInUid && attachments.isNotEmpty()) {
                    render(row, attachments, viewer)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                getChildAt(0)?.setOnClickListener { load() }
            }
        }
    }

    private fun refreshExpiredPreviews() {
        val expiry = previewExpiry ?: return
        if (!isAttachedToWindow || loadJob?.isActive == true || expiry == retriedExpiry ||
            expiry > System.currentTimeMillis()) return
        retriedExpiry = expiry
        load()
    }

    private fun render(row: ChatMessageEntity, photos: List<PlazaImage>, viewer: Long) {
        clearImages()
        previewExpiry = photos.minOf { it.previewResource?.expiresAt ?: it.expiresAt }
        val ratios = photos.map { photo ->
            if (photo.width > 0 && photo.height > 0) photo.width.toFloat() / photo.height else 1f
        }.toMutableList()
        aspects = ratios
        photos.forEachIndexed { index, photo ->
            val image = preview(single = photos.size == 1)
            image.contentDescription = context.getString(R.string.plaza_image_accessibility, index + 1, photos.size)
            image.setOnClickListener {
                if (viewer == SessionManager.loggedInUid) ChatImageViewer.open(row, photos, index, image)
            }
            image.setOnLongClickListener { performLongClick() }
            addView(image)
            Glide.with(image).load(PlazaMediaUrl(photo, viewer, preview = true))
                .diskCacheStrategy(DiskCacheStrategy.NONE).dontTransform().dontAnimate()
                .listener(object : RequestListener<Drawable> {
                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>?,
                        dataSource: DataSource, isFirstResource: Boolean,
                    ): Boolean {
                        if (aspects !== ratios || viewer != SessionManager.loggedInUid) return true
                        // Decoded thumbnails account for EXIF rotation; encoded bounds do not.
                        if (resource.intrinsicWidth > 0 && resource.intrinsicHeight > 0) {
                            val ratio = resource.intrinsicWidth.toFloat() / resource.intrinsicHeight
                            if (abs(ratio / ratios[index] - 1f) > 0.02f) {
                                ratios[index] = ratio
                                requestLayout()
                            }
                        }
                        return false
                    }

                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean,
                    ): Boolean {
                        image.post { if (aspects === ratios) refreshExpiredPreviews() }
                        return false
                    }
                }).into(image)
        }
    }

    private fun preview(single: Boolean) = ImageView(context).apply {
        scaleType = if (single) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        foreground = RippleDrawable(ColorStateList.valueOf(palette.alpha20), null, GradientDrawable().apply {
            setColor(palette.cardFill)
        })
    }

    private fun clearImages() {
        for (index in 0 until childCount) Glide.with(this).clear(getChildAt(index))
        removeAllViews()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
