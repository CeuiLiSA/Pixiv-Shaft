package ceui.pixiv.plaza.ui

import ceui.pixiv.plaza.PlazaImage
import com.bumptech.glide.load.model.GlideUrl

/** Media IDs identify immutable uploads. Signatures authorize transport, not image identity. */
internal class PlazaMediaUrl(image: PlazaImage, viewerUid: Long) : GlideUrl(image.url) {
    private val mediaKey = "plaza-media-v1:$viewerUid:${image.mediaId}"

    override fun getCacheKey() = mediaKey

    // Keep transport models distinct so a model-loader cache cannot revive an expired URL.
    // The disk cache key remains stable across signature rotation.
    override fun equals(other: Any?) =
        other is PlazaMediaUrl && mediaKey == other.mediaKey && toStringUrl() == other.toStringUrl()

    override fun hashCode() = 31 * mediaKey.hashCode() + toStringUrl().hashCode()
}
