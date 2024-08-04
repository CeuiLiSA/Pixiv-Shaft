package ceui.pixiv.ui.works

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.databinding.FragmentFancyIllustBinding
import ceui.lisa.fragments.ImageFileViewModel
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.github.panpf.sketch.loadImage
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import me.jessyan.progressmanager.ProgressListener
import me.jessyan.progressmanager.ProgressManager
import me.jessyan.progressmanager.body.ProgressInfo

class IllustFragment : PixivFragment(R.layout.fragment_fancy_illust) {

    private val binding by viewBinding(FragmentFancyIllustBinding::bind)
    private val args by navArgs<IllustFragmentArgs>()
    private val viewModel by viewModels<ImageFileViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpToolbar(binding.toolbarLayout)
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
        viewModel.fileLiveData.observe(viewLifecycleOwner) { file ->
            if (viewModel.progressLiveData.value != 100) {
                viewModel.progressLiveData.value = 100
            }
            binding.image.loadImage(file){ }
        }
        binding.progressCircular.max = 100
        viewModel.progressLiveData.observe(viewLifecycleOwner) { percent ->
            if (percent == -1 || percent == 100) {
                binding.progressCircular.isVisible = false
            } else {
                binding.progressCircular.isVisible = true
                binding.progressCircular.progress = percent
            }
            Common.showLog("dsaasddsasa ${percent}")
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

    private fun prepareOriginalImage(url: String?) {
        if (url.isNullOrEmpty()) {
            return
        }

        val frag = this

        ProgressManager.getInstance()
            .addResponseListener(url, object : ProgressListener {
                override fun onProgress(progressInfo: ProgressInfo) {
                    viewModel.progressLiveData.value = progressInfo.percent
                    if (progressInfo.isFinish) {
                        ProgressManager.getInstance().removeResponseListener(
                            url,
                            this
                        )
                    }
                }

                override fun onError(id: Long, e: Exception) {
                    viewModel.progressLiveData.value = -1
                }
            })

        launchSuspend {
            withContext(Dispatchers.IO) {
                try {
                    val file = Glide.with(frag)
                        .asFile()
                        .load(GlideUrlChild(url))
                        .submit()
                        .get()
                    viewModel.isHighQualityImageLoaded = true
                    viewModel.fileLiveData.postValue(file)
                } catch (ex: Exception) {
                    viewModel.progressLiveData.value = -1
                    throw ex
                }
            }
        }
    }
}