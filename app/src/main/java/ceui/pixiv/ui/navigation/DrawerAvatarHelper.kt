package ceui.pixiv.ui.navigation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.LifecycleOwner
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.GlideUtil
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import java.security.MessageDigest

/**
 * 侧边栏按钮头像化工具类（支持首页推荐/发现/动态以及平板侧边栏导航条）。
 *
 * 稳定性与防闪烁设计：
 * 1. 同图短路：比对 [R.id.user_head_icon_tag] 记录的目标指纹与现有 drawable，同图且已有位图时直接 return；
 *    绝不在 onResume 或 tab 切换时反复 clear / setImageDrawable(fallback) / 发起重复 Glide 解码，根治头像闪烁。
 * 2. 状态切换：开关关闭时短路记录 [TAG_DISABLED]，只在状态真正切换时恢复菜单图标与 tint，不产生无谓重排。
 * 3. 权威更新：监听 [SessionManager.loggedInAccount]，切号、修改资料、前台静默同步后自动刷新；
 *    新旧头像更替时以旧图平滑占位过渡，绝不中途闪回访客占位图。
 * 4. 离线/访客保底：未登录或官方默认头像走本地预置的圆形访客位图，0ms 瞬时渲染，不浪费网络请求。
 */
object DrawerAvatarHelper {

    private const val TAG_DISABLED = "__DRAWER_AVATAR_DISABLED__"
    private const val TAG_VISITOR = "__DRAWER_AVATAR_VISITOR__"

    private val fallbackCache = mutableMapOf<String, Bitmap>()

    /**
     * 带边框的圆形裁切 Transformation。
     */
    class BorderedCircleCrop(
        private val borderWidthPx: Float,
        @ColorInt private val borderColor: Int
    ) : BitmapTransformation() {

        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            messageDigest.update("ceui.pixiv.BorderedCircleCrop.$borderWidthPx.$borderColor".toByteArray())
        }

        override fun transform(
            pool: BitmapPool,
            toTransform: Bitmap,
            outWidth: Int,
            outHeight: Int
        ): Bitmap {
            val circleBitmap = TransformationUtils.circleCrop(pool, toTransform, outWidth, outHeight)
            if (borderWidthPx <= 0f || borderColor == Color.TRANSPARENT) {
                return circleBitmap
            }
            val mutableBitmap = if (circleBitmap.isMutable) {
                circleBitmap
            } else {
                circleBitmap.copy(Bitmap.Config.ARGB_8888, true)
            }
            val size = mutableBitmap.width
            val canvas = Canvas(mutableBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = borderWidthPx
                color = borderColor
            }
            val half = borderWidthPx / 2f
            canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - half, paint)
            return mutableBitmap
        }

        override fun equals(other: Any?): Boolean {
            if (other is BorderedCircleCrop) {
                return borderWidthPx == other.borderWidthPx && borderColor == other.borderColor
            }
            return false
        }

        override fun hashCode(): Int {
            var result = "ceui.pixiv.BorderedCircleCrop".hashCode()
            result = 31 * result + borderWidthPx.hashCode()
            result = 31 * result + borderColor
            return result
        }
    }

    /**
     * 获取访客默认圆形头像 Bitmap，带缓存。
     */
    @Synchronized
    fun getFallbackBitmap(
        context: Context,
        sizePx: Int,
        borderWidthPx: Float,
        @ColorInt borderColor: Int
    ): Bitmap {
        val key = "$sizePx-$borderWidthPx-$borderColor"
        fallbackCache[key]?.let {
            if (!it.isRecycled) return it
        }

        val rawDrawable = ContextCompat.getDrawable(context, R.drawable.no_profile)
        val rawBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val rawCanvas = Canvas(rawBitmap)
        rawDrawable?.setBounds(0, 0, sizePx, sizePx)
        rawDrawable?.draw(rawCanvas)

        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(rawBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        val radius = sizePx / 2f
        canvas.drawCircle(radius, radius, radius, paint)

        if (borderWidthPx > 0f && borderColor != Color.TRANSPARENT) {
            val halfBorder = borderWidthPx / 2f
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = borderWidthPx
                color = borderColor
            }
            canvas.drawCircle(radius, radius, radius - halfBorder, borderPaint)
        }

        rawBitmap.recycle()
        fallbackCache[key] = output
        return output
    }

    /**
     * 绑定 ImageView/ImageButton（发现、动态、平板 Navigation Rail 菜单按钮）。
     */
    @JvmStatic
    @JvmOverloads
    fun bindImageView(
        imageView: ImageView,
        lifecycleOwner: LifecycleOwner,
        sizeDp: Int = 32,
        withBorder: Boolean = true,
        @ColorInt borderColor: Int = ContextCompat.getColor(imageView.context, R.color.v3_border_2),
        @ColorInt defaultTint: Int = ContextCompat.getColor(imageView.context, R.color.v3_text_1)
    ) {
        applyToImageView(imageView, sizeDp, withBorder, borderColor, defaultTint)
        SessionManager.loggedInAccount.observe(lifecycleOwner) {
            applyToImageView(imageView, sizeDp, withBorder, borderColor, defaultTint)
        }
    }

    /**
     * 将头像（或回退图标）应用至 ImageView/ImageButton。
     */
    @JvmStatic
    @JvmOverloads
    fun applyToImageView(
        imageView: ImageView,
        sizeDp: Int = 32,
        withBorder: Boolean = true,
        @ColorInt borderColor: Int = ContextCompat.getColor(imageView.context, R.color.v3_border_2),
        @ColorInt defaultTint: Int = ContextCompat.getColor(imageView.context, R.color.v3_text_1)
    ) {
        val context = imageView.context
        val existingTag = imageView.getTag(R.id.user_head_icon_tag) as? String

        if (!Shaft.sSettings.isUseAccountAvatarAsDrawerIcon) {
            // 开关关闭态短路：已处于禁用态则直接返回，避免每次 onResume 重新 setImageResource 与重设 tint
            if (existingTag == TAG_DISABLED && imageView.drawable != null) {
                return
            }
            Glide.with(context).clear(imageView)
            ImageViewCompat.setImageTintList(imageView, ColorStateList.valueOf(defaultTint))
            imageView.setImageResource(R.drawable.ic_baseline_dns_24)
            imageView.setTag(R.id.user_head_icon_tag, TAG_DISABLED)
            return
        }

        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        val borderWidthPx = if (withBorder) (0.8f * density) else 0f

        val user = SessionManager.loggedInUser
        val rawUrl = user?.profile_image_urls?.findMaxSizeUrl()
        val isVisitor = user == null || rawUrl.isNullOrEmpty() || rawUrl == GlideUtil.DEFAULT_HEAD_IMAGE

        val targetKey = if (isVisitor) {
            "$TAG_VISITOR-$sizePx-$borderWidthPx-$borderColor"
        } else {
            "$rawUrl-$sizePx-$borderWidthPx-$borderColor"
        }

        // 头像防闪烁核心短路：若当前展示的目标与 URL/规格一致且图还在，直接 return，绝不清空重载
        if (existingTag == targetKey && imageView.drawable != null) {
            return
        }

        // 开关开启时，清除 tint 以免头像被滤镜单色染色
        ImageViewCompat.setImageTintList(imageView, null)
        imageView.colorFilter = null
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE

        val fallbackBitmap = getFallbackBitmap(context, sizePx, borderWidthPx, borderColor)
        val fallbackDrawable = BitmapDrawable(context.resources, fallbackBitmap)

        if (isVisitor) {
            Glide.with(context).clear(imageView)
            imageView.setImageDrawable(fallbackDrawable)
            imageView.setTag(R.id.user_head_icon_tag, targetKey)
            return
        }

        val headGlideUrl = GlideUtil.getHead(user)
        if (headGlideUrl == null) {
            Glide.with(context).clear(imageView)
            imageView.setImageDrawable(fallbackDrawable)
            imageView.setTag(R.id.user_head_icon_tag, targetKey)
            return
        }

        // 若当前已有图（如从旧头像切新头像），用已有图占位过渡；若为空则用圆形访客图占位
        val placeholder = imageView.drawable ?: fallbackDrawable

        Glide.with(context)
            .load(headGlideUrl)
            .override(sizePx, sizePx)
            .transform(BorderedCircleCrop(borderWidthPx, borderColor))
            .placeholder(placeholder)
            .error(fallbackDrawable)
            .addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    // 加载失败时清空 tag，以便后续网络恢复或返回时可重新尝试
                    imageView.setTag(R.id.user_head_icon_tag, null)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    // 仅在真实渲染成功后落 tag
                    imageView.setTag(R.id.user_head_icon_tag, targetKey)
                    return false
                }
            })
            .into(imageView)
    }

    /**
     * 实色顶栏（FragmentLeft Toolbar）描边自动计算标识（默认）。
     */
    const val BORDER_COLOR_AUTO: Int = 0

    /**
     * 根据当前主题色及其明暗度，动态解析推荐页实色顶栏（Toolbar）的头像描边色。
     *
     * 遵循 V3 设计规范：
     * - 深色/常规主题色（紫、蓝、粉等，感知亮度 < 0.5）：使用 50% 半透明纯白（0x80FFFFFF），在深色顶栏上呈现微透光晕；
     * - 高亮浅色主题色（如盛夏黄 #fee65e、浅绿等，感知亮度 ≥ 0.5）：使用 [V3Palette.onPrimary]
     *   （已压深的主题色），以 40% 透明度渲染，既具备主题色系基因，又能形成高对比轮廓，杜绝白边在黄底上的发虚隐形。
     */
    @ColorInt
    @JvmStatic
    fun resolveToolbarBorderColor(context: Context): Int {
        val palette = V3Palette.from(context)
        val onPrimary = palette.onPrimary
        val isDarkBg = ColorUtils.calculateLuminance(palette.primary) < 0.5
        val alpha = if (isDarkBg) 0x80 else 0x66
        return ColorUtils.setAlphaComponent(onPrimary, alpha)
    }

    /**
     * 绑定 Toolbar navigationIcon（推荐页 FragmentLeft）。
     */
    @JvmStatic
    @JvmOverloads
    fun bindToolbar(
        toolbar: Toolbar,
        lifecycleOwner: LifecycleOwner,
        sizeDp: Int = 32,
        withBorder: Boolean = true,
        @ColorInt borderColor: Int = BORDER_COLOR_AUTO
    ) {
        applyToToolbar(toolbar, sizeDp, withBorder, borderColor)
        SessionManager.loggedInAccount.observe(lifecycleOwner) {
            applyToToolbar(toolbar, sizeDp, withBorder, borderColor)
        }
    }

    /**
     * 将头像（或回退图标）应用至 Toolbar navigationIcon。
     */
    @JvmStatic
    @JvmOverloads
    fun applyToToolbar(
        toolbar: Toolbar,
        sizeDp: Int = 32,
        withBorder: Boolean = true,
        @ColorInt borderColor: Int = BORDER_COLOR_AUTO
    ) {
        val context = toolbar.context
        val existingTag = toolbar.getTag(R.id.user_head_icon_tag) as? String

        if (!Shaft.sSettings.isUseAccountAvatarAsDrawerIcon) {
            if (existingTag == TAG_DISABLED && toolbar.navigationIcon != null) {
                return
            }
            val oldTarget = toolbar.getTag(R.id.inflated_views_cache) as? CustomTarget<*>
            if (oldTarget != null) {
                Glide.with(context).clear(oldTarget)
                toolbar.setTag(R.id.inflated_views_cache, null)
            }
            toolbar.setNavigationIcon(R.drawable.ic_baseline_dns_24)
            toolbar.setTag(R.id.user_head_icon_tag, TAG_DISABLED)
            return
        }

        val resolvedBorderColor = if (withBorder) {
            if (borderColor == BORDER_COLOR_AUTO) resolveToolbarBorderColor(context) else borderColor
        } else {
            Color.TRANSPARENT
        }

        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        val borderWidthPx = if (withBorder) (1.2f * density) else 0f

        val user = SessionManager.loggedInUser
        val rawUrl = user?.profile_image_urls?.findMaxSizeUrl()
        val isVisitor = user == null || rawUrl.isNullOrEmpty() || rawUrl == GlideUtil.DEFAULT_HEAD_IMAGE

        val targetKey = if (isVisitor) {
            "$TAG_VISITOR-$sizePx-$borderWidthPx-$resolvedBorderColor"
        } else {
            "$rawUrl-$sizePx-$borderWidthPx-$resolvedBorderColor"
        }

        // Toolbar 防闪烁短路：若当前展示的目标与 URL/规格一致且图还在，直接 return
        if (existingTag == targetKey && toolbar.navigationIcon != null) {
            return
        }

        val fallbackBitmap = getFallbackBitmap(context, sizePx, borderWidthPx, resolvedBorderColor)
        val fallbackDrawable = BitmapDrawable(context.resources, fallbackBitmap)

        if (isVisitor) {
            val oldTarget = toolbar.getTag(R.id.inflated_views_cache) as? CustomTarget<*>
            if (oldTarget != null) {
                Glide.with(context).clear(oldTarget)
                toolbar.setTag(R.id.inflated_views_cache, null)
            }
            toolbar.navigationIcon = fallbackDrawable
            toolbar.setTag(R.id.user_head_icon_tag, targetKey)
            return
        }

        val headGlideUrl = GlideUtil.getHead(user)
        if (headGlideUrl == null) {
            val oldTarget = toolbar.getTag(R.id.inflated_views_cache) as? CustomTarget<*>
            if (oldTarget != null) {
                Glide.with(context).clear(oldTarget)
                toolbar.setTag(R.id.inflated_views_cache, null)
            }
            toolbar.navigationIcon = fallbackDrawable
            toolbar.setTag(R.id.user_head_icon_tag, targetKey)
            return
        }

        // 若当前未展示任何图标，先挂占位；若已有图标则保留原图标直至新图加载成功，避免闪白
        if (toolbar.navigationIcon == null) {
            toolbar.navigationIcon = fallbackDrawable
        }

        val oldTarget = toolbar.getTag(R.id.inflated_views_cache) as? CustomTarget<*>
        if (oldTarget != null) {
            Glide.with(context).clear(oldTarget)
        }

        val target = object : CustomTarget<Bitmap>(sizePx, sizePx) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                toolbar.navigationIcon = BitmapDrawable(context.resources, resource)
                toolbar.setTag(R.id.user_head_icon_tag, targetKey)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                if (toolbar.navigationIcon == null) {
                    toolbar.navigationIcon = placeholder ?: fallbackDrawable
                }
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                toolbar.setTag(R.id.user_head_icon_tag, null)
                if (toolbar.navigationIcon == null) {
                    toolbar.navigationIcon = errorDrawable ?: fallbackDrawable
                }
            }
        }
        toolbar.setTag(R.id.inflated_views_cache, target)

        Glide.with(context)
            .asBitmap()
            .load(headGlideUrl)
            .override(sizePx, sizePx)
            .transform(BorderedCircleCrop(borderWidthPx, resolvedBorderColor))
            .into(target)
    }
}
