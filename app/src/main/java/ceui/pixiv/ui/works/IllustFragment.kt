package ceui.pixiv.ui.works

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentFancyIllustBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.pushFragment
import ceui.pixiv.PixivFragment
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.google.android.material.transition.platform.MaterialSharedAxis

class IllustFragment : PixivFragment(R.layout.fragment_fancy_illust) {

    private val binding by viewBinding(FragmentFancyIllustBinding::bind)
    private val args by navArgs<IllustFragmentArgs>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ObjectPool.get<Illust>(args.illustId).observe(viewLifecycleOwner) { illust ->
            Glide.with(this).load(GlideUrlChild(illust.image_urls?.large)).into(binding.image)
            Glide.with(this).load(GlideUrlChild(illust.user?.profile_image_urls?.findMaxSizeUrl())).into(binding.userIcon)
            binding.userName.text = illust.user?.name

            binding.userLayout.setOnClick {
                illust.user?.id?.let {
                    pushFragment(R.id.navigation_user_profile, UserProfileFragmentArgs(it).toBundle())
                }
            }
        }
    }
}