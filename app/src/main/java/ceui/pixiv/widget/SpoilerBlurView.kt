package ceui.pixiv.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import jp.wasabeef.glide.transformations.BlurTransformation

/**
 * 「屏蔽」遮罩的观感组件：一张铺满自身的 Glide 模糊图 + 一层 [SpoilerParticleView] 粒子
 *（Telegram spoiler 那套）。放进详情页的整页遮罩 `abandoned_frame` 里当底，让「点了屏蔽」
 * 之后整页是糊的作品图 + 飘着的粒子，而不是一块纯黑。
 *
 * 只负责画，不管显隐：宿主把它放在哪个容器里、那个容器什么时候可见，它就什么时候跑
 *（[SpoilerParticleView] 自己按 `onVisibilityAggregated` / attach 状态接管粒子模拟，
 * 遮罩 GONE 时自动停，不会在后台空转）。
 *
 * 摆放约定：作为遮罩容器的**第一个**子 view，取消屏蔽 / 离开这些按钮排在它后面盖在上面。
 */
class SpoilerBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val blurImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /**
     * 已加载封面的 cacheKey（= 图片 URL）。宿主的 observer 每次发射都会调 [bind]，同一张图不该
     * 重发请求。
     *
     * 记 cacheKey 而不是 [GlideUrl] 本身：[ceui.lisa.utils.GlideUrlChild] 每次构造都带当前
     * 时间戳的请求头，而 `GlideUrl.equals()` 连 headers 一起比、那个 headers 又是个没重写
     * equals 的 lambda —— 拿对象比等于永远不相等，这层去重会形同虚设。
     */
    private var boundCoverKey: String? = null

    init {
        addView(blurImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            SpoilerParticleView(context),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /**
     * 贴上要糊的封面。传中图即可 —— 列表里通常已经缓存过，遮罩几乎是秒出，而且糊到这个程度
     * 再高的分辨率也看不出区别。[coverUrl] 为 null（bean 还没到 / 没有可用缩略图）时保持原样，
     * 露出容器自己的兜底底色。
     *
     * ⚠️ 只在遮罩**真的要显示**时调用：模糊是一次实打实的解码 + 变换（还要占一份位图内存），
     * 没被屏蔽的作品白跑一趟纯属浪费。
     */
    fun bind(glide: RequestManager, coverUrl: GlideUrl?) {
        if (coverUrl == null || boundCoverKey == coverUrl.cacheKey) return
        boundCoverKey = coverUrl.cacheKey
        glide
            .load(coverUrl)
            .apply(bitmapTransform(BlurTransformation(BLUR_RADIUS, BLUR_SAMPLING)))
            // 不设 placeholder：容器(abandoned_frame)自己就是不透明黑底，
            // 模糊图淡入之前露的就是它，不需要再垫一层
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(blurImage)
    }

    private companion object {
        /** 与瀑布流卡片的屏蔽模糊同参，两处观感一致。 */
        const val BLUR_RADIUS = 25
        const val BLUR_SAMPLING = 3
    }
}
