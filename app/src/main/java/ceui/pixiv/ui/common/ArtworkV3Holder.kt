package ceui.pixiv.ui.common

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.SectionV3ArtistBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.ProgressTextButton
import ceui.loxia.User
import ceui.loxia.findFragmentOrNull
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

/**
 * V3 风格的区域标题标签：大写、12sp、v3_text_3 色、letterSpacing 0.12。
 * 用于替代旧式 RedSectionHeaderHolder，视觉对齐 V3 详情页的 section header。
 */
class ArtworkV3Holder(
    val liveUser: LiveData<User?>,
) : ListItemHolder()

@ItemHolder(ArtworkV3Holder::class)
class ArtworkV3ViewHolder(private val b: SectionV3ArtistBinding) :
    ListItemViewHolder<SectionV3ArtistBinding, ArtworkV3Holder>(b) {

    private val ctx: Context get() = b.root.context
    private val palette: V3Palette = V3Palette.from(Shaft.getContext())

    override fun onBindViewHolder(holder: ArtworkV3Holder, position: Int) {
        super.onBindViewHolder(holder, position)
        holder.liveUser.observe(lifecycleOwner) { user ->
            bind(user)
        }

    }

    fun bind(user: User?) {
        if (user == null) {
            return
        }

        b.artistName.text = user.name
        b.artistHandle.text = "@${user.account ?: ""}"
        Glide.with(ctx).load(GlideUtil.getUrl(user.profile_image_urls?.medium))
            .error(R.drawable.no_profile)
            .into(b.artistAvatar)

        b.artistCard.setOnClickListener {
            val intent = Intent(ctx, UActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id.toInt())
            ctx.startActivity(intent)
        }
        applyTouchScale(b.artistCard)

        bindFollowState(user)
        b.artistBio.isVisible = !user.comment.isNullOrBlank()
        if (b.artistBio.isVisible) b.artistBio.text = user.comment
    }

    private fun bindFollowState(user: User) {
        if (user.is_followed == true) {
            b.followBtn.text = ctx.getString(R.string.unfollow)
            palette.applyUnfollowBtn(b.followBtn)
            b.followBtn.setOnClick {
                val fragment = it.findFragmentOrNull<Fragment>() ?: return@setOnClick
                fragment.unfollowUser(it as ProgressTextButton, user.id.toInt())
            }
        } else {
            b.followBtn.text = ctx.getString(R.string.follow)
            palette.applyFollowBtn(b.followBtn)
            b.followBtn.setTextColor(Color.WHITE)
            b.followBtn.setOnClick {
                val fragment = it.findFragmentOrNull<Fragment>() ?: return@setOnClick
                fragment.followUser(
                    it as ProgressTextButton,
                    user.id.toInt(),
                    Params.TYPE_PUBLIC
                )
            }
            b.followBtn.setOnLongClickListener {
                val fragment =
                    it.findFragmentOrNull<Fragment>() ?: return@setOnLongClickListener false
                fragment.followUser(b.followBtn, user.id.toInt(), Params.TYPE_PRIVATE); true
            }
        }
    }

    private fun applyTouchScale(view: View, scale: Float = 0.97f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(200)
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f)
                    .scaleY(1f).setDuration(200).start()
            }
            false
        }
    }
}
