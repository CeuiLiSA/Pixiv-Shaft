package ceui.pixiv.ui.user

import android.graphics.drawable.Drawable
import android.widget.ImageView
import ceui.lisa.R
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import ceui.pixiv.ui.common.PreviewImageTransitionFactory
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

/**
 * 从已删的 UserFollowingFragment.kt 抽出的全局图片加载函数。
 * [binding_loadUserIcon] 被 CommentCardRenderer / ArtworkDetailAdapter 等直接调用,
 * [binding_loadSquareMedia] 给标签卡（置顶 / Prime / 热门搜索）的三格预览图用,[NO_PROFILE_IMG] 被 ImageHostManager 引用。
 */

const val NO_PROFILE_IMG = "https://s.pximg.net/common/images/no_profile.png"

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

fun ImageView.binding_loadSquareMedia(illust: Illust?, fadeOnLoad: Boolean = true, durationMs: Int? = null) {
    val url = illust?.image_urls?.square_medium?.takeIf { it.isNotBlank() }
    if (url == null) {
        // 标签卡固定保留三个预览槽；空槽必须清掉 RecyclerView 复用留下的上一张图，
        // 让 XML 里的主题占位背景真正可见。
        Glide.with(this).clear(this)
        setImageDrawable(null)
        return
    }
    scaleType = ImageView.ScaleType.CENTER_CROP
    val request = Glide.with(this)
        .load(GlideUrlChild(url))
        .placeholder(R.drawable.bg_tag_preview_placeholder)
        .error(R.drawable.bg_tag_preview_placeholder)
    val imageRequest = if (fadeOnLoad) {
        request.transition(
            DrawableTransitionOptions().transition(PreviewImageTransitionFactory(context, durationMs))
        )
    } else {
        request
    }
    imageRequest.into(this)
}
