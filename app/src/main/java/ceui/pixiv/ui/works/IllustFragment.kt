package ceui.pixiv.ui.works

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.map
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
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
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ImgDisplayFragment
import ceui.pixiv.ui.common.ImgUrlFragmentArgs
import ceui.pixiv.ui.common.getFileSize
import ceui.pixiv.ui.common.getImageDimensions
import ceui.pixiv.ui.common.saveImageToGallery
import ceui.pixiv.ui.common.setUpFullScreen
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskStatus
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.SketchZoomImageView
import com.google.android.material.progressindicator.CircularProgressIndicator

class IllustFragment : ImgDisplayFragment(R.layout.fragment_fancy_illust), GalleryActionReceiver {

    private val binding by viewBinding(FragmentFancyIllustBinding::bind)
    private val args by navArgs<IllustFragmentArgs>()
    private val illustViewModel by viewModels<IllustViewModel>()

    override val downloadButton: View
        get() = binding.download
    override val progressCircular: CircularProgressIndicator
        get() = binding.progressCircular
    override val displayImg: SketchZoomImageView
        get() = binding.image

    override fun displayName(): String {
        return buildPixivWorksFileName(args.illustId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpFullScreen(
            viewModel,
            listOf(
                binding.toolbarLayout.root,
                binding.userLayout,
                binding.buttonLayout,
                binding.topShadow,
                binding.bottomShadow
            ),
            binding.toolbarLayout
        )
        val liveIllust = ObjectPool.get<Illust>(args.illustId)
        val adapter = CommonAdapter(viewLifecycleOwner)
        val context = requireContext()
        liveIllust.observe(viewLifecycleOwner) { illust ->
            binding.toolbarLayout.naviTitle.text = illust.title
            Glide.with(this).load(GlideUrlChild(illust.user?.profile_image_urls?.findMaxSizeUrl()))
                .into(binding.userIcon)
            binding.userName.text = illust.user?.name

            binding.userLayout.setOnClick {
                illust.user?.id?.let {
                    pushFragment(
                        R.id.navigation_user_profile,
                        UserProfileFragmentArgs(it).toBundle()
                    )
                }
            }

            if (illust.page_count == 1) {
                renderSingleImageIllust(illust, context)
            } else if (illust.page_count > 1) {
                renderGalleryIllust(illust, context, adapter)
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

    private fun renderSingleImageIllust(illust: Illust, context: Context) {
        Glide.with(this).load(GlideUrlChild(illust.image_urls?.large)).into(binding.image)
        binding.image.isVisible = true
        binding.galleryList.isVisible = false
        val url = illust.meta_single_page?.original_image_url ?: return
        val task = viewModel.loadNamedUrl(NamedUrl(displayName(), url), context)
        setUpLoadTask(context, task)
    }

    private fun renderGalleryIllust(illust: Illust, context: Context, adapter: CommonAdapter) {
        binding.image.isVisible = false
        binding.progressCircular.isVisible = false
        binding.galleryList.isVisible = true
        binding.galleryList.adapter = adapter
        binding.galleryList.layoutManager = LinearLayoutManager(requireContext())
        adapter.submitList(illustViewModel.getGalleryHolders(illust, context))
    }

    override fun onClickGalleryHolder(index: Int, galleryHolder: GalleryHolder) {
        pushFragment(
            R.id.navigation_paged_img_urls,
            PagedImgUrlFragmentArgs(
                args.illustId,
                index
            ).toBundle()
        )
    }
}