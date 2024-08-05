package ceui.pixiv.ui.works

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.databinding.FragmentFancyIllustBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.pushFragment
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.ImgDisplayFragment
import ceui.pixiv.ui.common.getFileSize
import ceui.pixiv.ui.common.getImageDimensions
import ceui.pixiv.ui.common.isImageInGallery
import ceui.pixiv.ui.common.saveImageToGallery
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.github.panpf.sketch.loadImage

class IllustFragment : ImgDisplayFragment(R.layout.fragment_fancy_illust) {

    private val binding by viewBinding(FragmentFancyIllustBinding::bind)
    private val args by navArgs<IllustFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpFullScreen(
            listOf(
                binding.toolbarLayout.root,
                binding.userLayout,
                binding.buttonLayout
            ),
            binding.image,
            binding.toolbarLayout
        )
        setUpProgressBar(binding.progressCircular)
        val context = requireContext()
        val displayName = "pixiv_works_${args.illustId}.png"
        viewModel.fileLiveData.observe(viewLifecycleOwner) { file ->
            binding.image.loadImage(file)
            binding.download.setOnClick {
                saveImageToGallery(context, file, displayName)
            }
        }

        val liveIllust = ObjectPool.get<Illust>(args.illustId)
        liveIllust.observe(viewLifecycleOwner) { illust ->
            binding.toolbarLayout.naviTitle.text = illust.title
            Glide.with(this).load(GlideUrlChild(illust.user?.profile_image_urls?.findMaxSizeUrl()))
                .into(binding.userIcon)
            if (!viewModel.isHighQualityImageLoaded) {
                Glide.with(this).load(GlideUrlChild(illust.image_urls?.large)).into(binding.image)
            }

            val url = if (illust.page_count == 1) {
                illust.meta_single_page?.original_image_url
            } else {
                illust.meta_pages?.getOrNull(0)?.image_urls?.original
            }
            Common.showLog("sadasd2 aa ${url}")
            prepareOriginalImage(url)

            binding.userName.text = illust.user?.name

            binding.userLayout.setOnClick {
                illust.user?.id?.let {
                    pushFragment(
                        R.id.navigation_user_profile,
                        UserProfileFragmentArgs(it).toBundle()
                    )
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
                pushFragment(
                    R.id.navigation_illust_comments,
                    CommentsFragmentArgs(args.illustId, illustArthurId = u.id).toBundle()
                )
            }
        }
    }
}