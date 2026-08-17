package ceui.lisa.activities

import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.ActivityImageDetailBinding
import ceui.lisa.download.IllustDownload
import ceui.lisa.fragments.FragmentImageDetail
import ceui.lisa.helper.ImageViewerTransition
import ceui.lisa.helper.PageTransformerHelper
import ceui.lisa.view.DragDismissLayout
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.pixiv.witstudio.popup.WitMenuPopup
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import ceui.lisa.databinding.ViewV3FabBarBinding
import ceui.lisa.download.FileCreator
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.detail.DownloadFab
import ceui.pixiv.ui.detail.V3FabBarController
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.ExifKeywordWriter
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.imageloader.ImageLoaderV3
import ceui.pixiv.ui.translate.ComicTextDetectorModel
import ceui.pixiv.ui.translate.ComicTextDetectorModelManager
import ceui.pixiv.ui.translate.MangaOcrModel
import ceui.pixiv.ui.translate.MangaOcrModelManager
import ceui.pixiv.ui.translate.MangaTranslatePrepSheet
import ceui.pixiv.ui.upscale.BackgroundRemover
import ceui.pixiv.ui.upscale.ModelPickerDialog
import ceui.pixiv.ui.upscale.RembgModel
import ceui.pixiv.ui.upscale.RembgModelPickerDialog
import ceui.pixiv.ui.upscale.UpscaleModel
import ceui.pixiv.ui.upscale.UpscaleStatus
import ceui.pixiv.ui.upscale.UpscaleTask
import ceui.pixiv.ui.upscale.UpscaleTaskPool
import ceui.pixiv.ui.works.ToggleToolnarViewModel
import ceui.pixiv.utils.animateFadeInQuickly
import ceui.pixiv.utils.animateFadeOutQuickly
import com.blankj.utilcode.util.BarUtils
import com.google.android.material.progressindicator.CircularProgressIndicator
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.Locale
import kotlin.coroutines.resume
import timber.log.Timber

/**
 * 图片二级详情
 */
class ImageDetailActivity : BaseActivity<ActivityImageDetailBinding?>() {
    var mIllustsBean: IllustsBean? = null
        private set
    private val translationViewModel by viewModels<ImageTranslationViewModel>()
    private var localIllust: List<String>? = ArrayList()
    private var currentPage: TextView? = null
    private var downloadSingle: TextView? = null
    private var index = 0
    private val viewModel by viewModels<ToggleToolnarViewModel>()

    /** 小红书式全屏弹窗转场(进场展开/竖向拖拽跟手/收场缩回),见 [ImageViewerTransition]。 */
    private var viewerTransition: ImageViewerTransition? = null
    private var restoredFromSavedState = false
    private var entryOrientation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // 重建恢复(旋转关掉 configChanges 的场景不会走到,但进程重建会)不播进场动画
        restoredFromSavedState = savedInstanceState != null
        super.onCreate(savedInstanceState)
    }

    override fun setTheme(resid: Int) {
        super.setTheme(resid)
        // BaseActivity.updateTheme 会按用户主题色 setTheme(AppTheme_IndexN),把 manifest 里
        // ImageViewerTheme 的透明 windowBackground 盖回不透明;每次 setTheme 后都叠回窗口
        // 透明属性,保证 PhoneWindow 生成 DecorView 时读到透明背景。「能看见身后 Activity」
        // 本身由 manifest 主题在启动时决定,不受运行期换主题影响。
        theme.applyStyle(R.style.ImageViewerWindowOverlay, true)
    }

    /** 「二级详情」的下载 + 收藏胶囊(与一级 V3 详情页共用),仅该模式下装配。 */
    private var fabBar: V3FabBarController? = null

    override fun initLayout(): Int {
        return R.layout.activity_image_detail
    }

    override fun initView() {
        observeTranslationStatus()
        val dataType = intent.getStringExtra("dataType")
        baseBind!!.viewPager.setPageTransformer(true, PageTransformerHelper.getCurrentTransformer())
        // issue #724: 个性化「看图时保留状态栏区域」开启时，给 ViewPager 顶部留出状态栏高度，
        // 让图片渲染在刘海/挖孔下方而不是铺满顶部被遮挡。用固定状态栏高度（而非 statusBars inset）
        // 是为了在双击进入沉浸/隐藏系统栏后顶部留白依旧保持。默认关闭，体验与原来完全一致。
        if (Shaft.sSettings.isKeepStatusBarWhenViewImage) {
            baseBind!!.viewPager.setPadding(0, BarUtils.getStatusBarHeight(), 0, 0)
        }
        val windowInsetsController = WindowInsetsControllerCompat(
            window,
            window.decorView
        )
        val btnAi = findViewById<View>(R.id.btn_ai_menu)
        ViewCompat.setOnApplyWindowInsetsListener(btnAi) { v, windowInsets ->
            val statusBarHeight = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val lp = v.layoutParams as android.widget.RelativeLayout.LayoutParams
            lp.topMargin = statusBarHeight + 8
            v.layoutParams = lp
            windowInsets
        }
        // btnAi 只在「二级详情」可用；放进 infoItems 会被 animateFadeInQuickly() 顶掉 GONE 状态 (issue #872)
        val infoItems = mutableListOf<View>()
        baseBind?.bottomRela?.let { infoItems.add(it) }
        if ("二级详情" == dataType) {
            infoItems.add(btnAi)
        }
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        viewModel.isFullscreenMode.observe(this) { isFullScreen ->
            if (isFullScreen) {
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                infoItems.forEach { it.animateFadeOutQuickly() }
            } else {
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                infoItems.forEach { it.animateFadeInQuickly() }
            }
        }
        setupViewerTransition(btnAi)
        if ("二级详情" == dataType) {
            currentPage = findViewById(R.id.current_page)
            mIllustsBean = intent.getSerializableExtra("illust") as IllustsBean?
            index = intent.getIntExtra("index", 0)
            if (mIllustsBean == null) {
                // 没有 bean 就装配不了任何点击语义,别留一个看得见点不动的胶囊
                findViewById<View>(R.id.fab_bar_row).visibility = View.GONE
                return
            }
            val btnAiMenu = findViewById<ImageView>(R.id.btn_ai_menu)
            btnAiMenu.visibility = View.VISIBLE
            btnAiMenu.setOnClickListener { anchor ->
                val illust = mIllustsBean ?: return@setOnClickListener
                // 动图(ugoira)的 original 是 zip,画质增强/抠图没法处理,不展示这两项(对齐 V3 详情页)。
                val actions = mutableListOf<Pair<CharSequence, () -> Unit>>()
                if (!illust.isGif) {
                    actions += getString(R.string.string_ai_upscale) to {
                        ModelPickerDialog.pickOrUseDefault(supportFragmentManager) { model ->
                            performAiUpscale(illust, baseBind!!.viewPager.currentItem, model)
                        }
                    }
                    actions += getString(R.string.string_ai_rembg) to {
                        RembgModelPickerDialog.pickOrUseDefault(supportFragmentManager) { model ->
                            performAiRembg(illust, baseBind!!.viewPager.currentItem, model)
                        }
                    }
                }
                actions += getString(R.string.string_ai_manga_translate_inline) to {
                    performAiMangaTranslateInline(illust, baseBind!!.viewPager.currentItem)
                }
                actions += getString(R.string.string_ai_manga_translate_manual) to {
                    performAiMangaTranslateManual(illust, baseBind!!.viewPager.currentItem)
                }
                actions += getString(R.string.string_set_wallpaper) to {
                    performSetWallpaper(illust, baseBind!!.viewPager.currentItem)
                }
                WitMenuPopup.show(this, anchor, actions.map { it.first }.toTypedArray()) { index, _ ->
                    actions[index].second()
                }
            }
            baseBind!!.viewPager.adapter = object : FragmentPagerAdapter(
                supportFragmentManager
            ) {
                override fun getItem(i: Int): Fragment {
                    return FragmentImageDetail.newInstance(i)
                }

                override fun getCount(): Int {
                    return mIllustsBean!!.page_count
                }
            }
            baseBind!!.viewPager.currentItem = index
            setupFabBar()
            checkDownload(index)
            baseBind!!.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrolled(i: Int, v: Float, i1: Int) {
                }

                override fun onPageSelected(i: Int) {
                    checkDownload(i)
                    currentPage?.setText(
                        String.format(
                            Locale.getDefault(),
                            "第 %d/%d P",
                            i + 1,
                            mIllustsBean!!.page_count
                        )
                    )
                }

                override fun onPageScrollStateChanged(i: Int) {
                }
            })
            if (mIllustsBean!!.page_count == 1) {
                currentPage?.setVisibility(View.INVISIBLE)
            } else {
                currentPage?.setText(
                    String.format(
                        Locale.getDefault(),
                        "第 %d/%d P",
                        index + 1,
                        mIllustsBean!!.page_count
                    )
                )
            }
        } else if (ceui.pixiv.ui.common.ImageUrlViewer.DATA_TYPE_URL_SINGLE == dataType) {
            findViewById<View>(R.id.btn_ai_menu).visibility = View.GONE
            findViewById<View>(R.id.fab_bar_row).visibility = View.GONE
            currentPage = findViewById(R.id.current_page)
            val singleUrl = intent.getStringExtra(Params.URL)
            val singleTitle = intent.getStringExtra(Params.TITLE)
            if (singleUrl.isNullOrEmpty()) {
                finish()
                return
            }
            baseBind!!.viewPager.adapter = object : FragmentPagerAdapter(
                supportFragmentManager
            ) {
                override fun getItem(i: Int): Fragment =
                    FragmentImageDetail.newInstance(singleUrl, singleTitle)

                override fun getCount(): Int = 1
            }
        } else if ("下载详情" == dataType) {
            findViewById<View>(R.id.btn_ai_menu).visibility = View.GONE
            findViewById<View>(R.id.fab_bar_row).visibility = View.GONE
            currentPage = findViewById(R.id.current_page)
            // 该模式下这个「按钮」只当文件路径标签用(历史行为),不参与下载
            downloadSingle = findViewById(R.id.download_this_one)
            downloadSingle?.visibility = View.VISIBLE
            localIllust = intent.getSerializableExtra("illust") as List<String>?
            index = intent.getIntExtra("index", 0)

            baseBind!!.viewPager.adapter = object : FragmentPagerAdapter(
                supportFragmentManager
            ) {
                override fun getItem(i: Int): Fragment {
                    return FragmentImageDetail.newInstance(localIllust!![i])
                }

                override fun getCount(): Int {
                    return localIllust!!.size
                }
            }
            currentPage?.setVisibility(View.INVISIBLE)
            baseBind!!.viewPager.currentItem = index
            baseBind!!.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrolled(i: Int, v: Float, i1: Int) {
                }

                override fun onPageSelected(i: Int) {
                    try {
                        downloadSingle?.setText(
                            String.format(
                                "%s%s", getString(R.string.file_path),
                                URLDecoder.decode(localIllust!![i], "utf-8")
                            )
                        )
                    } catch (e: UnsupportedEncodingException) {
                        e.printStackTrace()
                    }
                }

                override fun onPageScrollStateChanged(i: Int) {
                }
            })
            try {
                downloadSingle?.setText(
                    String.format(
                        "%s%s", getString(R.string.file_path),
                        URLDecoder.decode(localIllust!![index], "utf-8")
                    )
                )
            } catch (e: UnsupportedEncodingException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 装配小红书式全屏弹窗转场:根布局(透明窗口上的黑底 + 竖向手势层)在图片到顶/底后
     * 接管继续外拉，驱动 ViewPager 跟手位移/缩小,黑底与工具条透明度交给 [ImageViewerTransition];
     * 松手过阈值走统一收场(缩回缩略图矩形或沿手势方向淡出),否则回弹。进场从发起端带来的
     * [EXTRA_ENTER_BOUNDS] 缩略图矩形展开,没带则居中放大淡入。
     */
    private fun setupViewerTransition(btnAi: View) {
        val rootLayout = baseBind!!.root as DragDismissLayout
        val chrome = listOfNotNull(baseBind?.bottomRela, btnAi)
        val transition = ImageViewerTransition(
            rootLayout,
            baseBind!!.viewPager,
            chrome,
            intent.getIntArrayExtra(EXTRA_ENTER_BOUNDS),
        )
        viewerTransition = transition
        entryOrientation = resources.configuration.orientation
        rootLayout.dragTargetView = baseBind!!.viewPager
        rootLayout.callback = object : DragDismissLayout.Callback {
            override fun canStartDismissDrag(direction: DragDismissLayout.Direction): Boolean =
                currentImageFragment()?.canSwipeToDismiss(direction) ?: true

            override fun onDismissDragUpdate(fraction: Float) =
                transition.onDragProgress(fraction)

            override fun onDismissDragRelease(
                shouldDismiss: Boolean,
                direction: DragDismissLayout.Direction,
                velocityY: Float,
            ) {
                // AI 翻译烧着 Token 时手势收掉也要走二次确认,与返回键同一闸口
                if (shouldDismiss && !maybeConfirmAiExit()) {
                    dismissViewer(direction)
                } else {
                    transition.springBack()
                }
            }
        }
        if (restoredFromSavedState) {
            transition.showImmediately()
        } else {
            transition.playEnter()
        }
    }

    /** FragmentPagerAdapter 内建 tag 规则定位当前页 fragment(三种 dataType 的页面都是它)。 */
    private fun currentImageFragment(): FragmentImageDetail? =
        supportFragmentManager.findFragmentByTag(
            "android:switcher:" + R.id.view_pager + ":" + baseBind!!.viewPager.currentItem
        ) as? FragmentImageDetail

    /**
     * 「二级详情」装配底部下载 + 收藏胶囊(布局/着色/顺序偏好/底距逻辑与一级 V3 详情页
     * 共用 [V3FabBarController];点击语义归本页:下载 = 保存**当前页**,收藏 = 收藏整个作品)。
     */
    private fun setupFabBar() {
        val fabBind = ViewV3FabBarBinding.bind(findViewById(R.id.fab_bar))
        val fabBar = V3FabBarController(fabBind)
        this.fabBar = fabBar
        fabBar.applyDownloadOrderPreference()
        // 底距落在「胶囊 + 页码」整行上,页码跟着胶囊一起动
        fabBar.attachBottomInsetMargin(findViewById(R.id.fab_bar_row))

        // 收藏态:先按 intent 带来的 bean 画一次,再观察 ObjectPool 里同 id 的权威 bean(若有)
        mIllustsBean?.let { fabBar.setBookmarked(it.isIs_bookmarked) }
        mIllustsBean?.id?.toLong()?.let { id ->
            ObjectPool.get<IllustsBean>(id).observe(this) { bean ->
                bean?.let { fabBar.setBookmarked(it.isIs_bookmarked) }
            }
        }

        fabBind.fabDownloadContainer.setOnClick {
            val illust = likeTargetIllust() ?: return@setOnClick
            val page = baseBind!!.viewPager.currentItem
            if (illust.isGif) {
                // ugoira/gif 要 zip→帧→gif 渲染,简单文件拷贝救不了,保留原下载链路(它做 unzipAndPlay)。
                IllustDownload.downloadIllustCertainPage(illust, page, mContext as BaseActivity<*>)
                autoLikeAfterDownloadIfNeeded(illust, fabBar)
                return@setOnClick
            }
            val imageUrl = IllustDownload.getUrl(illust, page, Params.IMAGE_RESOLUTION_ORIGINAL)
                ?: IllustDownload.getUrl(illust, page, Params.IMAGE_RESOLUTION_LARGE)
                ?: return@setOnClick
            lifecycleScope.launch {
                val ok = saveLoadedIllustPage(illust, page, imageUrl)
                if (ok) {
                    Common.showToast(R.string.string_181)
                    checkDownload(page)
                    autoLikeAfterDownloadIfNeeded(illust, fabBar)
                }
            }
        }

        fabBind.fabBookmark.setOnClick {
            val illust = likeTargetIllust() ?: return@setOnClick
            val willBookmark = !illust.isIs_bookmarked
            // 乐观着色,权威态由上面的 ObjectPool 观察兜底(与 ArtworkV3Fragment 同款)
            fabBar.setBookmarked(willBookmark)
            PixivOperate.postLikeDefaultStarType(illust)
            if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar) {
                IllustDownload.downloadIllustAllPages(illust)
            }
        }
    }

    /** 收藏/取消收藏作用于整个作品:优先取 ObjectPool 里的权威 bean(与一级详情共享乐观态),退回 intent 副本。 */
    private fun likeTargetIllust(): IllustsBean? =
        mIllustsBean?.let { ObjectPool.get<IllustsBean>(it.id.toLong()).value ?: it }

    private fun autoLikeAfterDownloadIfNeeded(illust: IllustsBean, fabBar: V3FabBarController) {
        if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isIs_bookmarked) {
            fabBar.setBookmarked(true)
            PixivOperate.postLikeDefaultStarType(illust)
        }
    }

    private fun checkDownload(i: Int) {
        val illust = mIllustsBean ?: return
        lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                Common.isIllustDownloaded(illust, i)
            }
            // 快速翻页时旧页的 DB 探测可能晚于新页返回,过期结果不能盖掉当前页的状态
            if (baseBind?.viewPager?.currentItem != i) return@launch
            // 对齐一级 V3 详情页:已下载的页显示绿色「已下载」勾,而不是把按钮藏起来
            fabBar?.renderDownload(if (downloaded) DownloadFab.Done else DownloadFab.Idle)
        }
    }

    /**
     * 「保存这一张」：复用大图页已加载的原图(与显示层同一 imageloader 共享任务,不重新下载),走**新**下载后端
     * [DownloadsRegistry] 按用户命名模板/存储配置写盘;并记一条 [DownloadEntity]，让「已下载」列表与详情本地复用
     * (findDownloadedPageUri 仍查 DB)保持一致。按钮隐藏靠 [Common.isIllustDownloaded] → 新后端 `exists()` 自动生效。
     * 不再走旧 `IllustDownload` / 不重下原图。
     */
    private suspend fun saveLoadedIllustPage(illust: IllustsBean, page: Int, imageUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = try {
                ImageLoaderV3.obtain(imageUrl).awaitFile()
            } catch (e: Exception) {
                Timber.w(e, "[ImageDetail] save: await loaded file failed page=%d", page)
                null
            } ?: return@withContext false

            runCatching {
                // open() 返回 null = Skip 策略且文件已存在 → 视为已保存,无需重写。
                val handle = DownloadsRegistry.downloads.open(DownloadItems.illustPage(illust, page))
                    ?: return@runCatching true
                try {
                    handle.stream.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                    handle.onFinish()
                } catch (t: Throwable) {
                    handle.onAbort()
                    throw t
                }
                // 可选:标签写进 JPEG XMP 关键词(issue #938)。放在 commit 之后、onAbort 作用域之外,
                // 免得写元数据抛错误删掉已成功的文件。整段再包一层 runCatching:即便标签取值意外抛错,
                // 也不能连累下面「已下载」写库(否则文件在盘上却不记账)。开关默认关时直接跳过,零开销。
                if (Shaft.sSettings.isWriteTagsToImageExif()) {
                    runCatching {
                        ExifKeywordWriter.writeIfEnabled(
                            this@ImageDetailActivity,
                            handle.uri,
                            FileCreator.customFileName(illust, page),
                            illust.tags.orEmpty().mapNotNull { it?.name }
                        )
                    }
                }
                // 与 Manager 成功分支一致地写库(fileName 用 FileCreator=模板命名,filePath 用写盘 uri)。
                val entity = DownloadEntity().apply {
                    illustGson = Shaft.sGson.toJson(illust)
                    fileName = FileCreator.customFileName(illust, page)
                    downloadTime = System.currentTimeMillis()
                    filePath = handle.uri.toString()
                    // v41 的 page 列 —— 与 Manager 成功分支一致，让按 (illustId, page) 的
                    // 查询也能命中「保存这一张」写下的记录。
                    this.page = page
                }
                // insertDownload 会从 illustGson 顶层 id 补上 illustId（走 v38 索引）。
                AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertDownload(entity)
                ManagerReactive.pokeDoneTable()
                true
            }.getOrElse { ex ->
                Timber.e(ex, "[ImageDetail] saveLoadedIllustPage failed page=%d", page)
                false
            }
        }

    override fun initData() {
        // 返回键/返回手势与下拉收掉共用 dismissViewer 收场动画。targetSdk 35+ 后预测式返回
        // 默认开启,系统不再回调 onBackPressed,必须用 OnBackPressedDispatcher 接管。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (maybeConfirmAiExit()) return
                finishViewer()
            }
        })
    }

    /**
     * AI 翻译已向接口发过 POST(有 Token 成本)时,返回/手势退出前弹二次确认,
     * 防止手滑退出白烧 Token。确认退出才取消流水线;选「继续翻译」则留在页面。
     * Google 免费端点 / 还没发请求的阶段不弹,直接走原退出逻辑。
     */
    private fun maybeConfirmAiExit(): Boolean {
        if (!translationViewModel.shouldConfirmAiExit()) return false
        WitDialog.MessageDialogBuilder(this)
            .setTitle(R.string.ai_translate_exit_confirm_title)
            .setMessage(R.string.ai_translate_exit_confirm_message)
            .addAction(
                0,
                getString(R.string.ai_translate_exit_confirm_stay),
                WitDialogAction.ACTION_PROP_NEGATIVE
            ) { dialog, _ -> dialog.dismiss() }
            .addAction(
                0,
                getString(R.string.ai_translate_exit_confirm_exit),
                WitDialogAction.ACTION_PROP_POSITIVE
            ) { dialog, _ ->
                dialog.dismiss()
                translationViewModel.cancelActiveWorkflow()
                finishViewer()
            }
            .show()
        return true
    }

    /** 统一的退出入口(返回键 / AI 确认框的「退出」都走这里)。 */
    private fun finishViewer() {
        dismissViewer()
    }

    /**
     * 收场:还停在进入那一页且没转过屏 → 缩回缩略图矩形;翻到别的页/转过屏后矩形已对不上,
     * 沿关闭手势方向淡出。动画播完才真正 finish(透明主题 windowAnimationStyle=@null,系统不再叠动画)。
     */
    private fun dismissViewer(direction: DragDismissLayout.Direction = DragDismissLayout.Direction.DOWN) {
        val transition = viewerTransition ?: run {
            mActivity.finish()
            return
        }
        val backToBounds = index == baseBind?.viewPager?.currentItem &&
                resources.configuration.orientation == entryOrientation
        transition.playExit(backToBounds, direction) { mActivity.finish() }
    }

    override fun onDestroy() {
        // 用户返回退出页面时立刻停掉翻译流水线并弹「翻译已取消」:
        // 不 cancel 的话 Google/AI 的阻塞 HTTP 会继续跑到超时,晚到的异常
        // 会被当成「翻译失败」误报给已经离开的用户。
        // 旋转等配置变更不走 isFinishing,ViewModel 存活、翻译继续,不打断。
        if (isFinishing) {
            translationViewModel.cancelActiveWorkflow()
        }
        super.onDestroy()
    }

    private fun performAiRembg(illust: IllustsBean, pageIndex: Int, model: RembgModel) {
        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_LARGE) ?: return

        val overlayRoot = findViewById<View>(R.id.ai_overlay_root) ?: return
        val loadingState = findViewById<View>(R.id.ai_loading_state)
        val doneState = findViewById<View>(R.id.ai_done_state)
        val progressRing = findViewById<CircularProgressIndicator>(R.id.ai_progress_ring)
        val progressText = findViewById<TextView>(R.id.ai_progress_text)
        val statusText = findViewById<TextView>(R.id.ai_status_text)

        overlayRoot.visibility = View.VISIBLE
        loadingState.visibility = View.VISIBLE
        doneState.visibility = View.GONE
        overlayRoot.alpha = 0f
        overlayRoot.animate().alpha(1f).setDuration(300).start()
        statusText.text = getString(R.string.string_ai_rembg_running)
        progressRing.isIndeterminate = true
        progressText.visibility = View.GONE

        // 复用大图页已加载的原图(与显示层同一共享任务),不重新下载。
        val task = ImageLoaderV3.obtain(imageUrl)
        lifecycleScope.launch {
            val file = try {
                task.awaitFile()
            } catch (e: Exception) {
                overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                    overlayRoot.visibility = View.GONE
                }.start()
                Common.showToast(R.string.string_ai_rembg_failed)
                return@launch
            }
            val result = BackgroundRemover.removeBackground(this@ImageDetailActivity, file, model) { percent ->
                runOnUiThread {
                    progressRing.isIndeterminate = false
                    progressText.visibility = View.VISIBLE
                    val p = (percent * 100).toInt()
                    progressRing.setProgressCompat(p, true)
                    progressText.text = "$p%"
                }
            }
            overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                overlayRoot.visibility = View.GONE
            }.start()
            if (result != null) {
                val intent = Intent(this@ImageDetailActivity, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "主体高亮")
                intent.putExtra("original_path", file.absolutePath)
                intent.putExtra("rembg_path", result.absolutePath)
                startActivity(intent)
            } else {
                Common.showToast(R.string.string_ai_rembg_failed)
            }
        }
    }

    /**
     * AI 菜单「翻译漫画」入口。所有重活搬到了 [ImageTranslationViewModel],
     * 这里只负责模型存在性检查 + 拉图 + 把 File 喂给 VM。
     * Overlay UI 由 [observeTranslationStatus] 单独驱动。
     */
    private fun performAiMangaTranslateInline(illust: IllustsBean, pageIndex: Int) {
        val ocrModel = MangaOcrModel.MANGA_OCR_BASE
        val ctdModel = ComicTextDetectorModel.CTD_BASE
        val ocrReady = MangaOcrModelManager.isModelReady(this, ocrModel)
        val ctdReady = ComicTextDetectorModelManager.isModelReady(this, ctdModel)
        if (!ocrReady || !ctdReady) {
            // 首次准备 sheet 把两次下载顺序串起来,完成后回调里直接重入翻译流水线 ——
            // 用户全程不离开 ImageDetailActivity,零跳转。
            if (supportFragmentManager.findFragmentByTag(MangaTranslatePrepSheet.TAG) != null) return
            val sheet = MangaTranslatePrepSheet()
            sheet.setOnReady { performAiMangaTranslateInline(illust, pageIndex) }
            sheet.show(supportFragmentManager, MangaTranslatePrepSheet.TAG)
            return
        }
        if (translationViewModel.running.value == true) {
            Common.showToast(R.string.string_ai_translate_in_progress)
            return
        }

        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_LARGE) ?: return

        lifecycleScope.launch {
            val file = awaitLoadedFile(imageUrl)
            if (file == null) {
                Common.showToast(R.string.string_ai_ocr_failed)
                return@launch
            }
            translationViewModel.start(applicationContext, file, pageIndex, ocrModel, ctdModel)
        }
    }

    /**
     * AI 菜单「圈选翻译」入口(issue #891)。模型就绪检查复用「翻译漫画」那套(同一 prep
     * sheet,本机只下一次),通过后只往 VM 投一个圈选请求,真正的框选 + 流水线由当前页
     * [FragmentImageDetail] 接管 —— Activity 不直接持 Fragment 引用,也不碰图片触摸。
     */
    private fun performAiMangaTranslateManual(illust: IllustsBean, pageIndex: Int) {
        val ocrModel = MangaOcrModel.MANGA_OCR_BASE
        val ctdModel = ComicTextDetectorModel.CTD_BASE
        val ocrReady = MangaOcrModelManager.isModelReady(this, ocrModel)
        val ctdReady = ComicTextDetectorModelManager.isModelReady(this, ctdModel)
        if (!ocrReady || !ctdReady) {
            if (supportFragmentManager.findFragmentByTag(MangaTranslatePrepSheet.TAG) != null) return
            val sheet = MangaTranslatePrepSheet()
            sheet.setOnReady { performAiMangaTranslateManual(illust, pageIndex) }
            sheet.show(supportFragmentManager, MangaTranslatePrepSheet.TAG)
            return
        }
        if (translationViewModel.running.value == true) {
            Common.showToast(R.string.string_ai_translate_in_progress)
            return
        }
        translationViewModel.requestManualSelection(pageIndex)
    }

    /**
     * VM.status 单一来源驱动 overlay:非 null 显示并刷状态/进度,null 淡出隐藏。
     */
    private fun observeTranslationStatus() {
        translationViewModel.status.observe(this) { status ->
            val overlayRoot = findViewById<View>(R.id.ai_overlay_root) ?: return@observe
            val statusText = findViewById<TextView>(R.id.ai_status_text)
            val progressRing = findViewById<CircularProgressIndicator>(R.id.ai_progress_ring)
            val progressText = findViewById<TextView>(R.id.ai_progress_text)

            if (status == null) {
                if (overlayRoot.visibility == View.VISIBLE) {
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                }
                return@observe
            }

            if (overlayRoot.visibility != View.VISIBLE) {
                findViewById<View>(R.id.ai_loading_state).visibility = View.VISIBLE
                findViewById<View>(R.id.ai_done_state).visibility = View.GONE
                overlayRoot.alpha = 0f
                overlayRoot.visibility = View.VISIBLE
                overlayRoot.animate().alpha(1f).setDuration(300).start()
            }
            statusText.text = status.text
            val pct = status.progressPercent
            if (pct != null) {
                progressRing.isIndeterminate = false
                progressRing.setProgressCompat(pct, true)
                progressText.visibility = View.VISIBLE
                progressText.text = "$pct%"
            } else {
                progressRing.isIndeterminate = true
                progressText.visibility = View.GONE
            }
        }
    }

    /**
     * 等图片下载/缓存就绪。复用大图页显示层的同一共享任务:已加载直接返回、否则等它下完,不重复下载。
     */
    private suspend fun awaitLoadedFile(imageUrl: String): File? =
        try {
            ImageLoaderV3.obtain(imageUrl).awaitFile()
        } catch (e: CancellationException) {
            // 页面销毁导致协程取消:重抛,别把「取消」当成加载失败弹「识别失败」
            throw e
        } catch (e: Exception) {
            null
        }

    private fun performSetWallpaper(illust: IllustsBean, pageIndex: Int) {
        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
        if (imageUrl == null) {
            Timber.w("[ImageDetail] set wallpaper: original url missing page=%d", pageIndex)
            Common.showToast(R.string.string_set_wallpaper_failed)
            return
        }

        lifecycleScope.launch {
            val file = awaitLoadedFile(imageUrl)
            if (file == null) {
                Common.showToast(R.string.string_set_wallpaper_failed)
                return@launch
            }
            val uri = runCatching {
                withContext(Dispatchers.IO) {
                    copyImageFileToCacheFolder(file, "wallpaper_from_shaft.png")
                }
            }.getOrElse { ex ->
                Timber.w(ex, "[ImageDetail] set wallpaper: prepare uri failed page=%d", pageIndex)
                Common.showToast(R.string.string_set_wallpaper_failed)
                return@launch
            }
            val intent = Intent(WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (ex: Exception) {
                Timber.w(ex, "[ImageDetail] set wallpaper failed")
                Common.showToast(R.string.string_set_wallpaper_failed)
            }
        }
    }

    private fun copyImageFileToCacheFolder(source: File, fileName: String): Uri {
        val dir = File(cacheDir, "wallpaper_share").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val target = File(dir, fileName)
        source.inputStream().use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(this, "$packageName.provider", target)
    }

    private fun performAiUpscale(illust: IllustsBean, pageIndex: Int, model: UpscaleModel) {
        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_LARGE) ?: return

        // 复用大图页已加载的原图(与显示层同一共享任务),不重新下载。
        val loadTask = ImageLoaderV3.obtain(imageUrl)
        lifecycleScope.launch {
            val file = try { loadTask.awaitFile() } catch (e: Exception) { return@launch }
            val key = UpscaleTask.illustKey(illust.id * 100 + pageIndex)
            val task = UpscaleTaskPool.startTask(key, this@ImageDetailActivity, file, file.absolutePath, model)
            observeUpscaleTask(task)
        }
    }

    private fun observeUpscaleTask(task: UpscaleTask) {
        val overlayRoot = findViewById<View>(R.id.ai_overlay_root) ?: return
        val loadingState = findViewById<View>(R.id.ai_loading_state)
        val doneState = findViewById<View>(R.id.ai_done_state)
        val viewCompare = findViewById<View>(R.id.ai_view_compare)
        val dismiss = findViewById<View>(R.id.ai_dismiss)
        val progressRing = findViewById<CircularProgressIndicator>(R.id.ai_progress_ring)
        val progressText = findViewById<TextView>(R.id.ai_progress_text)
        val statusText = findViewById<TextView>(R.id.ai_status_text)
        val etaText = findViewById<TextView>(R.id.ai_eta_text)

        viewCompare.setOnClickListener {
            val result = task.resultFile.value ?: return@setOnClickListener
            val intent = android.content.Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "画质增强对比")
            intent.putExtra("upscaled_path", result.absolutePath)
            intent.putExtra("original_path", task.originalFilePath)
            startActivity(intent)
            overlayRoot.visibility = View.GONE
            UpscaleTaskPool.removeTask(task.taskKey)
        }
        dismiss.setOnClickListener {
            overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                overlayRoot.visibility = View.GONE
            }.start()
            UpscaleTaskPool.removeTask(task.taskKey)
        }

        task.status.observe(this) { status ->
            when (status) {
                UpscaleStatus.Running -> {
                    overlayRoot.visibility = View.VISIBLE
                    loadingState.visibility = View.VISIBLE
                    doneState.visibility = View.GONE
                    if (overlayRoot.alpha < 1f) {
                        overlayRoot.alpha = 0f
                        overlayRoot.animate().alpha(1f).setDuration(300).start()
                    }
                    statusText.text = getString(R.string.string_ai_upscale_running, task.model.displayName)
                }
                UpscaleStatus.Done -> {
                    val result = task.resultFile.value
                    if (result != null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                            overlayRoot.visibility = View.GONE
                        }.start()
                        val intent = android.content.Intent(this@ImageDetailActivity, TemplateActivity::class.java)
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "画质增强对比")
                        intent.putExtra("upscaled_path", result.absolutePath)
                        intent.putExtra("original_path", task.originalFilePath)
                        startActivity(intent)
                        UpscaleTaskPool.removeTask(task.taskKey)
                    } else {
                        loadingState.visibility = View.GONE
                        doneState.visibility = View.VISIBLE
                        overlayRoot.visibility = View.VISIBLE
                        overlayRoot.alpha = 1f
                    }
                }
                UpscaleStatus.Failed -> {
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                    Common.showToast(R.string.string_ai_upscale_failed)
                    UpscaleTaskPool.removeTask(task.taskKey)
                }
                else -> {}
            }
        }
        task.progress.observe(this) { percent ->
            val p = (percent * 100).toInt()
            progressRing.setProgressCompat(p, true)
            progressText.text = "$p%"
        }
        task.eta.observe(this) { eta ->
            etaText.text = if (eta > 0) "预计 ${String.format("%.0f", eta)} 秒后完成" else ""
        }
    }

    override fun hideStatusBar(): Boolean {
        return true
    }

    companion object {
        /** 进场缩略图矩形(屏幕坐标 [left, top, right, bottom]),发起端可选携带;没带则居中淡入。 */
        const val EXTRA_ENTER_BOUNDS = "enter_bounds"
    }
}
