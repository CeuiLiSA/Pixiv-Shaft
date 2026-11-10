package ceui.lisa.transformer

import android.graphics.Camera
import android.graphics.Matrix
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * 经典 ViewPager 页面切换变换器集合（纯原生 AndroidX 实现）。
 * 替代旧版 com.ToxicBakery.viewpager.transforms.*，彻底剥离废弃库与 Jetifier。
 */

open class AccordionTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val width = page.width.toFloat()
        page.scaleX = if (position < 0) 1f + position else 1f - position
        page.pivotX = if (position < 0) 0f else width
    }
}

open class BackgroundToForegroundTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val height = page.height.toFloat()
        val width = page.width.toFloat()
        val scale = min(if (position < 0) 1f else abs(1f - position), 0.5f)

        page.scaleX = scale
        page.scaleY = scale
        page.pivotX = width * 0.5f
        page.pivotY = height * 0.5f
        page.translationX = if (position < 0) width * position else -width * position * 0.25f
    }
}

open class ForegroundToBackgroundTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val height = page.height.toFloat()
        val width = page.width.toFloat()
        val scale = min(if (position > 0) 1f else abs(1f + position), 0.5f)

        page.scaleX = scale
        page.scaleY = scale
        page.pivotX = width * 0.5f
        page.pivotY = height * 0.5f
        page.translationX = if (position > 0) width * position else -width * position * 0.25f
    }
}

open class CubeOutTransformer @JvmOverloads constructor(
    private val distanceMultiplier: Int = 20
) : ABaseTransformer() {

    public override val isPagingEnabled: Boolean
        get() = true

    override fun onTransform(page: View, position: Float) {
        page.cameraDistance = (page.width * distanceMultiplier).toFloat()
        page.pivotX = if (position < 0f) page.width.toFloat() else 0f
        page.pivotY = page.height * 0.5f
        page.rotationY = 90f * position
    }
}

open class DefaultTransformer : ABaseTransformer() {
    public override val isPagingEnabled: Boolean
        get() = true

    override fun onTransform(page: View, position: Float) = Unit
}

open class DepthPageTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        if (position <= 0f) {
            page.translationX = 0f
            page.scaleX = 1f
            page.scaleY = 1f
        } else if (position <= 1f) {
            val scaleFactor = MIN_SCALE + (1 - MIN_SCALE) * (1 - abs(position))
            page.alpha = 1 - position
            page.pivotY = 0.5f * page.height
            page.translationX = page.width * -position
            page.scaleX = scaleFactor
            page.scaleY = scaleFactor
        }
    }

    override fun hideOffscreenPages(): Boolean = true

    companion object {
        private const val MIN_SCALE = 0.75f
    }
}

open class DrawerTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        if (position <= 0f) {
            page.translationX = 0f
        } else if (position <= 1f) {
            page.translationX = -page.width / 2f * position
        }
    }
}

open class FlipHorizontalTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val rotation = 180f * position
        page.alpha = if (rotation > 90f || rotation < -90f) 0f else 1f
        page.pivotX = page.width * 0.5f
        page.pivotY = page.height * 0.5f
        page.rotationY = rotation
    }

    override fun onPostTransform(page: View, position: Float) {
        super.onPostTransform(page, position)
        // Transparent back faces overlap the selected page and still receive child touches.
        page.visibility = if (position > -0.5f && position < 0.5f) View.VISIBLE else View.INVISIBLE
    }
}

open class FlipVerticalTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val rotation = -180f * position
        page.alpha = if (rotation > 90f || rotation < -90f) 0f else 1f
        page.pivotX = page.width * 0.5f
        page.pivotY = page.height * 0.5f
        page.rotationX = rotation
    }

    override fun onPostTransform(page: View, position: Float) {
        super.onPostTransform(page, position)
        // Transparent back faces overlap the selected page and still receive child touches.
        page.visibility = if (position > -0.5f && position < 0.5f) View.VISIBLE else View.INVISIBLE
    }
}

open class RotateDownTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val width = page.width.toFloat()
        val height = page.height.toFloat()
        val rotation = ROT_MOD * position * -1.25f

        page.pivotX = width * 0.5f
        page.pivotY = height
        page.rotation = rotation
    }

    override val isPagingEnabled: Boolean
        get() = true

    companion object {
        private const val ROT_MOD = -15f
    }
}

open class RotateUpTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val width = page.width.toFloat()
        val rotation = ROT_MOD * position

        page.pivotX = width * 0.5f
        page.pivotY = 0f
        page.translationX = 0f
        page.rotation = rotation
    }

    override val isPagingEnabled: Boolean
        get() = true

    companion object {
        private const val ROT_MOD = -15f
    }
}

open class ScaleInOutTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        page.pivotX = if (position < 0) 0f else page.width.toFloat()
        page.pivotY = page.height / 2f
        val scale = if (position < 0) 1f + position else 1f - position
        page.scaleX = scale
        page.scaleY = scale
    }
}

open class StackTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        page.translationX = if (position >= 0) -page.width * position else 0f
    }
}

open class TabletTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val rotation = if (position < 0) ROT_MOD else -ROT_MOD
        val rotationY = rotation * abs(position)

        page.translationX = getOffsetXForRotation(rotationY, page.width, page.height)
        page.pivotX = page.width * 0.5f
        page.pivotY = 0f
        page.rotationY = rotationY
    }

    companion object {
        private val OFFSET_MATRIX = Matrix()
        private val OFFSET_CAMERA = Camera()
        private val OFFSET_TEMP_FLOAT = FloatArray(2)
        private const val ROT_MOD = 30f

        @JvmStatic
        protected fun getOffsetXForRotation(degrees: Float, width: Int, height: Int): Float {
            OFFSET_MATRIX.reset()
            OFFSET_CAMERA.save()
            OFFSET_CAMERA.rotateY(abs(degrees))
            OFFSET_CAMERA.getMatrix(OFFSET_MATRIX)
            OFFSET_CAMERA.restore()

            OFFSET_MATRIX.preTranslate(-width * 0.5f, -height * 0.5f)
            OFFSET_MATRIX.postTranslate(width * 0.5f, height * 0.5f)
            OFFSET_TEMP_FLOAT[0] = width.toFloat()
            OFFSET_TEMP_FLOAT[1] = height.toFloat()
            OFFSET_MATRIX.mapPoints(OFFSET_TEMP_FLOAT)
            return (width - OFFSET_TEMP_FLOAT[0]) * if (degrees > 0.0f) 1.0f else -1.0f
        }
    }
}

open class ZoomInTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val scale = if (position < 0) position + 1f else abs(1f - position)
        page.scaleX = scale
        page.scaleY = scale
        page.pivotX = page.width * 0.5f
        page.pivotY = page.height * 0.5f
        page.alpha = if (position < -1f || position > 1f) 0f else 1f - (scale - 1f)
    }
}

open class ZoomOutSlideTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        if (position >= -1 || position <= 1) {
            val height = page.height.toFloat()
            val width = page.width.toFloat()
            val scaleFactor = max(MIN_SCALE, 1 - abs(position))
            val vertMargin = height * (1 - scaleFactor) / 2
            val horzMargin = width * (1 - scaleFactor) / 2

            page.pivotY = 0.5f * height
            page.pivotX = 0.5f * width

            page.translationX =
                if (position < 0) horzMargin - vertMargin / 2
                else -horzMargin + vertMargin / 2

            page.scaleX = scaleFactor
            page.scaleY = scaleFactor

            page.alpha = MIN_ALPHA + (scaleFactor - MIN_SCALE) / (1 - MIN_SCALE) * (1 - MIN_ALPHA)
        }
    }

    companion object {
        private const val MIN_SCALE = 0.85f
        private const val MIN_ALPHA = 0.5f
    }
}

open class ZoomOutTransformer : ABaseTransformer() {
    override fun onTransform(page: View, position: Float) {
        val scale = 1f + abs(position)
        page.scaleX = scale
        page.scaleY = scale
        page.pivotX = page.width * 0.5f
        page.pivotY = page.height * 0.5f
        page.alpha = if (position < -1f || position > 1f) 0f else 1f - (scale - 1f)
        if (position == -1f) page.translationX = (page.width * -1).toFloat()
    }
}
