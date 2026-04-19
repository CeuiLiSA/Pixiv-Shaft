package ceui.pixiv.ui.novel.reader.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.ui.novel.reader.model.PageElement
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.util.concurrent.ConcurrentHashMap

/**
 * [ImageBitmapSource] backed by Glide. Kicks off an async bitmap load the first
 * time a page element is requested; re-queries once the bitmap is cached so the
 * reader redraws.
 *
 * Uses [GlideUrlChild] so Pixiv's referer check doesn't bounce the request.
 */
class GlideImageBitmapSource(
    private val context: Context,
    private val onBitmapReady: (PageElement.Image) -> Unit,
) : ImageBitmapSource {

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val targets = ConcurrentHashMap<String, CustomTarget<Bitmap>>()

    override fun bitmapFor(element: PageElement.Image): Bitmap? {
        val url = element.imageUrl ?: return null
        val key = keyFor(element)
        cache[key]?.let { if (!it.isRecycled) return it else cache.remove(key) }
        if (inFlight.putIfAbsent(key, true) != null) return null
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                cache[key] = resource
                inFlight.remove(key)
                targets.remove(key)
                onBitmapReady(element)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                inFlight.remove(key)
                targets.remove(key)
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                inFlight.remove(key)
                targets.remove(key)
            }
        }
        targets[key] = target
        Glide.with(context.applicationContext)
            .asBitmap()
            .load(GlideUrlChild(url))
            .into(target)
        return null
    }

    fun clear() {
        for (bmp in cache.values) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        cache.clear()
        for (target in targets.values) {
            runCatching { Glide.with(context.applicationContext).clear(target) }
        }
        targets.clear()
        inFlight.clear()
    }

    private fun keyFor(element: PageElement.Image): String =
        "${element.imageType}|${element.resourceId}|${element.pageIndexInIllust}"
}
