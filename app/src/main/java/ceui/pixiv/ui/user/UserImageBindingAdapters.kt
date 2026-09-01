package ceui.pixiv.ui.user

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.databinding.BindingAdapter
import ceui.lisa.R
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import com.bumptech.glide.request.target.Target
import jp.wasabeef.glide.transformations.BlurTransformation

/**
 * 从已删的 UserFollowingFragment.kt 抽出的全局图片 DataBinding 适配器。
 * 这些 @BindingAdapter(userIcon / loadSquareMedia / loadMedia / loadBlurredMedia)被大量
 * 存活布局的 app: 属性引用,[binding_loadUserIcon] 还被 CommentCardRenderer / ArtworkDetailAdapter
 * 直接调用,[NO_PROFILE_IMG] 被 ImageHostManager 引用 —— 故随框架清理搬到独立文件
 * (包名保持 ceui.pixiv.ui.user,现有 import / XML 属性名均不变)。
 */

const val NO_PROFILE_IMG = "https://s.pximg.net/common/images/no_profile.png"

@BindingAdapter("userIcon")
fun ImageView.binding_loadUserIcon(user: User?) {
    // 部分账号(如被限制/注销)API 不返回任何头像字段;退化成 NO_PROFILE_IMG 走同一条网络加载
    // 路径,跟 UserActivityV3/GlideUtil.getHead 拿到的官方"nO imaGe"占位图保持一致,
    // 而不是本地随手换一张不一样的通用小人图标
    val url = user?.profile_image_urls?.findMaxSizeUrl() ?: NO_PROFILE_IMG

    val self = this

    val existing = self.getTag(R.id.user_head_icon_tag) as? String
    if (existing == url) {
        return
    }

    scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(GlideUrlChild(url))
        .placeholder(R.drawable.icon_user_mask)
        .addListener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                self.setTag(R.id.user_head_icon_tag, null)
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: com.bumptech.glide.load.DataSource,
                isFirstResource: Boolean
            ): Boolean {
                self.setTag(R.id.user_head_icon_tag, url)
                return false
            }
        })
        .into(this)
}

@BindingAdapter("loadSquareMedia")
fun ImageView.binding_loadSquareMedia(illust: Illust?) {
    val url = illust?.image_urls?.square_medium ?: return
    scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(GlideUrlChild(url))
        .placeholder(R.drawable.image_place_holder_r2)
        .into(this)
}

@BindingAdapter("loadMedia")
fun ImageView.binding_loadMedia(displayUrl: String?) {
    val url = displayUrl ?: return
    scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(GlideUrlChild(url))
        .placeholder(R.drawable.image_place_holder)
        .into(this)
}

@BindingAdapter("loadBlurredMedia")
fun ImageView.binding_loadBlurredMedia(displayUrl: String?) {
    val url = displayUrl ?: return
    scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(GlideUrlChild(url))
        .placeholder(R.drawable.image_place_holder)
        .apply(bitmapTransform(BlurTransformation(25, 3)))
        .transition(withCrossFade())
        .into(this)
}
