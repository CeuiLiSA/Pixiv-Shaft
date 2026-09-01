package ceui.lisa.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.ImageTranslationViewModel
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentImageDetailBinding
import ceui.lisa.download.FileCreator
import ceui.lisa.download.IllustDownload
import ceui.pixiv.api.model.Illust
import ceui.pixiv.services.appServices
import ceui.lisa.utils.Params
import ceui.lisa.utils.Settings
import ceui.lisa.view.DragDismissLayout
import ceui.pixiv.download.RecordedPageProbe
import ceui.pixiv.imageloader.Disposable
import ceui.pixiv.imageloader.ImageLoadState
import ceui.pixiv.imageloader.ImageLoaderV3
import ceui.pixiv.imageloader.observeState
import ceui.pixiv.ui.common.deleteImageById
import ceui.pixiv.ui.common.getImageIdInGallery
import ceui.pixiv.ui.common.saveImageToGallery
import ceui.pixiv.ui.translate.MangaOcrModel
import ceui.pixiv.ui.works.ToggleToolnarViewModel
import ceui.pixiv.utils.setOnClick
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.util.IntSizeCompat
import com.github.panpf.zoomimage.util.OffsetCompat
import com.github.panpf.zoomimage.util.isNotEmpty
import com.github.panpf.zoomimage.view.zoom.OnViewLongPressListener
import com.github.panpf.zoomimage.view.zoom.OnViewTapListener
import com.github.panpf.zoomimage.zoom.GestureType
import com.github.panpf.zoomimage.zoom.ReadMode
import com.github.panpf.zoomimage.zoom.ScalesCalculator
import com.hjq.toast.Toaster
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class FragmentImageDetail : BaseFragment<FragmentImageDetailBinding?>() {
    private var index = 0
    private var url: String? = null
    private var saveName: String? = null
    private val viewModel by viewModels<ToggleToolnarViewModel>(ownerProducer = { requireActivity() })
    private val translationViewModel by viewModels<ImageTranslationViewModel>(ownerProducer = { requireActivity() })
    private var isAnimated: Boolean = false
    private var isScaleMax: Boolean = false

    /**
     * 上一次 ZoomImage 的 contentSize，用来判断「底图是不是换成了另一个尺寸」。
     *
     * 增量模式用 [isScaleMax] 记忆「已到最大」；baseScale 由 contentSize 决定：large 占位
     * （长边约 1200px）换成原图（数千 px）后，同一块可见区域对应的绝对倍率会整体缩小好几倍，
     * 这个记忆值当场失真。所以 contentSize 一变就清记忆，避免下一次双击误判成「该缩回去」。
     *
     * 只在「真的换了尺寸」时清：[BaseFragment] 复用 rootView 时 StateFlow 会重放同一个值，那时画面上的
     * 缩放和记忆仍然对得上，不能清（理由见 [onDestroyView] 的注释）。
     */
    private var lastZoomContentSize: IntSizeCompat = IntSizeCompat.Zero
    private var imageDisposable: Disposable? = null
    // large 占位任务的观察句柄：原图就绪后摘掉，避免晚到的 large 回盖已显示的原图。
    private var largeDisposable: Disposable? = null
    // 原图是否已显示（网络成功 / 本地直读）。large 占位仅在其为 false 时才铺，兜住 large/原图竞态。
    private var originalShown: Boolean = false
    // 不再放进 arguments / savedInstanceState，避免每个 Fragment 重复持久化 80KB Illust
    // 导致 TransactionTooLargeException。统一向 ImageDetailActivity 取。
    private val mIllust: Illust?
        get() = (activity as? ImageDetailActivity)?.mIllust

    private val isSnapshotMode: Boolean
        get() = (activity as? ImageDetailActivity)?.isSnapshotMode == true

    /**
     * 延迟派发过来的手势回调，现在还能不能安全地碰 fragment 的东西。
     *
     * 单击/长按都不是同步回调：自家的 [gestureDetector] 与 ZoomImage 的
     * UnifiedGestureDetector 都靠 Handler 延后判定（onSingleTapConfirmed 要等完双击
     * 判定窗 ~300ms，onLongPress 500ms）。用户点完立刻翻页/返回，这条消息照样会投递到
     * 已经 detach 的 fragment 上 —— 此时 [viewModel] 的 lazy 首次取值走
     * `requireActivity()`，直接抛
     * `IllegalStateException: Fragment FragmentImageDetail{...} not attached to an activity.`
     * （线上 Crashlytics）。baseBind / viewLifecycleOwner 同样已经失效。
     *
     * 手势的语义本来就是「作用在当前这一页上」，页面没了直接丢掉是正确行为，无需补偿。
     *
     * 注意这只是「该不该执行」的判断；「执行了会不会崩」由 [onAttach] 里预先兑现 lazy 兜底，
     * 两者互不替代：漏了这个守卫最多是对着已离开的页面做一次无意义的 toggle，不会再崩。
     */
    private val isGestureTargetAlive: Boolean
        get() = isAdded && view != null

    /**
     * 在还 attach 着的时候把两个 activity 作用域的 VM lazy 兑现掉。
     *
     * [viewModel] / [translationViewModel] 的 ownerProducer 是 `requireActivity()`，只在 lazy
     * **首次**取值时执行一次；取到后 ViewModelLazy 缓存实例，之后再取不碰 attach 状态。崩溃的成因
     * 正是「首次取值发生在 detach 之后」—— [translationViewModel] 在 onViewCreated 就被取过所以从没崩，
     * [viewModel] 只被手势回调用到，首次取值就是那条延迟派发的消息本身。
     *
     * onAttach 时 host 已就绪，在这里取值把首次取值点钉死在生命周期内，之后无论什么延迟回调打进来
     * 都只是拿缓存实例，不会再抛 ISE。
     */
    override fun onAttach(context: Context) {
        super.onAttach(context)
        viewModel
        translationViewModel
    }

    /**
     * view 没了就把 [isAnimated] 这把动画闩复位。
     *
     * [isAnimated] 的语义是「此刻有一条缩放协程正在跑」：[gestureDetector] 的 onDoubleTap 同步置 true，
     * 复位只写在协程尾部，而中间的 `zoomable.scale(animated = true)` 是挂起等动画的。动画没跑完就翻页，
     * viewLifecycleOwner 的 scope 被取消，协程再也回不到那句复位 —— 闩就永久停在 true。
     *
     * 而 [gestureDetector] 与本字段都挂在 fragment 实例上，[BaseFragment] 又缓存 rootView 跨 view 复用，
     * 所以滑回这一页时旧闩还在：onDoubleTap 的 `if (isAnimated) return` 与 onLongPress 的 `if (!isAnimated)`
     * 双双短路，这一页的双击缩放和长按归位整场失效（仅「增量」双击缩放行为时这条路径生效）。
     *
     * scope 一取消就不可能再有协程把它置回 true，这里复位是安全的。
     * [isScaleMax] 是「当前这张图是否已到最大」的记忆，view 实例被复用、
     * 缩放状态也跟着留着，故意不动 —— 清了反而会和画面上真实的缩放对不上。
     */
    override fun onDestroyView() {
        isAnimated = false
        super.onDestroyView()
    }

    // 自定义双击手势（仅增量模式使用）：
    // PR#900 的 scaleBy 增量放大 + 长按归位。
    // 「默认」和「三级」都走库自带双击缩放，不经过这里。
    private val gestureDetector by lazy {
        GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isGestureTargetAlive) return true
                if (isAnimated) return true
                isAnimated = true
                val zoomable = baseBind.image.zoomable
                val contentPoint = zoomable.touchPointToContentPointF(OffsetCompat(e.x, e.y))
                viewLifecycleOwner.lifecycleScope.launch {
                    if (isScaleMax) {
                        if (viewModel.isFullscreenMode.value == true) {
                            viewModel.toggleFullscreen()
                        }
                        val minScale = zoomable.minScaleState.value
                        zoomable.scale(
                            targetScale = minScale,
                            centroidContentPointF = contentPoint,
                            animated = true
                        )
                        isScaleMax = false
                    } else {
                        if (viewModel.isFullscreenMode.value == false) {
                            viewModel.toggleFullscreen()
                        }
                        zoomable.scaleBy(
                            addScale = Shaft.sSettings.customZoomAddScale,
                            centroidContentPointF = contentPoint,
                            animated = true
                        )
                        val afterScale = zoomable.transformState.value.scaleX
                        val maxScale = zoomable.maxScaleState.value
                        if (afterScale >= maxScale - MAX_SCALE_EPSILON) {
                            if (Shaft.sSettings.isUseCustomLongPressReset) {
                                Toaster.showShort(R.string.double_tap_zoom_max_reached)
                                if (viewModel.isFullscreenMode.value == true) {
                                    viewModel.toggleFullscreen()
                                }
                            } else {
                                isScaleMax = true
                                Toaster.showShort(R.string.double_tap_zoom_max_reached2)
                            }
                        }
                    }
                    isAnimated = false
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isGestureTargetAlive) return
                if (!isAnimated && Shaft.sSettings.isUseCustomLongPressReset) {
                    val zoomable = baseBind.image.zoomable
                    val contentPoint = zoomable.touchPointToContentPointF(OffsetCompat(e.x, e.y))
                    if (viewModel.isFullscreenMode.value == true) {
                        viewModel.toggleFullscreen()
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        zoomable.scale(
                            targetScale = zoomable.minScaleState.value,
                            centroidContentPointF = contentPoint,
                            animated = true
                        )
                        isScaleMax = false
                    }
                }
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isGestureTargetAlive) return true
                viewModel.toggleFullscreen()
                return true
            }
        })
    }

    /**
     * 供大图页 DragDismissLayout 判定能否起竖向拖拽关闭手势。
     * 无论当前缩放倍率，到顶后可继续下拉，到底后可继续上推；
     * 图片还能沿当前方向平移时仍归 ZoomImage。圈选翻译模式下框选层接管触摸，不参与。
     */
    fun canSwipeToDismiss(direction: DragDismissLayout.Direction): Boolean {
        if (view == null || baseBind == null) return false
        if (baseBind.manualSelectionOverlay.visibility == View.VISIBLE) return false
        val scrollDirection = when (direction) {
            DragDismissLayout.Direction.UP -> 1
            DragDismissLayout.Direction.DOWN -> -1
        }
        return !baseBind.image.canScrollVertically(scrollDirection)
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
        when (Shaft.sSettings.getDoubleTapZoomMode()) {
            Settings.DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL -> {
                // 三级继续走库原生 switchScale，只通过自定义 ScalesCalculator 修正 mediumScale，
                // 避免库原生跳档逻辑在大间距时从最小档直接跳到最大档。
                baseBind.image.zoomable.setThreeStepScale(true)
                baseBind.image.zoomable.setScalesCalculator(NoSkipThreeStepScalesCalculator())
                baseBind.image.onViewTapListener = OnViewTapListener { _, _ ->
                    if (!isGestureTargetAlive) return@OnViewTapListener
                    viewModel.toggleFullscreen()
                }
                setupLibraryLongPressReset()
            }
            Settings.DOUBLE_TAP_ZOOM_MODE_INCREMENTAL -> {
                // PR#900 路径：禁用 ZoomImage 自带双击缩放，改走自家 GestureDetector
                baseBind.image.zoomable.setThreeStepScale(false)
                baseBind.image.zoomable.setScalesCalculator(ScalesCalculator.Dynamic)
                baseBind.image.zoomable.setDisabledGestureTypes(
                    baseBind.image.zoomable.disabledGestureTypesState.value or GestureType.DOUBLE_TAP_SCALE
                )
                setupCustomDoubleTapTouchListener()
            }
            else -> {
                // 默认路径：onViewTapListener → setReadMode 的顺序与改前完全一致
                baseBind.image.zoomable.setThreeStepScale(false)
                baseBind.image.zoomable.setScalesCalculator(ScalesCalculator.Dynamic)
                baseBind.image.onViewTapListener = OnViewTapListener { _, _ ->
                    // 线上崩溃点：ZoomImage 的 onSingleTapConfirmed 经 Handler 延迟派发，
                    // 打到已 detach 的 fragment 上时 viewModel 会走 requireActivity() 抛 ISE。
                    if (!isGestureTargetAlive) return@OnViewTapListener
                    viewModel.toggleFullscreen()
                }
                setupLibraryLongPressReset()
            }
        }
        // 长图阅读模式：自动填满宽度、从顶部开始，无需手动双击放大再滑动
        baseBind.image.zoomable.setReadMode(ReadMode.Default)

        // large 占位切 original 时，保留用户已经做的缩放/平移，避免原图到位后被弹回 fit
        baseBind.image.zoomable.setKeepTransformWhenSameAspectRatioContentSizeChanged(true)
    }

    /** 增量模式自定义双击路径的触摸分发：单指交给 GestureDetector，多指交回 ZoomImage。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupCustomDoubleTapTouchListener() {
        baseBind.image.setOnTouchListener { v, event ->
            // 还是要阻止可能存在误触打断到动画的
            if (event.pointerCount == 1) {
                gestureDetector.onTouchEvent(event)
            } else {
                // 多指（双指缩放/拖拽）：取消 gestureDetector 的待处理长按事件
                // 通过发送一个 ACTION_CANCEL 来阻止长按触发
                gestureDetector.onTouchEvent(
                    MotionEvent.obtain(
                        event.downTime,
                        event.eventTime,
                        MotionEvent.ACTION_CANCEL,
                        event.x,
                        event.y,
                        event.metaState
                    ).also { it.recycle() }
                )
                v.onTouchEvent(event)
            }
        }
    }

    /**
     * 默认/三级共用：让库自带长按也支持「长按复原到最小」。
     *
     * 开关关闭时必须**整个不挂**这个 listener，不能挂上去再在回调里判空跑：ZoomImage 的
     * TouchHelper 只要发现 onViewLongPressListener 非 null，长按一触发就把 longPressExecuted
     * 置 true，而 onGestureCallback / onEndCallback 都是 `if (longPressExecuted) return`——
     * 于是「按住不动超过 500ms 再拖」这一整串手势的平移、双指缩放、抬手后的 fling / 回弹全被吞掉。
     * 挂空回调等于给没开这个功能的人白白砍掉一种拖动方式。
     */
    private fun setupLibraryLongPressReset() {
        if (!Shaft.sSettings.isUseCustomLongPressReset) return
        baseBind.image.onViewLongPressListener = OnViewLongPressListener { _, offset ->
            if (isGestureTargetAlive && Shaft.sSettings.isUseCustomLongPressReset) {
                val zoomable = baseBind.image.zoomable
                val contentPoint = zoomable.touchPointToContentPointF(offset)
                if (viewModel.isFullscreenMode.value == true) {
                    viewModel.toggleFullscreen()
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    zoomable.scale(
                        targetScale = zoomable.minScaleState.value,
                        centroidContentPointF = contentPoint,
                        animated = true
                    )
                }
            }
        }
    }

    /** 清掉「已到最大」的记忆，避免 contentSize 变化后增量双击误判。 */
    private fun resetZoomLevelMemory() {
        isScaleMax = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 换底图（large 占位 → 原图 / 译图）会改变 contentSize，增量模式记的绝对倍率随之失真，
        // 见 [lastZoomContentSize]。默认/三级都走库自带双击，没有这类记忆，不必挂这个观察。
        if (Shaft.sSettings.getDoubleTapZoomMode() == Settings.DOUBLE_TAP_ZOOM_MODE_INCREMENTAL) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    baseBind.image.zoomable.contentSizeState.collect { size ->
                        if (!size.isNotEmpty()) return@collect
                        if (lastZoomContentSize.isNotEmpty() && size != lastZoomContentSize) {
                            resetZoomLevelMemory()
                        }
                        lastZoomContentSize = size
                    }
                }
            }
        }
        loadImage()
        // 监听"翻译漫画"产出:VM 里出现本页 index 的译图就直接换图
        translationViewModel.translatedPaths.observe(viewLifecycleOwner) { map ->
            val path = map[index] ?: return@observe
            val f = File(path)
            if (f.exists()) {
                baseBind.image.loadImage(f)
            }
        }
        // 「圈选翻译」请求:命中本页 index 才进圈选模式,进完立刻消费防重复触发
        translationViewModel.manualSelectionRequest.observe(viewLifecycleOwner) { req ->
            if (req != null && req == index) {
                translationViewModel.consumeManualSelectionRequest()
                enterManualSelection()
            }
        }
    }

    /** 进圈选模式:亮出框选层接管触摸,画完一框就退出并交给 VM 翻译。 */
    private fun enterManualSelection() {
        if (translationViewModel.running.value == true || requireContext().appServices().mangaBatchTranslateCenter.isRunning) {
            Toaster.showShort(R.string.string_ai_translate_in_progress)
            return
        }
        val box = baseBind.selectionBoxView
        box.reset()
        box.onSelected = { rect ->
            exitManualSelection()
            handleSelectedBox(rect)
        }
        box.onCancelled = { exitManualSelection() }
        baseBind.manualSelectionCancel.setOnClick { exitManualSelection() }
        val overlay = baseBind.manualSelectionOverlay
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(200).start()
    }

    private fun exitManualSelection() {
        val overlay = baseBind.manualSelectionOverlay
        if (overlay.visibility != View.VISIBLE) return
        overlay.animate().alpha(0f).setDuration(200).withEndAction {
            overlay.visibility = View.GONE
        }.start()
    }

    /**
     * 把框选层吐回的「View 坐标系矩形」用 zoomimage 的 transform 换算成「内容坐标」,
     * 再除以 contentSize 归一化到 [0,1] —— 这样与显示图实际分辨率、当前缩放都无关,
     * VM 拿到归一化矩形按底图分辨率还原即可。最后解析原图 File 交给 VM 翻译。
     */
    private fun handleSelectedBox(screenRect: RectF) {
        val illust = mIllust ?: return
        val zoomable = baseBind.image.zoomable
        val size = zoomable.contentSizeState.value
        if (size.width <= 0 || size.height <= 0) {
            Toaster.showShort(R.string.string_ai_manga_translate_failed)
            return
        }
        // 选区太小(细长误触)直接拦下,免得 OCR 拿到一条线
        val minPx = 16f * resources.displayMetrics.density
        if (screenRect.width() < minPx || screenRect.height() < minPx) {
            Toaster.showShort(R.string.string_ai_manga_manual_too_small)
            return
        }
        val tl = zoomable.touchPointToContentPointF(OffsetCompat(screenRect.left, screenRect.top))
        val br = zoomable.touchPointToContentPointF(OffsetCompat(screenRect.right, screenRect.bottom))
        val l = (minOf(tl.x, br.x) / size.width).coerceIn(0f, 1f)
        val t = (minOf(tl.y, br.y) / size.height).coerceIn(0f, 1f)
        val r = (maxOf(tl.x, br.x) / size.width).coerceIn(0f, 1f)
        val b = (maxOf(tl.y, br.y) / size.height).coerceIn(0f, 1f)

        val imageUrl = IllustDownload.getUrl(illust, index, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, index, Params.IMAGE_RESOLUTION_LARGE) ?: return
        // 只读已加载好的原图,图已显示、正常都是命中,不触发多余下载
        val file = ImageLoaderV3.peekFile(imageUrl)
        if (file == null) {
            Toaster.showShort(R.string.string_ai_ocr_failed)
            return
        }
        val started = translationViewModel.startManualRegion(
            file, index, l, t, r, b, MangaOcrModel.MANGA_OCR_BASE
        )
        if (!started) {
            Toaster.showShort(R.string.string_ai_translate_in_progress)
        }
    }

    private fun loadImage() {
        baseBind.emptyFrame.visibility = View.GONE
        // 重新加载（重试 / onViewCreated 再进）时复位「large 占位 → 原图」竞态状态。
        originalShown = false
        largeDisposable?.dispose()
        largeDisposable = null
        val isUrlMode = mIllust == null && !TextUtils.isEmpty(url)
        val imageUrl: String? = if (isUrlMode) {
            url
        } else {
            IllustDownload.getUrl(mIllust, index, Params.IMAGE_RESOLUTION_ORIGINAL)
        }

        val shortUrl = imageUrl?.substringAfterLast('/') ?: "null"
        Timber.d("[ImageDetail] loadImage index=$index, isUrlMode=$isUrlMode, url=$shortUrl")

        if (imageUrl.isNullOrEmpty()) return

        // content:// URI（来自下载完成页的 SAF 路径）直接用 Sketch 加载，
        // 不走 TaskPool/Glide，因为 Glide 没有 SAF URI 的访问权限。
        if (imageUrl.startsWith("content://")) {
            baseBind.image.loadImage(Uri.parse(imageUrl))
            return
        }

        // 这一页原图若已下载到本地，直读本地文件秒展示，跳过 TaskPool 重新下原图。
        // 与一级详情页 IllustAdapter.scanLocalDownloads 同源：按 FileCreator.customFileName
        // （= 下载写库的 fileName）主键精确查 illust_download_table，命中即用 Sketch 直读。
        // 修复用户反馈：多 P 未展开时点下载已下好原图，进二级详情大图却仍走进度条重新下原图。
        //
        // 仅当原图还没在查看器缓存里时才查库：正常浏览(原图已在 TaskPool 缓存)直接走原同步
        // 快路径，零 DB 开销、即时显示；真正要修的「下载过但查看器没缓存」才值得多查一次库。
        val illust = mIllust
        if (!isSnapshotMode && !isUrlMode && illust != null && !illust.isGif() && ImageLoaderV3.peekFile(imageUrl) == null) {
            // 原图尚未就绪（典型：一级详情页 B「展示原图」关，只显了 large）。先用 B 已加载的 large
            // 秒铺底，原图并行下好再盖上——避免大图页只剩一个转圈的空白等待。原图已在缓存
            // （peekFile 命中，通常是「展示原图」开时 B 已下好）则不进本分支，直接秒显原图，无需占位。
            showLargePlaceholder(illust, imageUrl)
            viewLifecycleOwner.lifecycleScope.launch {
                val localUri = withContext(Dispatchers.IO) { findDownloadedPageUri(illust, index) }
                if (localUri != null) {
                    Timber.d("[ImageDetail] local download HIT index=$index uri=$localUri")
                    loadFromLocal(localUri, imageUrl, isUrlMode)
                } else {
                    loadFromNetwork(imageUrl, isUrlMode)
                }
            }
            return
        }

        loadFromNetwork(imageUrl, isUrlMode)
    }

    /**
     * 原图下好前，先把一级详情页 B 已加载的 large 秒铺到底图 —— large 的字节已在 Glide 磁盘缓存里，
     * 经 [ImageLoaderV3]（= Glide asFile）取回时命中缓存即回，不额外走一次网络。原图
     * [ImageLoadState.Success] 后会 loadImage(原图) 盖掉本占位。[originalShown] 兜住竞态：原图先到时，
     * 晚到的 large 不再往上盖，避免用 large 覆盖已显示的原图导致清晰度回退。
     *
     * @param originalUrl 本页原图 url，仅用来和 large 比对：single-page 无 meta 时两者会回退到同一张，
     *                    此时占位无意义、直接跳过。
     */
    private fun showLargePlaceholder(illust: Illust, originalUrl: String) {
        val largeUrl = IllustDownload.getUrl(illust, index, Params.IMAGE_RESOLUTION_LARGE)
        if (largeUrl.isNullOrEmpty() || largeUrl == originalUrl) return

        val largeTask = ImageLoaderV3.obtain(largeUrl)
        // 已在缓存（B 显过 large）→ 直接秒铺，不必再挂观察。原图仍在后台下，进度条照旧盖在
        // large 上显示原图下载进度（与一级详情页 B 的 large + 进度环体验一致），Success 后再换原图。
        val cached = largeTask.currentFile
        if (cached != null) {
            if (!originalShown) baseBind.image.loadImage(cached)
            return
        }
        largeDisposable?.dispose()
        largeDisposable = largeTask.observeState(viewLifecycleOwner) { state ->
            if (state is ImageLoadState.Success && !originalShown) {
                baseBind.image.loadImage(state.file)
            }
        }
    }

    /**
     * 直接 Sketch 读已下载的本地文件（content:// 或 file://），不走 TaskPool 网络下载。
     * 读失败（文件被移动/删除/无权限）就回退网络路径重新下，行为与 IllustAdapter 一致。
     */
    private fun loadFromLocal(localUri: Uri, imageUrl: String, isUrlMode: Boolean) {
        // 本地已有原图，直读即最终图 —— 摘掉 large 占位观察，别让晚到的 large 回盖已显示的原图。
        originalShown = true
        largeDisposable?.dispose()
        baseBind.progressCircular.visibility = View.GONE
        baseBind.image.loadImage(localUri) {
            addListener(onError = { _, _ ->
                Timber.w("[ImageDetail] local file load FAIL uri=$localUri, fall back to network")
                loadFromNetwork(imageUrl, isUrlMode)
            })
        }
    }

    private fun loadFromNetwork(imageUrl: String, isUrlMode: Boolean) {
        val shortUrl = imageUrl.substringAfterLast('/')
        // V3 imageloader：同一 url 与一级详情页 IllustAdapter 共享同一个进程级任务/进度/结果，不各下一次。
        val task = ImageLoaderV3.obtain(imageUrl)
        // 上一次(可能在一级详情)失败的话，打开大图时重来一次——对齐旧 TaskPool「取到 errored 任务即换新重下」。
        if (task.state.value is ImageLoadState.Error) task.retry()
        Timber.d("[ImageDetail] task acquired. state=${task.state.value}, hasResult=${task.currentFile != null}, url=$shortUrl")

        // 复用观察前先摘掉上一次（重试等重复调用），避免观察者叠加。
        imageDisposable?.dispose()
        imageDisposable = task.observeState(viewLifecycleOwner) { state ->
            when (state) {
                ImageLoadState.Idle -> {
                    // 对齐旧 setUpWithTaskStatus 的 NotStart：先亮出进度条(0)，别留空白。
                    baseBind.progressCircular.visibility = View.VISIBLE
                    baseBind.progressCircular.progress = 0
                }
                is ImageLoadState.Loading -> {
                    // large 占位下，进度条照旧盖在图上显示原图下载进度（与一级详情页 B 一致）。
                    baseBind.progressCircular.visibility = View.VISIBLE
                    baseBind.progressCircular.progress = state.percent
                }
                is ImageLoadState.Success -> {
                    baseBind.progressCircular.visibility = View.GONE
                    // 原图就绪：标记 + 摘掉 large 占位观察，晚到的 large 不再回盖。
                    originalShown = true
                    largeDisposable?.dispose()
                    val file = state.file
                    Timber.d("[ImageDetail] result callback. file=${file.absolutePath}, size=${file.length()}, url=$shortUrl")
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
                is ImageLoadState.Error -> {
                    baseBind.progressCircular.visibility = View.GONE
                }
            }
        }
    }

    /**
     * 按页码查 illust_download_table 里这一页已下载文件的 Uri（content:// 或 file://）。
     *
     * 两条路，都走索引，大下载库下都是 O(log n)：
     *  1. `(illustId, page)` 复合索引（v41）—— 跟文件叫什么名字无关，用户换过命名模板、
     *     或记录是 `DownloadImporter` 从旧版命名的文件扫进来的（issue #953），照样命中。
     *  2. 落空时退回 [FileCreator.customFileName] + 主键查询 —— v41 之前的存量行 page
     *     还是 -1（回填没跑完 / 文件名解析不出页码），得靠这条兜。
     *
     * 返回 null 表示这页没下过 / 记录损坏，调用方回退网络。须在 IO 线程调用。
     */
    private fun findDownloadedPageUri(illust: Illust, page: Int): Uri? {
        return try {
            val context = Shaft.getContext()
            RecordedPageProbe.findUsableUri(context, illust.id.toLong(), page)
                ?: AppDatabase.getAppDatabase(context).downloadDao()
                    .getDownloadByFileName(FileCreator.customFileName(illust, page))
                    ?.filePath
                    ?.let { RecordedPageProbe.usableUri(context, it) }
        } catch (e: Exception) {
            Timber.w(e, "[ImageDetail] findDownloadedPageUri failed page=%d", page)
            null
        }
    }

    companion object {
        // PR#900 自定义双击放大：每次乘 1.8f；浮点误差判最大倍数容差 0.01f
        //private const val CUSTOM_ZOOM_ADD_SCALE = 1.8f
        private const val MAX_SCALE_EPSILON = 0.01f

        // Illust 由 ImageDetailActivity 持有，Fragment 运行时读取，避免放进 Bundle
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
