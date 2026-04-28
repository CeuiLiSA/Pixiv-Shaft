package ceui.pixiv.ui.comic.reader

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max

/** ViewPager2 用的 PageTransformer 集合。每种动画都不要拦截 ZoomImageView 的手势。 */
object ComicPageTransformers {

    /** 默认平移：原生 ViewPager2 行为，不做额外效果。 */
    val Slide = ViewPager2.PageTransformer { _, _ -> }

    /**
     * Cover：当前页平移，下一页保持不动（被覆盖在底层）。bilibili 漫画使用类似效果。
     */
    val Cover = ViewPager2.PageTransformer { page, position ->
        page.translationZ = if (position <= 0) 1f else 0f
        page.translationX = if (position > 0) -position * page.width else 0f
    }

    /**
     * Depth：被翻走的页缩小并淡出，下一页保持。
     */
    val Depth = ViewPager2.PageTransformer { page, position ->
        when {
            position < -1f || position > 1f -> page.alpha = 0f
            position <= 0f -> {
                page.alpha = 1f
                page.translationX = 0f
                page.scaleX = 1f
                page.scaleY = 1f
            }
            else -> {
                page.alpha = 1f - position
                page.translationX = -page.width * position
                val scale = 0.75f + (1f - 0.75f) * (1f - abs(position))
                page.scaleX = scale
                page.scaleY = scale
            }
        }
    }

    /**
     * 简易书页翻转：以页面右边缘为轴翻转 0..-90°。无大量阴影计算，性能稳。
     */
    val FlipBook = ViewPager2.PageTransformer { page, position ->
        page.cameraDistance = 12000f
        when {
            position < -1f || position > 1f -> page.alpha = 0f
            position <= 0f -> {
                page.alpha = 1f
                page.rotationY = 0f
                page.translationX = 0f
            }
            else -> {
                page.alpha = max(0f, 1f - position)
                page.rotationY = -90f * position
                page.pivotX = page.width.toFloat()
                page.pivotY = page.height / 2f
                page.translationX = -page.width * position
            }
        }
    }
}
