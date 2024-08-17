package ceui.pixiv.ui.works

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.databinding.FragmentFancyIllustBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.DateParse
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.User
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ImgDisplayFragment
import ceui.pixiv.ui.common.setUpFullScreen
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.pixiv.ui.user.setTextOrGone
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.github.panpf.zoomimage.SketchZoomImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch

class IllustFragment : ImgDisplayFragment(R.layout.fragment_fancy_illust), GalleryActionReceiver {

    private val binding by viewBinding(FragmentFancyIllustBinding::bind)
    private val args by navArgs<IllustFragmentArgs>()

    override val downloadButton: View
        get() = binding.download
    override val progressCircular: CircularProgressIndicator
        get() = binding.progressCircular
    override val displayImg: SketchZoomImageView
        get() = binding.image

    private val liveIllust by lazy { ObjectPool.get<Illust>(args.illustId) }

    override fun displayName(): String {
        return buildPixivWorksFileName(args.illustId)
    }

    override fun contentUrl(): String {
        return liveIllust.value?.meta_single_page?.original_image_url ?: ""
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpFullScreen(
            viewModel,
            listOf(
                binding.toolbarLayout.root,
                binding.infoLayout,
                binding.buttonLayout,
                binding.topShadow,
            ),
            binding.toolbarLayout
        )
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.illust = liveIllust
        liveIllust.observe(viewLifecycleOwner) { illust ->
            binding.toolbarLayout.naviTitle.text = illust.title

            binding.userLayout.setOnClick {
                illust.user?.id?.let {
                    pushFragment(
                        R.id.navigation_user_profile,
                        UserProfileFragmentArgs(it).toBundle()
                    )
                }
            }

            if (illust.is_bookmarked == true) {
                binding.bookmark.setImageResource(R.drawable.icon_liked)
                binding.bookmark.setOnClick {
                    launchSuspend(it) {
                        Client.appApi.removeBookmark(args.illustId)
                        ObjectPool.update(
                            illust.copy(
                                is_bookmarked = false,
                                total_bookmarks = illust.total_bookmarks?.minus(1)
                            )
                        )
                    }
                }
            } else {
                binding.bookmark.setImageResource(R.drawable.icon_not_liked)
                binding.bookmark.setOnClick {
                    launchSuspend(it) {
                        Client.appApi.postBookmark(args.illustId)
                        ObjectPool.update(
                            illust.copy(
                                is_bookmarked = true,
                                total_bookmarks = illust.total_bookmarks?.plus(1)
                            )
                        )
                    }
                }
            }

            if (illust.page_count == 1) {
                renderSingleImageIllust(illust)
            } else if (illust.page_count > 1) {
                renderGalleryIllust(illust, adapter)
            }
            if (illust.caption?.isNotEmpty() == true) {
                binding.description.isVisible = true
                binding.description.text = illust.caption
            } else {
                binding.description.isVisible = false
            }
            binding.dateTime.setTextOrGone(DateParse.displayCreateDate(illust.create_date))
            binding.visitCount.setTextOrGone("展示 " + illust.total_view)
            binding.bookmarkCount.setTextOrGone("收藏 " + illust.total_bookmarks)
        }

        liveIllust.value?.user?.let { u ->
            val liveUser = ObjectPool.get<User>(u.id)
            binding.user = liveUser
            binding.follow.setOnClick {
                followUser(it, u.id.toInt(), Params.TYPE_PUBLIC)
            }
            binding.unfollow.setOnClick {
                unfollowUser(it, u.id.toInt())
            }

            binding.comment.setOnClick {
                pushFragment(
                    R.id.navigation_comments_illust,
                    CommentsFragmentArgs(args.illustId, objectArthurId = u.id, objectType = ObjectType.ILLUST).toBundle()
                )
            }
        }
    }

    private fun renderSingleImageIllust(illust: Illust) {
        Glide.with(this).load(GlideUrlChild(illust.image_urls?.large)).into(binding.image)
        binding.image.isVisible = true
        binding.galleryList.isVisible = false
        binding.download.isVisible = true
    }

    private fun renderGalleryIllust(illust: Illust, adapter: CommonAdapter) {
        binding.image.isVisible = false
        binding.download.isVisible = false
        binding.progressCircular.isVisible = false
        binding.galleryList.isVisible = true
        binding.galleryList.adapter = adapter
        binding.galleryList.layoutManager = LinearLayoutManager(requireContext())
        adapter.submitList(getGalleryHolders(illust, requireActivity()))
    }

    private fun getGalleryHolders(illust: Illust, activity: FragmentActivity): List<GalleryHolder>? {
        return illust.meta_pages?.mapIndexed { index, metaPage ->
            val task = TaskPool.getLoadTask(
                NamedUrl(
                    buildPixivWorksFileName(illust.id, index),
                    metaPage.image_urls?.original ?: ""
                ),
                activity
            )
            GalleryHolder(illust, index, task) {
                activity.lifecycleScope.launch {
                    task.execute()
                }
            }
        }
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