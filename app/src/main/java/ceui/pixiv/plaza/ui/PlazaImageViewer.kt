package ceui.pixiv.plaza.ui

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ceui.pixiv.plaza.PlazaImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.bumptech.glide.Glide

/** Reuse valid signatures; renew expired ones while retaining media-based disk cache keys. */
class PlazaImageViewer : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val imageRequests=Glide.with(this)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val close = ctx.action("关闭"); close.setTextColor(Color.WHITE); close.background = null
        val status = ctx.label("正在加载…").apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        bar.addView(close); bar.addView(status, LinearLayout.LayoutParams(0,-2,1f))
        root.addView(bar)
        val pager = ViewPager2(ctx)
        root.addView(pager, LinearLayout.LayoutParams(-1,0,1f))
        close.setOnClickListener { dismiss() }
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply { setContentView(root) }
        var loading=false
        fun load(force:Boolean=false) {
            if(loading) return
            loading=true
            status.text = "正在加载…"
            lifecycleScope.launch {
                try {
                    val cached=runCatching { com.google.gson.Gson().fromJson(requireArguments().getString("images"),Array<PlazaImage>::class.java).toList() }.getOrDefault(emptyList())
                    val images=if(!force && cached.isNotEmpty() && cached.all {it.expiresAt>System.currentTimeMillis()+5000}) cached
                        else PlazaRepository.api.post(requireArguments().getLong("post")).images
                    pager.adapter = object : RecyclerView.Adapter<ImageHolder>() {
                        override fun getItemCount() = images.size
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder {
                            val photo = ZoomImageView(parent.context)
                            photo.layoutParams = ViewGroup.LayoutParams(-1,-1)
                            return ImageHolder(photo)
                        }
                        override fun onBindViewHolder(holder: ImageHolder, position: Int) {
                            holder.photo.resetZoom()
                            holder.photo.contentDescription = "图片 ${position + 1}，双指缩放，左右滑动切换"
                            imageRequests.load(images[position].url.takeIf {it.isNotBlank()}?.let {PlazaMediaUrl(images[position],ceui.pixiv.session.SessionManager.loggedInUid)}).dontAnimate().error(android.R.drawable.ic_menu_report_image).into(holder.photo)
                        }
                        override fun onViewRecycled(holder: ImageHolder) { imageRequests.clear(holder.photo) }
                    }
                    pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) { status.text = "${position + 1} / ${images.size}" }
                    })
                    pager.setCurrentItem((savedInstanceState?.getInt("index") ?: requireArguments().getInt("index")).coerceIn(0,(images.size - 1).coerceAtLeast(0)), false)
                    status.text = if (images.isEmpty()) "没有图片" else "${pager.currentItem + 1} / ${images.size}"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status.text = plazaError(e) + " · 点按重试" }
                finally {loading=false}
            }
        }
        status.setOnClickListener { load(true) }
        pager.id = ceui.lisa.R.id.plaza_image_pager
        load()
        return dialog
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", dialog?.findViewById<ViewPager2>(ceui.lisa.R.id.plaza_image_pager)?.currentItem ?: 0)
    }
    private class ImageHolder(val photo: ZoomImageView) : RecyclerView.ViewHolder(photo)
    companion object {
        fun newInstance(post: ceui.pixiv.plaza.PlazaPost, index: Int) = PlazaImageViewer().apply { arguments = bundleOf("post" to post.id, "index" to index,"images" to com.google.gson.Gson().toJson(post.images)) }
    }
}

/** Fit-center decoding stays bounded by the screen; pinch zoom does not load an original-size bitmap. */
private class ZoomImageView(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private val detector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f); constrain(); invalidate(); return true
        }
    })
    init { scaleType = ImageView.ScaleType.FIT_CENTER }
    fun resetZoom() { zoom = 1f; offsetX = 0f; offsetY = 0f; invalidate() }
    private fun constrain() {
        offsetX = offsetX.coerceIn(-width * (zoom - 1) / 2, width * (zoom - 1) / 2)
        offsetY = offsetY.coerceIn(-height * (zoom - 1) / 2, height * (zoom - 1) / 2)
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val save = canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(zoom, zoom, width / 2f, height / 2f)
        super.onDraw(canvas); canvas.restoreToCount(save)
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        detector.onTouchEvent(event)
        parent.requestDisallowInterceptTouchEvent(event.pointerCount > 1 || zoom > 1f)
        if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE && !detector.isInProgress && zoom > 1f) {
            offsetX += event.x - lastX; offsetY += event.y - lastY; constrain(); invalidate()
        }
        lastX = event.x; lastY = event.y
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
