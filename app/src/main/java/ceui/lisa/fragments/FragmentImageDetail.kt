package ceui.lisa.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.ImageTranslationViewModel
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentImageDetailBinding
import java.io.File
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import com.github.panpf.zoomimage.util.OffsetCompat
import com.github.panpf.zoomimage.zoom.GestureType
import com.github.panpf.zoomimage.zoom.ReadMode

class FragmentImageDetail : BaseFragment<FragmentImageDetailBinding?>() {
    private var index = 0
    private var url: String? = null
    private var saveName: String? = null
    private val viewModel by viewModels<ToggleToolnarViewModel>(ownerProducer = { requireActivity() })
    private val translationViewModel by viewModels<ImageTranslationViewModel>(ownerProducer = { requireActivity() })

    // 不再放进 arguments / savedInstanceState，避免每个 Fragment 重复持久化 80KB IllustsBean
    // 导致 TransactionTooLargeException。统一向 ImageDetailActivity 取。
    private val mIllustsBean: IllustsBean?
        get() = (activity as? ImageDetailActivity)?.mIllustsBean

    /**
     * 自定义双击持续放大逻辑
     *
     * 作者：wangwang-code
     *
     * 核心痛点：ZoomImage原有的双击缩放功能一次缩放过大，导致需要手动双指缩小
     * 具体案例：图中多处区域有大量文字，ZoomImage原有的双击放大功能，双击后图片放大倍数过大，导致需要手动双指缩小或者拼命拖拽来找文字。
     * 解决办法：
     * 1. 禁用 ZoomImage 双击放大功能
     * 2. 自定义双击放大逻辑，双击图片后用scaleBy() 方法来以乘法的方式增量缩放图像到指定的倍数
     * 3. 图片放大后，长按屏幕可恢复至原始大小
     * 备注：
     * 原本想法是参照ZoomImage的双击放大逻辑中的循环放大，但考虑到需要多次双击才能回到原始大小，因此改为长按屏幕恢复至原始大小。
     * 该方法仅消费双击、单击事件，处理长按事件，其余事件（滑动拖拽、双指放大）需要交回 ZoomImage 处理
     * 待实现：
     * 1. 设置页面，提供切换默认（ZoomImage的双击放大）和双击持续放大模式
     * 2. 设置页面，允许用户自定义双击持续放大模式的addScale
     * 3. 设置页面，允许用户自定义 选择 双击持续放大模式 的 双击次数上限 list，需向用户注明即使未达到次数也可能无法继续放大（被最大倍数限制），例如：默认（无限制）, 2次, 3次, ...
     * 4. 显示当前放大倍数
     * 5. 做无干扰的提醒，双击放大后，提醒“可长按复原”
     * 6. “已至最大，长按复原”Toast存在延迟，需要换更优雅、更及时的提醒
     */
    private val gestureDetector by lazy {
        GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val touchPoint = OffsetCompat(e.x, e.y)
                val zoomable = baseBind.image.zoomable
                val contentPoint = zoomable.touchPointToContentPointF(touchPoint)

                //val currentScale = zoomable.transformState.value.scaleX
                val maxScale = zoomable.maxScaleState.value
                //val minScale = zoomable.minScaleState.value
                // 判断当前是否已经放大到最大倍数，若是则缩小至最小倍数；否则放大继续放大。
                /*if (currentScale >= maxScale - 0.01f) {
                    // 取消图片沉浸式
                    if (viewModel.isFullscreenMode.value == true) {
                        viewModel.toggleFullscreen()
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        zoomable.scale(
                            targetScale = minScale,
                            centroidContentPointF = contentPoint,
                            animated = true
                        )
                    }
                } else {*/
                    // 图片沉浸式
                    if (viewModel.isFullscreenMode.value == false) {
                        viewModel.toggleFullscreen()
                    }
                    // 放大倍数（相对）
                    val addScale = 1.8f

                    viewLifecycleOwner.lifecycleScope.launch {
                        // 1. 先执行放大操作
                        zoomable.scaleBy(
                            addScale = addScale,
                            centroidContentPointF = contentPoint,
                            animated = true
                        )
                        // 2. 在协程内、操作完成后，获取最新的缩放值
                        val afterScale = zoomable.transformState.value.scaleX

                        // 3. 浮点数防误差判断：若当前大小已经接近或等于最大放大倍数
                        if (afterScale >= maxScale - 0.01f) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "已至最大，长按复原",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                //}
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                val touchPoint = OffsetCompat(e.x, e.y)
                val zoomable = baseBind.image.zoomable
                val minScale = zoomable.minScaleState.value
                val contentPoint = zoomable.touchPointToContentPointF(touchPoint)
                // 取消图片沉浸式
                if (viewModel.isFullscreenMode.value == true) {
                    viewModel.toggleFullscreen()
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    zoomable.scale(
                        targetScale = minScale,
                        centroidContentPointF = contentPoint,
                        animated = true
                    )
                }
            }
            /*处理原有点击事件，单击屏幕切换沉浸式模式，如果正在缩放动画中则不切换沉浸式模式，避免打断动画。
            * */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                viewModel.toggleFullscreen()
                return true
            }
        })
    }

    public override fun initBundle(bundle: Bundle) {
        url = bundle.getString(Params.URL)
        index = bundle.getInt(Params.INDEX)
        saveName = bundle.getString(Params.TITLE)
    }

    public override fun initLayout() {
        mLayoutID = R.layout.fragment_image_detail
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun initView() {
        baseBind.emptyActionButton.setOnClickListener { v: View? -> loadImage() }
        //插画二级详情保持屏幕常亮
        if (Shaft.sSettings.isIllustDetailKeepScreenOn) {
            baseBind.root.keepScreenOn = true
        }
        //交给gestureDetector处理
        /*
        baseBind.image.onViewTapListener = OnViewTapListener { _, _ ->
            viewModel.toggleFullscreen()
        }*/
        // 长图阅读模式：自动填满宽度、��顶部开始，无需手动双击放大再滑动
        baseBind.image.zoomable.setReadMode(ReadMode.Default)
        // 禁用ZoomImage的默认双击缩放
        baseBind.image.zoomable.setDisabledGestureTypes(
            baseBind.image.zoomable.disabledGestureTypesState.value or GestureType.DOUBLE_TAP_SCALE
        )
        //监听触摸事件，视情况拦截事件避免打断动画
        baseBind.image.setOnTouchListener { v, event ->
            // 只在单指时拦截触摸事件给gestureDetector，其他情况交回给ZoomImage处理
            // 在实现自有双击持续放大逻辑下，尽力减少对ZoomImage手势的拦截。
            // 但双指缩放过程中总有可能发生中心点“抖动”（疑似原本就存在）
            if (event.pointerCount == 1) {
                val handled = gestureDetector.onTouchEvent(event)
                handled
            } else {
                v.onTouchEvent(event)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadImage()
        // 监听"翻译漫画"产出:VM 里出现本页 index 的译图就直接换图
        translationViewModel.translatedPaths.observe(viewLifecycleOwner) { map ->
            val path = map[index] ?: return@observe
            val f = File(path)
            if (f.exists()) {
                baseBind.image.loadImage(f)
            }
        }
    }

    private fun loadImage() {
        baseBind.emptyFrame.visibility = View.GONE
        val isUrlMode = mIllustsBean == null && !TextUtils.isEmpty(url)
        val imageUrl: String? = if (isUrlMode) {
            url
        } else {
            IllustDownload.getUrl(mIllustsBean, index, Params.IMAGE_RESOLUTION_ORIGINAL)
        }

        val shortUrl = imageUrl?.substringAfterLast('/') ?: "null"
        Timber.d("[ImageDetail] loadImage index=$index, isUrlMode=$isUrlMode, url=$shortUrl")

        if (imageUrl?.isNotEmpty() == true) {
            // content:// URI（来自下载完成页的 SAF 路径）直接用 Sketch 加载，
            // 不走 TaskPool/Glide，因为 Glide 没有 SAF URI 的访问权限。
            if (imageUrl.startsWith("content://")) {
                baseBind.image.loadImage(Uri.parse(imageUrl))
                return
            }

            val task = TaskPool.getLoadTask(NamedUrl("", imageUrl))
            Timber.d("[ImageDetail] task acquired. taskId=${task.taskId}, status=${task.status.value}, hasResult=${task.result.value != null}, url=$shortUrl")

            // 原图尚未加载完时，若一级详情页的大图已在 Glide 缓存，先用大图占位
            if (mIllustsBean != null && task.result.value == null) {
                val largeUrl = IllustDownload.getUrl(
                    mIllustsBean, index, Params.IMAGE_RESOLUTION_LARGE
                )
                if (!largeUrl.isNullOrEmpty() && largeUrl != imageUrl) {
                    val largeFile = TaskPool.peekCachedFile(largeUrl)
                    if (largeFile != null) {
                        Timber.d("[ImageDetail] placeholder HIT path=${largeFile.absolutePath} size=${largeFile.length()}")
                        baseBind.image.loadImage(largeFile)
                    } else {
                        Timber.d("[ImageDetail] placeholder MISS largeUrl=${largeUrl.substringAfterLast('/')}")
                    }
                }
            }

            task.result.observe(viewLifecycleOwner) { file ->
                Timber.d("[ImageDetail] result callback. file=${file?.absolutePath}, exists=${file?.exists()}, size=${file?.length() ?: -1}, url=$shortUrl")
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
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                val imageId = getImageIdInGallery(ctx, displayName)
                                if (imageId != null) {
                                    deleteImageById(ctx, imageId)
                                }
                                saveImageToGallery(ctx, file, displayName)
                            }
                        }
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
