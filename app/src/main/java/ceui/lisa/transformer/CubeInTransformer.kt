package ceui.lisa.transformer

import android.view.View
import com.ToxicBakery.viewpager.transforms.ABaseTransformer

class CubeInTransformer : ABaseTransformer() {

    override val isPagingEnabled: Boolean
        get() = true

    override fun onTransform(page: View, position: Float) {
        page.cameraDistance = (page.width * 20).toFloat()
        page.pivotX = if (position > 0) 0f else page.width.toFloat()
        page.pivotY = page.height * 0.5f
        page.rotationY = -90f * position
    }
}
