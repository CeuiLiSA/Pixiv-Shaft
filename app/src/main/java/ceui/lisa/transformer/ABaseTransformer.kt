package ceui.lisa.transformer

import android.view.View
import androidx.viewpager.widget.ViewPager.PageTransformer
import kotlin.math.max

/**
 * ViewPager PageTransformer 原生 AndroidX 基类。
 * 替代旧版 com.ToxicBakery.viewpager.transforms.ABaseTransformer，消除对 support-v4 与 Jetifier 的依赖。
 */
abstract class ABaseTransformer : PageTransformer {

    protected open val isPagingEnabled: Boolean
        get() = false

    protected abstract fun onTransform(page: View, position: Float)

    override fun transformPage(page: View, position: Float) {
        val clampedPosition = clampPosition(position)
        onPreTransform(page, clampedPosition)
        onTransform(page, clampedPosition)
        onPostTransform(page, clampedPosition)
    }

    private fun clampPosition(position: Float): Float {
        return when {
            position < -1f -> -1f
            position > 1f -> 1f
            else -> position
        }
    }

    protected open fun hideOffscreenPages(): Boolean {
        return true
    }

    protected open fun onPreTransform(page: View, position: Float) {
        val width = page.width.toFloat()

        page.rotationX = 0f
        page.rotationY = 0f
        page.rotation = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.pivotX = 0f
        page.pivotY = 0f
        page.translationY = 0f
        page.translationX = if (isPagingEnabled) 0f else -width * position

        if (hideOffscreenPages()) {
            page.alpha = if (position <= -1f || position >= 1f) 0f else 1f
            page.isEnabled = false
        } else {
            page.isEnabled = true
            page.alpha = 1f
        }
    }

    protected open fun onPostTransform(page: View, position: Float) {}

    companion object {
        @JvmStatic
        protected fun min(value: Float, min: Float): Float {
            return max(value, min)
        }
    }
}
