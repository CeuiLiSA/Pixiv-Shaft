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
import android.util.LruCache
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
import ceui.loxia.User
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import java.security.MessageDigest

/**
 * 首页左上角侧边栏按钮头像化（推荐页 Toolbar、发现 / 动态的抽屉按钮、平板 Navigation Rail 菜单按钮）。
 *
 * - 同图短路：[R.id.drawer_avatar_key] 记下当前目标（URL + 尺寸 + 描边），一致且已有图时直接 return，
 *   onResume / 切 tab 不会反复清图重载，头像不闪。请求发出即落 key，失败时改记 [KEY_FAILED] 以便下次重试。
 * - 开关关闭：恢复菜单图标、tint 与原 scaleType，同样只在状态真正切换时动一次。
 * - 未登录 / 官方默认头像：直接用本地 no_profile 生成的圆形位图，不走网络。
 * - 占位只用访客位图：Glide 释放旧请求后旧位图可能被回收复用，不能拿来当占位或继续挂在 Toolbar 上。
 */
object DrawerAvatarHelper {

    private const val KEY_DISABLED = "__DRAWER_AVATAR_DISABLED__"
    private const val KEY_VISITOR = "__DRAWER_AVATAR_VISITOR__"
    // 不能清成 null：null 表示控件从没被头像化过，关闭开关时会被跳过，失败后的访客图就还原不回菜单图标
    private const val KEY_FAILED = "__DRAWER_AVATAR_FAILED__"

    // key = 尺寸 + 描边宽 + 描边色；只会随主题色变化多出几份，几十 KB 的小图
    private val fallbackCache = LruCache<String, Bitmap>(8)

    /** 带描边的圆形裁切。 */
    private class BorderedCircleCrop(
        private val borderWidthPx: Float,
        @ColorInt private val borderColor: Int
    ) : BitmapTransformation() {

        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            messageDigest.update("$ID.$borderWidthPx.$borderColor".toByteArray())
        }

        override fun transform(
            pool: BitmapPool,
            toTransform: Bitmap,
            outWidth: Int,
            outHeight: Int
        ): Bitmap {
            val circle = TransformationUtils.circleCrop(pool, toTransform, outWidth, outHeight)
            if (borderWidthPx <= 0f || borderColor == Color.TRANSPARENT) return circle
            val output = if (circle.isMutable) circle else circle.copy(Bitmap.Config.ARGB_8888, true)
            drawBorder(Canvas(output), output.width / 2f, borderWidthPx, borderColor)
            return output
        }

        override fun equals(other: Any?): Boolean =
            other is BorderedCircleCrop &&
                borderWidthPx == other.borderWidthPx && borderColor == other.borderColor

        override fun hashCode(): Int =
            31 * (31 * ID.hashCode() + borderWidthPx.hashCode()) + borderColor

        companion object {
            private const val ID = "ceui.pixiv.ui.navigation.BorderedCircleCrop"
        }
    }

    private fun drawBorder(canvas: Canvas, radius: Float, widthPx: Float, @ColorInt color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            this.color = color
        }
        canvas.drawCircle(radius, radius, radius - widthPx / 2f, paint)
    }

    private fun fallbackDrawable(
        context: Context,
        sizePx: Int,
        borderWidthPx: Float,
        @ColorInt borderColor: Int
    ): Drawable {
        val key = "$sizePx-$borderWidthPx-$borderColor"
        val bitmap = fallbackCache.get(key) ?: run {
            val raw = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            ContextCompat.getDrawable(context, R.drawable.no_profile)?.apply {
                setBounds(0, 0, sizePx, sizePx)
                draw(Canvas(raw))
            }
            val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val radius = sizePx / 2f
            canvas.drawCircle(radius, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            })
            if (borderWidthPx > 0f && borderColor != Color.TRANSPARENT) {
                drawBorder(canvas, radius, borderWidthPx, borderColor)
            }
            raw.recycle()
            output.also { fallbackCache.put(key, it) }
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    /** 当前账号的头像请求；null = 访客（未登录或官方默认头像），走本地位图。 */
    private fun headUrlOrNull(user: User?): GlideUrl? {
        val rawUrl = user?.profile_image_urls?.findMaxSizeUrl()
        if (rawUrl.isNullOrEmpty() || rawUrl == GlideUtil.DEFAULT_HEAD_IMAGE) return null
        return GlideUtil.getHead(user)
    }

    private fun targetKey(headUrl: GlideUrl?, sizePx: Int, borderWidthPx: Float, @ColorInt borderColor: Int): String =
        "${headUrl?.toStringUrl() ?: KEY_VISITOR}-$sizePx-$borderWidthPx-$borderColor"

    /** 绑定 ImageView/ImageButton（发现、动态的抽屉按钮），并跟随账号变化刷新。 */
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

    /** 按开关把头像（或菜单图标）应用到 ImageView/ImageButton。 */
    @JvmStatic
    @JvmOverloads
    fun applyToImageView(
        imageView: ImageView,
        sizeDp: Int = 32,
        withBorder: Boolean = true,
        @ColorInt borderColor: Int = ContextCompat.getColor(imageView.context, R.color.v3_border_2),
        @ColorInt defaultTint: Int = ContextCompat.getColor(imageView.context, R.color.v3_text_1)
    ) {
        val existingKey = imageView.getTag(R.id.drawer_avatar_key) as? String

        if (!Shaft.sSettings.isUseAccountAvatarAsDrawerIcon) {
            // 从没开过（tag 为空）时控件本来就是菜单图标，不用动
            if (existingKey == null || existingKey == KEY_DISABLED) return
            Glide.with(imageView).clear(imageView)
            ImageViewCompat.setImageTintList(imageView, ColorStateList.valueOf(defaultTint))
            (imageView.getTag(R.id.drawer_avatar_scale_type) as? ImageView.ScaleType)?.let {
                imageView.scaleType = it
            }
            imageView.setImageResource(R.drawable.ic_baseline_dns_24)
            imageView.setTag(R.id.drawer_avatar_key, KEY_DISABLED)
            return
        }

        val context = imageView.context
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        val borderWidthPx = if (withBorder) 0.8f * density else 0f
        val headUrl = headUrlOrNull(SessionManager.loggedInUser)
        val key = targetKey(headUrl, sizePx, borderWidthPx, borderColor)
        if (existingKey == key && imageView.drawable != null) return

        // 头像不吃菜单图标的 tint；按原尺寸居中，关掉开关时再还原 scaleType
        ImageViewCompat.setImageTintList(imageView, null)
        if (imageView.getTag(R.id.drawer_avatar_scale_type) == null) {
            imageView.setTag(R.id.drawer_avatar_scale_type, imageView.scaleType)
        }
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setTag(R.id.drawer_avatar_key, key)

        val fallback = fallbackDrawable(context, sizePx, borderWidthPx, borderColor)
        if (headUrl == null) {
            Glide.with(imageView).clear(imageView)
            imageView.setImageDrawable(fallback)
            return
        }
        Glide.with(imageView)
            .load(headUrl)
            .override(sizePx, sizePx)
            .transform(BorderedCircleCrop(borderWidthPx, borderColor))
            .placeholder(fallback)
            .error(fallback)
            .addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    // 失败换掉 key，网络恢复后下一次 onResume 会重试
                    if (imageView.getTag(R.id.drawer_avatar_key) == key) {
                        imageView.setTag(R.id.drawer_avatar_key, KEY_FAILED)
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean = false
            })
            .into(imageView)
    }

    /**
     * 推荐页实色顶栏（?attr/colorPrimary）上的头像描边色：取 [V3Palette.onPrimary]，
     * 深色主题色（感知亮度 < 0.5）上 50% 不透明，盛夏黄这类浅色主题色上 40%，避免白边在浅底上发虚。
     */
    @ColorInt
    private fun resolveToolbarBorderColor(context: Context): Int {
        val palette = V3Palette.from(context)
        val alpha = if (ColorUtils.calculateLuminance(palette.primary) < 0.5) 0x80 else 0x66
        return ColorUtils.setAlphaComponent(palette.onPrimary, alpha)
    }

    /** 绑定 Toolbar navigationIcon（推荐页 FragmentLeft），并跟随账号变化刷新。 */
    @JvmStatic
    @JvmOverloads
    fun bindToolbar(toolbar: Toolbar, lifecycleOwner: LifecycleOwner, sizeDp: Int = 32) {
        applyToToolbar(toolbar, sizeDp)
        SessionManager.loggedInAccount.observe(lifecycleOwner) {
            applyToToolbar(toolbar, sizeDp)
        }
    }

    /** 按开关把头像（或菜单图标）应用到 Toolbar navigationIcon。 */
    @JvmStatic
    @JvmOverloads
    fun applyToToolbar(toolbar: Toolbar, sizeDp: Int = 32) {
        val existingKey = toolbar.getTag(R.id.drawer_avatar_key) as? String

        if (!Shaft.sSettings.isUseAccountAvatarAsDrawerIcon) {
            if (existingKey == null || existingKey == KEY_DISABLED) return
            clearToolbarTarget(toolbar)
            toolbar.setNavigationIcon(R.drawable.ic_baseline_dns_24)
            toolbar.setTag(R.id.drawer_avatar_key, KEY_DISABLED)
            return
        }

        val context = toolbar.context
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        val borderWidthPx = 1.2f * density
        val borderColor = resolveToolbarBorderColor(context)
        val headUrl = headUrlOrNull(SessionManager.loggedInUser)
        val key = targetKey(headUrl, sizePx, borderWidthPx, borderColor)
        if (existingKey == key && toolbar.navigationIcon != null) return

        toolbar.setTag(R.id.drawer_avatar_key, key)
        // 清旧请求会回调旧 target 的 onLoadCleared，在那里换下旧位图
        clearToolbarTarget(toolbar)
        val fallback = fallbackDrawable(context, sizePx, borderWidthPx, borderColor)
        if (headUrl == null) {
            toolbar.navigationIcon = fallback
            return
        }

        val target = object : CustomTarget<Bitmap>(sizePx, sizePx) {
            override fun onLoadStarted(placeholder: Drawable?) {
                // 内存缓存命中时 onResourceReady 会在 into() 内同步回调，同一帧内不会看到占位
                toolbar.navigationIcon = fallback
            }

            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                toolbar.navigationIcon = BitmapDrawable(context.resources, resource)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                // 位图即将交还 Glide，不能再让 Toolbar 画它
                toolbar.navigationIcon = fallback
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (toolbar.getTag(R.id.drawer_avatar_key) == key) {
                    toolbar.setTag(R.id.drawer_avatar_key, KEY_FAILED)
                }
                toolbar.navigationIcon = fallback
            }
        }
        toolbar.setTag(R.id.drawer_avatar_target, target)
        Glide.with(toolbar)
            .asBitmap()
            .load(headUrl)
            .override(sizePx, sizePx)
            .transform(BorderedCircleCrop(borderWidthPx, borderColor))
            .into(target)
    }

    private fun clearToolbarTarget(toolbar: Toolbar) {
        val old = toolbar.getTag(R.id.drawer_avatar_target) as? CustomTarget<*> ?: return
        toolbar.setTag(R.id.drawer_avatar_target, null)
        Glide.with(toolbar).clear(old)
    }
}
