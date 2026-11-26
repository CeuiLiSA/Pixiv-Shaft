package ceui.pixiv.ui.recommend

import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import ceui.lisa.databinding.CellTagRankBinding
import ceui.pixiv.ui.user.loadSquareMedia
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.witstudio.theme.cardSurface
import com.bumptech.glide.RequestManager

internal fun CellTagRankBinding.setupRankPreviewCard() {
    root.background = root.context.cardSurface()
    previewOne.clipToOutline = true
    previewTwo.clipToOutline = true
    previewThree.clipToOutline = true
}

internal fun CellTagRankBinding.bindRankImagePreviews(urls: List<String>, glide: RequestManager) {
    if (urls.isEmpty() && !previews.isVisible) return
    previews.isVisible = urls.isNotEmpty()
    listOf(previewOne, previewTwo, previewThree).forEachIndexed { index, image ->
        val url = urls.getOrNull(index)
        image.isInvisible = url.isNullOrBlank()
        image.loadSquareMedia(url, glide)
    }
}

internal fun CellTagRankBinding.clearRankImagePreviews() {
    if (!previews.isVisible) return
    previewOne.clearGlideOnRecycle()
    previewTwo.clearGlideOnRecycle()
    previewThree.clearGlideOnRecycle()
}
