package ceui.pixiv.ui.works

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.databinding.FragmentFancyIllustBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide

class IllustFragment : PixivFragment(R.layout.fragment_fancy_illust) {

    private val binding by viewBinding(FragmentFancyIllustBinding::bind)
    private val args by navArgs<IllustFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val liveIllust = ObjectPool.get<Illust>(args.illustId)
        liveIllust.observe(viewLifecycleOwner) { illust ->
            Glide.with(this).load(GlideUrlChild(illust.image_urls?.large)).into(binding.image)
            Glide.with(this).load(GlideUrlChild(illust.user?.profile_image_urls?.findMaxSizeUrl())).into(binding.userIcon)
            binding.userName.text = illust.user?.name

            binding.userLayout.setOnClick {
                illust.user?.id?.let {
                    pushFragment(R.id.navigation_user_profile, UserProfileFragmentArgs(it).toBundle())
                }
            }
        }

        liveIllust.value?.user?.let { u ->
            ObjectPool.get<User>(u.id).observe(viewLifecycleOwner) { user ->
                binding.follow.isVisible = user.is_followed != true
                binding.unfollow.isVisible = user.is_followed == true
            }

            binding.follow.setOnClick {
                followUser(it, u.id.toInt(), Params.TYPE_PUBLIC)
            }
            binding.unfollow.setOnClick {
                unfollowUser(it, u.id.toInt())
            }

            binding.comment.setOnClick {
                pushFragment(R.id.navigation_illust_comments, CommentsFragmentArgs(args.illustId, illustArthurId = u.id).toBundle())
            }
        }


    }
}