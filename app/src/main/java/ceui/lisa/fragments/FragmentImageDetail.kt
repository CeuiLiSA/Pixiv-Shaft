package ceui.lisa.fragments

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentImageDetailBinding
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.pixiv.ui.common.deleteImageById
import ceui.pixiv.ui.common.getImageIdInGallery
import ceui.pixiv.ui.common.saveImageToGallery
import ceui.pixiv.ui.common.setUpWithTaskStatus
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool
import ceui.pixiv.ui.works.ToggleToolnarViewModel
import ceui.pixiv.utils.setOnClick
import com.github.panpf.sketch.loadImage
import timber.log.Timber

class FragmentImageDetail : BaseFragment<FragmentImageDetailBinding?>() {
    private var index = 0
    private var url: String? = null
    private var saveName: String? = null
    private val viewModel by viewModels<ToggleToolnarViewModel>(ownerProducer = { requireActivity() })

    // 不再放进 arguments / savedInstanceState，避免每个 Fragment 重复持久化 80KB IllustsBean
    // 导致 TransactionTooLargeException。统一向 ImageDetailActivity 取。
    private val mIllustsBean: IllustsBean?
        get() = (activity as? ImageDetailActivity)?.mIllustsBean

    public override fun initBundle(bundle: Bundle) {
        url = bundle.getString(Params.URL)
        index = bundle.getInt(Params.INDEX)
        saveName = bundle.getString(Params.TITLE)
    }

    public override fun initLayout() {
        mLayoutID = R.layout.fragment_image_detail
    }

    override fun initView() {
        baseBind.emptyActionButton.setOnClickListener { v: View? -> loadImage() }
        //插画二级详情保持屏幕常亮
        if (Shaft.sSettings.isIllustDetailKeepScreenOn) {
            baseBind.root.keepScreenOn = true
        }
        baseBind.image.setOnClick {
            viewModel.toggleFullscreen()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadImage()
    }

    private fun loadImage() {
        baseBind.emptyFrame.visibility = View.GONE
        val isUrlMode = mIllustsBean == null && !TextUtils.isEmpty(url)
        val imageUrl: String? = if (isUrlMode) {
            url
        } else {
            IllustDownload.getUrl(mIllustsBean, index, Params.IMAGE_RESOLUTION_ORIGINAL)
        }

        if (imageUrl?.isNotEmpty() == true) {
            val task = TaskPool.getLoadTask(NamedUrl("", imageUrl))
            Timber.d("二级详情页 loadImage: taskId=${task.taskId}, status=${task.status.value}, url=$imageUrl")

            // 原图尚未加载完时，若一级详情页的大图已在 Glide 缓存，先用大图占位
            if (mIllustsBean != null && task.result.value == null) {
                val largeUrl = IllustDownload.getUrl(
                    mIllustsBean, index, Params.IMAGE_RESOLUTION_LARGE
                )
                if (!largeUrl.isNullOrEmpty() && largeUrl != imageUrl) {
                    val largeFile = TaskPool.peekCachedFile(largeUrl)
                    if (largeFile != null) {
                        Timber.d("二级详情页 占位大图 HIT path=${largeFile.absolutePath} size=${largeFile.length()}")
                        baseBind.image.loadImage(largeFile)
                    } else {
                        Timber.d("二级详情页 占位大图 MISS largeUrl=$largeUrl")
                    }
                }
            }

            task.result.observe(viewLifecycleOwner) { file ->
                baseBind.image.loadImage(file)
                if (isUrlMode) {
                    baseBind.downloadButton.visibility = View.VISIBLE
                    baseBind.downloadButton.setOnClick {
                        val ext = imageUrl.substringAfterLast('.', "jpg")
                        val displayName = if (!saveName.isNullOrEmpty()) {
                            "$saveName.$ext"
                        } else {
                            imageUrl.substringAfterLast('/')
                        }
                        val ctx = requireActivity()
                        val imageId = getImageIdInGallery(ctx, displayName)
                        if (imageId != null) {
                            deleteImageById(ctx, imageId)
                        }
                        saveImageToGallery(ctx, file, displayName)
                    }
                }
            }
            baseBind.progressCircular.setUpWithTaskStatus(task.status, viewLifecycleOwner)
        }
    }

    companion object {
        // IllustsBean 由 ImageDetailActivity 持有，Fragment 运行时读取，避免放进 Bundle
        @JvmStatic
        fun newInstance(index: Int): FragmentImageDetail {
            val args = Bundle()
            args.putInt(Params.INDEX, index)
            val fragment = FragmentImageDetail()
            fragment.arguments = args
            return fragment
        }

        @JvmStatic
        @JvmOverloads
        fun newInstance(pUrl: String?, pSaveName: String? = null): FragmentImageDetail {
            val args = Bundle()
            args.putString(Params.URL, pUrl)
            if (pSaveName != null) {
                args.putString(Params.TITLE, pSaveName)
            }
            val fragment = FragmentImageDetail()
            fragment.arguments = args
            return fragment
        }
    }
}
