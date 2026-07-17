package ceui.lisa.fragments

import android.annotation.SuppressLint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.ImageTranslationViewModel
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentImageDetailBinding
import ceui.lisa.download.FileCreator
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
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
import com.github.panpf.zoomimage.util.OffsetCompat
import com.github.panpf.zoomimage.view.zoom.OnViewTapListener
import com.github.panpf.zoomimage.zoom.GestureType
import com.github.panpf.zoomimage.zoom.ReadMode
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
    private var savedScale: Float? = null
    private var zoomedToMax: Boolean = false
    private var pendingGestureCheck: Boolean = false
    private var imageDisposable: Disposable? = null
    // large 占位任务的观察句柄：原图就绪后摘掉，避免晚到的 large 回盖已显示的原图。
    private var largeDisposable: Disposable? = null
    // 原图是否已显示（网络成功 / 本地直读）。large 占位仅在其为 false 时才铺，兜住 large/原图竞态。
    private var originalShown: Boolean = false
    // 不再放进 arguments / savedInstanceState，避免每个 Fragment 重复持久化 80KB IllustsBean
    // 导致 TransactionTooLargeException。统一向 ImageDetailActivity 取。
    private val mIllustsBean: IllustsBean?
        get() = (activity as? ImageDetailActivity)?.mIllustsBean

    // PR#900：自定义双击「增量放大 + 长按归位」。
    // 仅在 Settings.isUseCustomDoubleTapZoom 开启时才会被访问/初始化；
    // 关闭时整条路径不参与，保持与未引入 PR 前完全一致的体验。
    private val gestureDetector by lazy {
        GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isAnimated) return true
                isAnimated = true
                val zoomable = baseBind.image.zoomable
                val contentPoint = zoomable.touchPointToContentPointF(OffsetCompat(e.x, e.y))
                viewLifecycleOwner.lifecycleScope.launch {
                    //三级智能推测
                    if (Shaft.sSettings.isUseThreeLevelZoo) {
                        val currentScale = zoomable.transformState.value.scaleX
                        val minScale = zoomable.minScaleState.value
                        val maxScale = zoomable.maxScaleState.value
                        val targetScale = currentScale * Shaft.sSettings.customZoomAddScale

                        when {
                            // ① 初始记录
                            savedScale == null -> {
                                if (viewModel.isFullscreenMode.value == false) {
                                    viewModel.toggleFullscreen()
                                }
                                zoomable.scale(
                                    targetScale = targetScale,
                                    centroidContentPointF = contentPoint,
                                    animated = true
                                )
                                savedScale = targetScale
                                zoomedToMax = false
                                pendingGestureCheck = false
                            }

                            // ② 处于待定期：上次从最大缩回了中间，现在根据手动缩放决定
                            pendingGestureCheck -> {
                                val saved = savedScale!!
                                if (currentScale > saved) {
                                    if (viewModel.isFullscreenMode.value == false) {
                                        viewModel.toggleFullscreen()
                                    }
                                    // 用户手动放大了 → 放大到最大
                                    zoomable.scale(
                                        targetScale = maxScale,
                                        centroidContentPointF = contentPoint,
                                        animated = true
                                    )
                                    zoomedToMax = true
                                } else {
                                    if (viewModel.isFullscreenMode.value == false) {
                                        viewModel.toggleFullscreen()
                                    }
                                    // 用户没放大或缩得更小 → 直接缩到最小并重置
                                    zoomable.scale(
                                        targetScale = minScale,
                                        centroidContentPointF = contentPoint,
                                        animated = true
                                    )
                                    savedScale = null
                                    zoomedToMax = false
                                }
                                pendingGestureCheck = false
                            }

                            // ③ 还没到最大，且当前缩放 ≥ 记录的中间值 → 放大到最大
                            !zoomedToMax && currentScale >= savedScale!! -> {
                                if (viewModel.isFullscreenMode.value == false) {
                                    viewModel.toggleFullscreen()
                                }
                                zoomable.scale(
                                    targetScale = maxScale,
                                    centroidContentPointF = contentPoint,
                                    animated = true
                                )
                                zoomedToMax = true
                            }

                            // ④ 其余情况：缩回逻辑
                            else -> {
                                val saved = savedScale!!
                                when {
                                    // 等于最大，或介于中间和最大之间 → 缩回中间，并开启待定期
                                    currentScale == maxScale || (currentScale < maxScale && currentScale > saved) -> {
                                        if (viewModel.isFullscreenMode.value == false) {
                                            viewModel.toggleFullscreen()
                                        }
                                        zoomable.scale(
                                            targetScale = saved,
                                            centroidContentPointF = contentPoint,
                                            animated = true
                                        )
                                        zoomedToMax = false
                                        pendingGestureCheck = true   // 等待用户手势
                                        // savedScale 不变
                                    }
                                    // 当前缩放 ≤ 中间 → 缩回最小，完全重置
                                    else -> {
                                        if (viewModel.isFullscreenMode.value == true) {
                                            viewModel.toggleFullscreen()
                                        }
                                        zoomable.scale(
                                            targetScale = minScale,
                                            centroidContentPointF = contentPoint,
                                            animated = true
                                        )
                                        savedScale = null
                                        zoomedToMax = false
                                        pendingGestureCheck = false
                                    }
                                }
                            }
                        }

                        isAnimated = false


                    } else {
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
                            isAnimated = false
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
                                    Toast.makeText(
                                        requireContext(),
                                        R.string.double_tap_zoom_max_reached,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (viewModel.isFullscreenMode.value == true) {
                                        viewModel.toggleFullscreen()
                                    }
                                } else {
                                    isScaleMax = true
                                    Toast.makeText(
                                        requireContext(),
                                        R.string.double_tap_zoom_max_reached2,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                    }
                        isAnimated = false
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
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
                        savedScale = null
                        zoomedToMax = false
                        pendingGestureCheck = false
                    }
                }
            }

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
        if (Shaft.sSettings.isUseCustomDoubleTapZoom) {
            // PR#900 路径：禁用 ZoomImage 自带双击缩放，改走自家 GestureDetector
            baseBind.image.zoomable.setDisabledGestureTypes(
                baseBind.image.zoomable.disabledGestureTypesState.value or GestureType.DOUBLE_TAP_SCALE
            )
            // 单指交给 gestureDetector（双击/长按/单击），多指交回 ZoomImage（拖拽/双指缩放）
            baseBind.image.setOnTouchListener { v, event ->
                //还是要阻止可能存在误触打断到动画的
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
        } else {
            // 默认路径：onViewTapListener → setReadMode 的顺序与改前完全一致
            baseBind.image.onViewTapListener = OnViewTapListener { _, _ ->
                viewModel.toggleFullscreen()
            }
        }
        // 长图阅读模式：自动填满宽度、从顶部开始，无需手动双击放大再滑动
        baseBind.image.zoomable.setReadMode(ReadMode.Default)
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
        if (translationViewModel.running.value == true) {
            Toast.makeText(requireContext(), R.string.string_ai_translate_in_progress, Toast.LENGTH_SHORT).show()
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
        val illust = mIllustsBean ?: return
        val zoomable = baseBind.image.zoomable
        val size = zoomable.contentSizeState.value
        if (size.width <= 0 || size.height <= 0) {
            Toast.makeText(requireContext(), R.string.string_ai_manga_translate_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // 选区太小(细长误触)直接拦下,免得 OCR 拿到一条线
        val minPx = 16f * resources.displayMetrics.density
        if (screenRect.width() < minPx || screenRect.height() < minPx) {
            Toast.makeText(requireContext(), R.string.string_ai_manga_manual_too_small, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), R.string.string_ai_ocr_failed, Toast.LENGTH_SHORT).show()
            return
        }
        translationViewModel.startManualRegion(
            requireContext().applicationContext, file, index, l, t, r, b, MangaOcrModel.MANGA_OCR_BASE
        )
    }

    private fun loadImage() {
        baseBind.emptyFrame.visibility = View.GONE
        // 重新加载（重试 / onViewCreated 再进）时复位「large 占位 → 原图」竞态状态。
        originalShown = false
        largeDisposable?.dispose()
        largeDisposable = null
        val isUrlMode = mIllustsBean == null && !TextUtils.isEmpty(url)
        val imageUrl: String? = if (isUrlMode) {
            url
        } else {
            IllustDownload.getUrl(mIllustsBean, index, Params.IMAGE_RESOLUTION_ORIGINAL)
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
        val illust = mIllustsBean
        if (!isUrlMode && illust != null && !illust.isGif() && ImageLoaderV3.peekFile(imageUrl) == null) {
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
    private fun showLargePlaceholder(illust: IllustsBean, originalUrl: String) {
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
     * 文件名用 [FileCreator.customFileName]，与下载写库时同源，所以是精确的逐页匹配，
     * 走主键索引（[ceui.lisa.database.DownloadDao.getDownloadByFileName]），大下载库下仍是
     * O(log n)。返回 null 表示这页没下过 / 记录损坏，调用方回退网络。须在 IO 线程调用。
     */
    private fun findDownloadedPageUri(illust: IllustsBean, page: Int): Uri? {
        return try {
            val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
            val entity = dao.getDownloadByFileName(FileCreator.customFileName(illust, page))
            val path = entity?.filePath
            if (!path.isNullOrEmpty()) Uri.parse(path) else null
        } catch (t: Throwable) {
            Timber.w(t, "[ImageDetail] findDownloadedPageUri failed page=%d", page)
            null
        }
    }

    companion object {
        // PR#900 自定义双击放大：每次乘 1.8f；浮点误差判最大倍数容差 0.01f
        //private const val CUSTOM_ZOOM_ADD_SCALE = 1.8f
        private const val MAX_SCALE_EPSILON = 0.01f

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
