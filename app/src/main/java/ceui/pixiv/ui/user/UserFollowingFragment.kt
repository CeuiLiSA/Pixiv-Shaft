package ceui.pixiv.ui.user

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.User
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

class UserFollowingFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<UserFollowingFragmentArgs>()
    private val viewModel by pixivListViewModel {
        DataSource(
            dataFetcher = { Client.appApi.getFollowingUsers(args.userId, args.restrictType) },
            itemMapper = { preview -> preview.illusts.map { IllustCardHolder(it) } }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpStaggerLayout(binding, viewModel)
    }
}

const val NO_PROFILE_IMG = "https://s.pximg.net/common/images/no_profile.png"

@BindingAdapter("userIcon")
fun ImageView.binding_loadUserIcon(user: User?) {
    val url = user?.profile_image_urls?.findMaxSizeUrl() ?: return

    val self = this

    val existing = self.getTag(R.id.user_head_icon_tag) as? String
    if (existing == url) {
        return
    }

    scaleType = ImageView.ScaleType.CENTER_CROP
    if (url == NO_PROFILE_IMG) {
        Glide.with(this)
            .load(R.drawable.icon_user_mask)
            .into(this)
    } else {
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

fun TextView.setTextOrGone(content: String?) {
    if (content?.isNotEmpty() == true) {
        isVisible = true
        text = content
    } else {
        isVisible = false
    }
}