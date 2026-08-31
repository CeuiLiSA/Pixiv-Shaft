package ceui.pixiv.banner

import android.widget.ImageView

/**
 * Host-supplied loader for [BannerIcon.Url]. The banner module deliberately
 * has no image library dependency; the host plugs in whatever it already uses
 * (Glide, Coil…) and owns placeholder / referer / cropping policy.
 *
 * Called on the main thread from [DefaultBannerViewBinder.bind]. The target
 * is a 44dp circular slot — loaders should crop to a circle.
 */
fun interface BannerIconLoader {
    fun load(target: ImageView, url: String)
}
