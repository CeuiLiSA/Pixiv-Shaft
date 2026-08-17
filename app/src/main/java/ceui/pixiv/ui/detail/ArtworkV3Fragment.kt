package ceui.pixiv.ui.detail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.actions.FollowVisibility
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.pixiv.ui.muted.MuteTagSheet
import ceui.lisa.download.IllustDownload
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Dev
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.ShareIllust
import ceui.lisa.utils.V3Palette
import ceui.lisa.core.Mapper
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.combineLatest
import ceui.loxia.requireNetworkStateManager
import ceui.pixiv.chat.base.panel.PanelState
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.comments.CommentComposerController
import ceui.pixiv.ui.comments.CommentComposerPresentation
import ceui.pixiv.ui.comments.CommentTarget
import ceui.pixiv.ui.comments.CommentsComposerViewModel
import ceui.pixiv.ui.comments.SentComment
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.staggerIllustRenderer
import ceui.pixiv.ui.share.shareFirstImage
import ceui.pixiv.ui.task.PageLoadRetryController
import ceui.pixiv.ui.task.renderImageLoadStatusBanner
import ceui.pixiv.ui.upscale.IllustAiHelper
import ceui.pixiv.ui.upscale.ModelPickerDialog
import ceui.pixiv.ui.upscale.RembgModelPickerDialog
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 插画详情页(feeds 框架版)。整页 = 一张异构瀑布流:顶部大图页 + header 区块(全 fullSpan)+
 * 相关作品瀑布流。列表 / 分页 / 空错态 / DiffUtil 归框架;chrome(toolbar / 悬浮下载收藏胶囊 /
 * 折叠胶囊 / 内联评论输入栏)浮在列表之上,由本 Fragment 直接管理。
 *
 * 顶部大图:每页是一个 [ArtworkPageItem](外层瀑布流回收),bind/recycle **委托**给本页持有的
 * 那一个 [IllustAdapter]/[CollapsibleIllustAdapter] 实例(见 [ensurePageAdapter] /
 * [ArtworkV3Fragment.artworkPageRenderer]),尺寸 / 折叠 / 取图规则与 legacy 逐字一致。
 *
 * 数据源见 [ArtworkV3FeedSource];下载 FAB / 收藏态归 [ArtworkV3ViewModel]。无下拉刷新
 *([refreshEnabled] = false)。
 */
class ArtworkV3Fragment : IllustFeedFragment(R.layout.fragment_artwork_v3) {

    private val illustId: Long by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt("illust_id").toLong()
    }

    override val feedViewModel by feedViewModels {
        // 零捕获:只把 id 读进局部值交给长命 VM 持有的数据源,不钉 Fragment。
        val id = requireArguments().getInt("illust_id").toLong()
        ArtworkV3FeedSource(id)
    }

    private val artworkViewModel by viewModels<ArtworkV3ViewModel> {
        viewModelFactory { initializer { ArtworkV3ViewModel(illustId) } }
    }

    /** 底部内联评论输入栏的 composer VM(独立于列表)。 */
    private val composer by viewModels<CommentsComposerViewModel> {
        viewModelFactory {
            initializer { CommentsComposerViewModel(CommentTarget(illustId, ObjectType.ILLUST)) }
        }
    }

    internal val palette: V3Palette by lazy(LazyThreadSafetyMode.NONE) { V3Palette.from(requireContext()) }

    // 顶部大图页共享的那一个 adapter(所有页 bind 都委托给它)。isGif 时不建(走 ugoira renderer)。
    private var pageAdapter: IllustAdapter? = null
    private lateinit var retryController: PageLoadRetryController
    private var aiHelper: IllustAiHelper? = null

    // chrome
    private var _chromeBind: FragmentArtworkV3Binding? = null
    private val chromeBind get() = checkNotNull(_chromeBind) { "view 尚未创建或已销毁" }

    // 悬浮下载/收藏胶囊的共享逻辑(与二级大图页共用),随视图创建/销毁
    private var _fabBarController: V3FabBarController? = null
    private val fabBarController get() = checkNotNull(_fabBarController) { "view 尚未创建或已销毁" }

    private var commentComposer: CommentComposerController? = null
    private var composerActive = false
    private var fabShown = true

    /** 整页屏蔽遮罩是否正盖着。盖着时底部胶囊一律收起，见 [setMuteMaskActive]。 */
    private var muteMaskActive = false

    private var sectionLoader: SectionLoader<ArtworkSection>? = null
    private var artistObservedUserId: Long = 0L
    private var muteObserved = false

    /** 详情面板展开态归 Fragment(而非 cell tag):滚走再滚回不会被重绑重置(对齐 legacy VH 字段)。 */
    internal var detailPanelExpanded = true

    /** 超长简介展开态(#965):同上归 Fragment,默认折叠。 */
    internal var descExpanded = false

    /**
     * 一键跳评论(#970)落点后是否还在钉基线:首跳时评论/作者作品/相关往往都还是加载态,
     * 视口下方内容不够,SGLM 修 end gap 会把评论块顶离 toolbar 基线;且各区块落地时序不定,
     * 补对一次不够——数据每变一次就补对一次(见 [alignCommentsIfPending]),直到下方内容
     * 足够(end gap 不会再动它)才算收敛。用户一拖动即作废,不抢用户的滚动。
     */
    private var commentsJumpRealign = false

    /** 解析好的完整简介(#965):折叠态显示的是截断文本,展开/重绑时从这里取回全文。 */
    internal var descFullCaption: CharSequence? = null

    // 关闭下拉刷新(详情页 feeds 版不支持)
    override val refreshEnabled: Boolean = false

    // ── 列表装配 ────────────────────────────────────────────────────────────

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(
            artworkPageRenderer(),
            artworkUgoiraRenderer(),
            heroRenderer(),
            seriesRenderer(),
            descRenderer(),
            statsRenderer(),
            tagsRenderer(),
            artistRenderer(),
            detailPanelRenderer(),
            commentsRenderer(),
            authorWorksRenderer(),
            relatedHeaderRenderer(),
            staggerIllustRenderer(),
        )
    }

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        // 带整行 header 的瀑布流:GAP_HANDLING_NONE 对齐 legacy(开 gap 策略回滚时重排跳动)
        return StaggeredManager(Shaft.sSettings.lineCount, RecyclerView.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
    }

    override fun onListReady(listView: RecyclerView) {
        val spanCount = Shaft.sSettings.lineCount.coerceAtLeast(1)
        // 相关作品瀑布流间距对齐外面的推荐插画流(SpacesItemDecoration 也是 8dp);列数跟随设置。
        listView.addItemDecoration(RelatedOnlySpaceDecoration(8.ppppx, spanCount))
        // header 区块(fullSpan)在 notifyItemChanged 时的默认变更动画会打乱 SGLM 的 fullSpan 追踪。
        listView.itemAnimator = null
        // 跳评论(#970)基线收敛:懒加载区块每次落地(change/insert)都可能触发 end-gap 修正,
        // 数据一变就再对一次。观察者随本次 install 的 adapter 一起活/一起丢,不需手动反注册。
        feedAdapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                scheduleCommentsRealign()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                scheduleCommentsRealign()
            }
        })
    }

    // 详情页首屏是大图 + header,不是瀑布流网格——瀑布流骨架图会误导。用居中转圈圈(对齐 legacy)。
    override fun onCreateSkeletonView(
        layoutManager: RecyclerView.LayoutManager,
    ): ceui.pixiv.feeds.FeedSkeletonView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _chromeBind = FragmentArtworkV3Binding.bind(view)
        _fabBarController = V3FabBarController(chromeBind.fabBar)
        sectionLoader = SectionLoader<ArtworkSection>(viewLifecycleOwner) { it.load(illustId, feedViewModel) }
        aiHelper = IllustAiHelper(this, chromeBind.root).also {
            it.restoreUpscaleIfRunning(illustId.toInt())
        }

        // 旋转 / 视图重建:feedViewModel 的列表存活(可能是展开态),但 pageAdapter 会重建为
        // 折叠态。二者不一致会出「p0 顶着展开胶囊、p1/p2 却已显示」的矛盾 UI。对齐 legacy(旋转即
        // 折叠):在任何页绑定前(此刻 uiState 尚未 render)把多出的页收回,保持与新 adapter 一致。
        ObjectPool.get<IllustsBean>(illustId).value?.let { illust ->
            if (CollapsibleIllustAdapter.shouldCollapse(illust.page_count)) {
                feedViewModel.removeItems { it is ArtworkPageItem && it.pageIndex > 0 }
            }
        }

        retryController = PageLoadRetryController(
            lifecycleOwner = viewLifecycleOwner,
            networkStateManager = requireNetworkStateManager(),
            urlAtIndex = { idx ->
                val illust = ObjectPool.get<IllustsBean>(illustId).value
                    ?: return@PageLoadRetryController null
                if (idx < 0 || idx >= illust.page_count) return@PageLoadRetryController null
                val resolution = if (Shaft.sSettings.isShowOriginalPreviewImage)
                    Params.IMAGE_RESOLUTION_ORIGINAL
                else
                    Params.IMAGE_RESOLUTION_LARGE
                IllustDownload.getUrl(illust, idx, resolution)
            },
            totalPages = { ObjectPool.get<IllustsBean>(illustId).value?.page_count ?: 0 },
            onSummaryChanged = { loaded, total, failed ->
                renderImageLoadStatusBanner(
                    chromeBind.pageStatusRow, chromeBind.pageStatusText, loaded, total, failed,
                )
            },
            onRetryAt = { idx ->
                val fa = feedAdapter ?: return@PageLoadRetryController
                val pos = fa.currentList.indexOfFirst {
                    it is ArtworkPageItem && it.pageIndex == idx
                }
                if (pos >= 0) fa.notifyItemChanged(pos)
            },
        )
        chromeBind.pageStatusRetry.setOnClickListener { retryController.retryAllFailed() }

        setupFabBar()
        setupNavBar()
        handleSystemInsets()
        setupComposer()

        // 隐藏 / 显示悬浮胶囊(滚动);用户主动拖动时作废还欠着的跳评论基线校正
        feedBinding.feedListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) hideFabBar() else if (dy < -8) showFabBar()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) commentsJumpRealign = false
            }
        })

        // 关注态:观察作者 UserBean,变更后重算 Artist 条目(关注切换只这条重绑)。
        // 顺带接住 caption 后台补拉的落地(见 ArtworkV3ViewModel.ensureTrustedCaption)。
        ObjectPool.get<IllustsBean>(illustId).observe(viewLifecycleOwner) { illust ->
            illust ?: return@observe
            syncDescSection(illust.caption, illust.title)
            attachMuteObserver(illust)
            val authorId = illust.user?.id?.toLong() ?: return@observe
            attachArtistFollowObserver(authorId)
        }
    }

    /**
     * 屏蔽遮罩(对齐经典 [ceui.lisa.fragments.FragmentIllust] 的 observeMuteStatus):作品或画师
     * 命中屏蔽记录时全屏盖住整页,给出取消屏蔽 / 离开入口。V3 原先只有菜单里的写库动作、没有任何
     * 消费方,「屏蔽这个作品」点完页面纹丝不动(#983)。
     *
     * 两路判定都直接来自 Room 的行,没有「本进程内临时可见」这种中间态:瀑布流里点一下打码卡
     * 就是取消屏蔽,那一行当场就删了,再点进详情自然不会被挡。所以这里只观察库,不必再掺
     * [IllustMuteStore] 的版本号。
     */
    private fun attachMuteObserver(illust: IllustsBean) {
        if (muteObserved) return
        muteObserved = true
        // 经典同款 userId ?: 0 兜底:user 缺失/解析失败(#592 web 兜底 bean 可能 id=0)时,
        // 作品屏蔽的观察也要照常接线,画师侧退化成恒 null。只接一次——按 userId 重接会留下
        // 两个 mediator 同时观察,二者发射顺序不定,旧 mediator 的过期值可能盖掉新值。
        val userId = illust.user?.userId ?: 0
        val dao = AppDatabase.getAppDatabase(requireContext()).searchDao()
        combineLatest(
            dao.getIllustMuteEntityByID(illust.id),
            dao.getUserMuteEntityByIDLiveData(userId),
        ).observe(viewLifecycleOwner) { (illustEntity, userEntity) ->
            val muted = illustEntity != null || userEntity != null
            chromeBind.abandonedFrame.isVisible = muted
            setMuteMaskActive(muted)
            // 整页遮罩不再是一块纯黑：糊掉的作品图 + spoiler 粒子（与瀑布流「屏蔽此作品」同款）。
            // 只在真要显示遮罩时贴图——本 observer 在**没被屏蔽**时也照常发射（那才是常态），
            // 无脑 bind 等于每开一个作品都白解码 + 白模糊一张图。bind 自身按 cacheKey 幂等，
            // 屏蔽期间的重复发射不会重发请求；粒子由 SpoilerParticleView 按可见性自行起停。
            if (muted) {
                chromeBind.abandonedSpoiler.bind(illustGlide, GlideUtil.getMediumImg(illust))
            }
            chromeBind.cancelMuteIllust.isVisible = illustEntity != null
            chromeBind.cancelMuteUser.isVisible = userEntity != null
            if (illustEntity != null) {
                chromeBind.cancelMuteIllust.setOnClick {
                    viewLifecycleOwner.lifecycleScope.launch {
                        it.showProgress()
                        delay(600L)
                        // 删库和内存名单一并交给 store（它无条件删这一行）：瀑布流卡片的遮罩
                        // 判定读的是内存名单，而自己 deleteMuteEntity 还会绕开 store 的单线程
                        // 写队列，和排队中的 insert 抢顺序把这行复活（见 MutedWorkStore 类注释）。
                        // 本页的整页遮罩由上面那条 LiveData 在行真正删掉后自行收起。
                        IllustMuteStore.setMuted(illustEntity.id.toLong(), false)
                        it.hideProgress()
                    }
                }
            }
            if (userEntity != null) {
                chromeBind.cancelMuteUser.setOnClick {
                    viewLifecycleOwner.lifecycleScope.launch {
                        it.showProgress()
                        delay(600L)
                        dao.deleteMuteEntity(userEntity)
                        it.hideProgress()
                    }
                }
            }
            chromeBind.leave.setOnClick {
                viewLifecycleOwner.lifecycleScope.launch {
                    it.showProgress()
                    delay(600L)
                    requireActivity().finish()
                    it.hideProgress()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        artworkViewModel.onPageVisible()
        artworkViewModel.refreshDownloadFab()
    }

    override fun onPause() {
        artworkViewModel.pauseDownloadFab()
        super.onPause()
    }

    override fun onDestroyView() {
        commentComposer = null
        composerActive = false
        fabShown = true
        muteMaskActive = false
        // 跳评论的基线钉扎(#970)随视图作废:留着的话,视图重建(回退栈重显/旋转)后首次
        // render 的数据落地就会把新列表拽去评论区。
        commentsJumpRealign = false
        pageAdapter?.release()
        pageAdapter = null
        // 本视图生命周期内的一次性 guard 随视图销毁归零。否则同一 Fragment 实例视图重建(回退栈
        // 重显等)后,旧 viewLifecycleOwner 上的观察已随视图销毁,而 artistObservedUserId 还钉着
        // 旧值 → 关注更新不再触发。区块懒加载的去重集随 sectionLoader 一起丢弃、新视图重建。
        artistObservedUserId = 0L
        muteObserved = false
        sectionLoader = null
        // helper 持有本次 rootView；Fragment 留在返回栈时必须随 View 生命周期断开引用。
        aiHelper = null
        _fabBarController = null
        _chromeBind = null
        super.onDestroyView()
    }

    // ── 顶部大图 adapter(委托目标)────────────────────────────────────────────

    /** 首次绑定顶部页时懒建那一个共享 adapter(尺寸 / 折叠 / 取图逻辑全在它里面)。 */
    internal fun ensurePageAdapter(): IllustAdapter? {
        pageAdapter?.let { return it }
        if (view == null || !::retryController.isInitialized) return null
        val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return null
        if (illust.isGif()) return null // ugoira 走自己的 renderer
        val maxHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        val activity = requireActivity()
        val adapter: IllustAdapter = if (CollapsibleIllustAdapter.shouldCollapse(illust.page_count)) {
            val collapsible = CollapsibleIllustAdapter(
                activity, this, illust, maxHeight, artworkViewModel.forceOriginalPreview,
                onComicReaderClick = { openComicReader() },
                onExpandedChanged = { expanded -> onPagesExpandedChanged(expanded) },
            )
            // 悬浮「收起」胶囊点击 → 折叠(collapse() 触发 onExpandedChanged(false) → 收回页 + 回顶 + 藏胶囊)
            chromeBind.collapsePill.setOnClickListener { collapsible.collapse() }
            collapsible
        } else {
            object : IllustAdapter(activity, this, illust, maxHeight, artworkViewModel.forceOriginalPreview) {
                override fun onBindViewHolder(
                    holder: ViewHolder<RecyIllustDetailBinding>,
                    position: Int,
                ) {
                    super.onBindViewHolder(holder, position)
                    // 多 P 漫画(非折叠,即 2P):点图进漫画阅读器。必须同时判类型——只看
                    // page_count 会把 2P 插画也送进漫画阅读器(#961);3P+ 那条折叠分支的漫画入口
                    // 也是 type == "manga" 才出胶囊(见 CollapsibleIllustAdapter),两边保持一致。
                    // 必须挂在 itemView 而不是 illust 上:super 把「长按下载」挂的是 itemView,
                    // 而一个 clickable 却不 longClickable 的子 View 会把触摸整条吃掉——长按既到不了
                    // itemView 的 longClick,抬手时又照常 performClick,表现成「长按变成打开大图」(#957)。
                    // 同挂 itemView 后长按优先:performLongClick 返回 true 即抑制这次 click。
                    if ("manga" == illust.type && illust.page_count > 1) {
                        holder.itemView.setOnClickListener { openComicReader() }
                    }
                }
            }
        }
        adapter.setPageStatusListener { position, status ->
            retryController.reportStatus(position, status)
        }
        adapter.setLocalPagesChangedListener {
            // IllustAdapter 在 feeds 版只是 bind delegate，并未直接挂到 RecyclerView；它自己的
            // notifyDataSetChanged 无效。下载记录扫描命中后 bump tick，让外层 FeedAdapter 重绑。
            if (_chromeBind != null) {
                feedViewModel.updateItems<ArtworkPageItem> {
                    it.copy(rebindTick = it.rebindTick + 1)
                }
            }
        }
        retryController.refresh()
        // 每页真实宽高由 [ArtworkV3ViewModel.pageDimensions] 承载(VM 多 P 时拉一次网页 ajax)。
        // 这里把「已到」的值补给新建的 adapter;「后到」的值由 setup 处的观察者补上。
        artworkViewModel.pageDimensions.value?.let { adapter.seedPageDimensions(it) }
        pageAdapter = adapter
        return adapter
    }

    /**
     * “加载原图”：重建共享的顶层大图 adapter（isForceOriginal=true），
     * 并 bump 所有页面条目的 rebindTick，让外层 FeedAdapter 原地重绑。
     * 多 P 折叠作品保留展开态，避免点一下菜单就折回第一页。
     */
    private fun applyForceOriginal() {
        // 全局已经是原图模式时，当前 adapter 本来就按 original 加载。不要为了一个等价状态
        // 释放 / 重建 adapter、重扫本地下载并重绑全部已展开页面，避免无意义的闪动和 IO。
        if (Shaft.sSettings.isShowOriginalPreviewImage || artworkViewModel.forceOriginalPreview) return
        // 先置位再动 adapter：若首帧大图还没懒建（pageAdapter == null），
        // 后续 ensurePageAdapter() 也会带着这个开关创建，点击不丢。
        artworkViewModel.forceOriginalPreview = true
        val old = pageAdapter ?: return
        val wasExpanded = (old as? CollapsibleIllustAdapter)?.isExpanded == true
        old.release()
        pageAdapter = null
        val newAdapter = ensurePageAdapter() ?: return
        if (wasExpanded) {
            (newAdapter as? CollapsibleIllustAdapter)?.expand()
        }
        feedViewModel.updateItems<ArtworkPageItem> { it.copy(rebindTick = it.rebindTick + 1) }
    }

    private fun openComicReader() {
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画阅读")
            putExtra(Params.ILLUST_ID, illustId.toInt())
        })
    }

    /** 折叠 adapter 的展开态回调:驱动 feed 列表增删剩余页 + 浮动「收起」胶囊。 */
    private fun onPagesExpandedChanged(expanded: Boolean) {
        // 展开/收起本身就带滚动意图(收起要回顶),它触发的条目增删不能再被跳评论的
        // 基线钉扎(#970)拽回评论区——收起胶囊点击不经过 DRAGGING,得在这里主动作废。
        commentsJumpRealign = false
        val pill = chromeBind.collapsePill
        pill.animate().cancel()
        if (expanded) {
            pill.alpha = 0f
            pill.visibility = View.VISIBLE
            pill.animate().alpha(1f).setDuration(220).start()
            val pageCount = ObjectPool.get<IllustsBean>(illustId).value?.page_count ?: return
            feedViewModel.mutateItems { items ->
                val existing = items.filterIsInstance<ArtworkPageItem>().mapTo(HashSet()) { it.pageIndex }
                val toAdd = (1 until pageCount)
                    .filter { it !in existing }
                    .map { ArtworkPageItem(illustId, it) }
                if (toAdd.isEmpty()) return@mutateItems items
                val insertAt = items.indexOfLast { it is ArtworkPageItem } + 1
                items.subList(0, insertAt) + toAdd + items.subList(insertAt, items.size)
            }
        } else {
            pill.animate().alpha(0f).setDuration(220).withEndAction {
                pill.visibility = View.GONE
                pill.alpha = 1f
            }.start()
            // 一次编辑同时:删掉隐藏页 + bump 首页 rebindTick(强制 DiffUtil 原地重绑 p0,
            // 让「展开剩余 X 张」覆盖层重现)。不用 notifyItemChanged/post,避免与在飞的 diff 抢。
            feedViewModel.mutateItems { items ->
                items.mapNotNull { item ->
                    when {
                        item is ArtworkPageItem && item.pageIndex > 0 -> null
                        item is ArtworkPageItem && item.pageIndex == 0 ->
                            item.copy(rebindTick = item.rebindTick + 1)
                        else -> item
                    }
                }
            }
            val lm = feedBinding.feedListView.layoutManager
            if (lm is StaggeredGridLayoutManager) lm.scrollToPositionWithOffset(0, 0)
            else feedBinding.feedListView.scrollToPosition(0)
        }
    }

    // ── 懒加载区块 ───────────────────────────────────────────────────────────
    // 各区块「怎么拉 + 怎么把数据落回条目」全在 [ArtworkSection];这里只把 renderer 的
    // 「区块可见」信号转给 [SectionLoader](去重 + 单飞 + 视图作用域)。进页时区块还没
    // 滚到,一律不触发——池里已有完整 illust 时点进详情不会发任何多余请求。

    /** renderer holder attach 且数据仍空时调用:区块首次上屏触发一次懒加载。 */
    internal fun onSectionVisible(section: ArtworkSection) {
        sectionLoader?.onVisible(section)
    }

    /**
     * 联网后补拉加载失败的区块。区块的触发信号只有 holder 的 attach，用户如果就停在
     * 那一屏不动（评论/相关区块正在转圈时最常见），不补这一下就再也没有重试时机。
     */
    override fun onNetworkRestored() {
        super.onNetworkRestored()
        sectionLoader?.retryFailed()
    }

    /**
     * 把池里那条 bean 的 caption 同步到简介块。首屏是拿列表 bean 直接画的(不为了简介去阻塞,
     * 见 [ArtworkV3ViewModel] 的 caption 补拉),所以简介可能**晚到**、也可能一开始压根没这条目。
     *
     * 只做「补上 / 换内容」,不做「抹掉」:池会被各种精简来源覆盖(作者其他作品、相关作品列表都会
     * 合池),拿一次空 caption 去删已经显示出来的简介,就成了简介闪一下又没了。
     *
     * 位置锚在 [ArtworkTagsItem] 之前——对齐 [ArtworkV3FeedSource.buildArtworkHeaderItems] 的
     * 区块顺序(Hero /(Series)/ Artist / Desc / Tags / ...)。tags 块是无条件产出的,锚点稳定。
     * 没变化时原样返回同一个 list,[FeedViewModel.mutateItems] 据此判定 no-op,所以这个观察者
     * 每次因收藏 / 关注变更 fire 都是免费的。
     *
     * ⚠️ 这条插入可能与 fling 同帧落地(简介比首屏晚 ~0.5s,用户很可能正在滚)。它安全的前提是
     * [onListReady] 把 itemAnimator 关掉了 —— 没有 itemAnimator 就不跑 SGLM 的 predictive
     * 预布局,也就绕开了 [ceui.lisa.helper.StaggeredManager] 注释里那个「fling + 插入同帧」的
     * AOSP 越界。谁要把动画开回来,先想清楚这里。
     */
    private fun syncDescSection(caption: String?, title: String?) {
        if (caption.isNullOrEmpty() && title.isNullOrEmpty()) return
        val descCaption = caption.orEmpty()
        val descTitle = title.orEmpty()
        feedViewModel.mutateItems { items ->
            val at = items.indexOfFirst { it is ArtworkDescItem }
            if (at >= 0) {
                if ((items[at] as ArtworkDescItem).caption == descCaption &&
                    (items[at] as ArtworkDescItem).title == descTitle
                ) {
                    items
                } else {
                    items.toMutableList().apply { this[at] = ArtworkDescItem(descCaption, descTitle) }
                }
            } else {
                val anchor = items.indexOfFirst { it is ArtworkTagsItem }
                if (anchor < 0) {
                    items // header 还没建出来(首屏仍在飞),等下一次 fire
                } else {
                    Timber.tag(ARTWORK_LAZY_TAG)
                        .d("简介块后台补入 illustId=%d len=%d", illustId, descCaption.length)
                    items.subList(0, anchor) + ArtworkDescItem(descCaption, descTitle) +
                            items.subList(anchor, items.size)
                }
            }
        }
    }

    /**
     * 收起超长简介后把简介块拉回视口顶部(#965)。收起按钮在简介**末尾**,长简介收起时
     * 视口锚点还停在原来的绝对偏移,块一缩几千像素,画面就跳到更下面的区块去了;
     * 简介顶部仍在屏内(短简介)时无此问题,不动。
     */
    internal fun scrollDescBackIntoView(itemView: View) {
        if (itemView.top >= 0) return
        val rv = feedBinding.feedListView
        val pos = rv.getChildAdapterPosition(itemView)
        if (pos == RecyclerView.NO_POSITION) return
        val lm = rv.layoutManager
        if (lm is StaggeredGridLayoutManager) lm.scrollToPositionWithOffset(pos, 0)
        else rv.scrollToPosition(pos)
    }

    /**
     * 一键跳到评论预览区(#970)。评论条目随首屏 header 一并产出(见
     * [ArtworkV3FeedSource.buildArtworkHeaderItems]),首屏还在飞时 pos 找不到,静默不动。
     * offset 用悬浮顶栏实际底缘,让区块落在 toolbar 之下而不是被它盖住;落位后区块 attach
     * 自然触发评论懒加载。
     */
    private fun scrollToCommentsSection() {
        val fa = feedAdapter ?: return
        val pos = fa.currentList.indexOfFirst { it is ArtworkCommentsItem }
        if (pos < 0) return
        val lm = feedBinding.feedListView.layoutManager
        if (lm is StaggeredGridLayoutManager) {
            lm.scrollToPositionWithOffset(pos, chromeBind.topOverlayColumn.bottom)
        } else {
            feedBinding.feedListView.scrollToPosition(pos)
        }
        commentsJumpRealign = true
    }

    /**
     * 数据变更后把基线校正排到下一帧(布局落定后再量);flag 不亮时零开销。
     * ⚠️ 必须先验视图还活着再碰 feedBinding:FeedAdapter 的 diff 是异步的,在飞的一次
     * submitList 可能在 onDestroyView 之后才派发到旧 adapter 的 observer。
     */
    private fun scheduleCommentsRealign() {
        if (!commentsJumpRealign || _chromeBind == null) return
        feedBinding.feedListView.post { alignCommentsIfPending() }
    }

    /**
     * 基线收敛一步:评论块顶不在 [FragmentArtworkV3Binding.topOverlayColumn] 底缘就再锚一次;
     * 已在基线上且列表还能继续下滚(下方内容已够,end-gap 修正不会再动它)才算收敛完毕。
     */
    private fun alignCommentsIfPending() {
        if (!commentsJumpRealign || _chromeBind == null) return
        val fa = feedAdapter ?: return
        val pos = fa.currentList.indexOfFirst { it is ArtworkCommentsItem }
        if (pos < 0) return
        val rv = feedBinding.feedListView
        val target = chromeBind.topOverlayColumn.bottom
        val vh = rv.findViewHolderForAdapterPosition(pos)
        if (vh != null && vh.itemView.top == target) {
            if (rv.canScrollVertically(1)) commentsJumpRealign = false
            return
        }
        (rv.layoutManager as? StaggeredGridLayoutManager)?.scrollToPositionWithOffset(pos, target)
    }

    private fun attachArtistFollowObserver(authorId: Long) {
        if (authorId <= 0L || authorId == artistObservedUserId) return
        artistObservedUserId = authorId
        // 关注了没：ObjectPool 的 UserBean。
        ObjectPool.get<ceui.lisa.models.UserBean>(authorId).observe(viewLifecycleOwner) {
            refreshArtistFollowItem()
        }
        // 怎么关的：FollowVisibility。两条渠道缺一不可 —— 画师主页拿 user/follow/detail 补上
        // 「原来是私密关注」时 is_followed 一个字节都没变，上面那条不会响（issue #997 追加反馈）。
        FollowVisibility.changes.observe(viewLifecycleOwner) { changed ->
            if (changed == authorId) refreshArtistFollowItem()
        }
    }

    private fun refreshArtistFollowItem() {
        feedViewModel.updateItems<ArtworkArtistItem> { item ->
            val followed = ArtworkArtistItem.resolveIsFollowed(item.illust)
            val private = ArtworkArtistItem.resolvePrivateFollow(item.illust)
            if (item.isFollowed == followed && item.isPrivateFollow == private) {
                item
            } else {
                ArtworkArtistItem(item.illust, followed, private)
            }
        }
    }

    // ── 悬浮下载 / 收藏胶囊 ─────────────────────────────────────────────────────

    private fun setupFabBar() {
        fabBarController.applyPalette(palette)

        // 一键跳转评论区(#970):默认关,设置「看图与详情」里手动打开。
        if (Shaft.sSettings.isArtworkV3ShowCommentJumpFab) {
            fabBarController.setCommentJumpVisible(true)
            chromeBind.fabBar.fabComment.setOnClick { scrollToCommentsSection() }
            // 长按直达完整评论区(#1009),与下载/收藏 FAB 的长按增强同一套习惯。
            chromeBind.fabBar.fabComment.setOnLongClickListener {
                val intent = Intent(requireContext(), TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                intent.putExtra(Params.ILLUST_ID, illustId.toInt())
                startActivity(intent)
                true
            }
        }

        artworkViewModel.isBookmarked.observe(viewLifecycleOwner) { bookmarked ->
            fabBarController.setBookmarked(bookmarked)
        }
        artworkViewModel.downloadFabState.observe(viewLifecycleOwner) { state ->
            if (view == null) return@observe
            fabBarController.renderDownload(state)
        }
        // 网页 ajax 的每页真实宽高到达 → 喂给顶部大图 adapter,预置各页展示 ratio(下载前就摆准高度)。
        // adapter 懒建:值先到就由 ensurePageAdapter 补,adapter 先建就由这里补——两序都覆盖。
        artworkViewModel.pageDimensions.observe(viewLifecycleOwner) { dims ->
            pageAdapter?.seedPageDimensions(dims)
        }
    }

    /**
     * 整页遮罩盖上 / 揭掉时同步底部胶囊（下载 / 收藏 / 评论）。
     *
     * 遮罩本该盖住胶囊——`fab_bar` 在 `abandoned_frame` **之前**声明。但 view_v3_fab_bar 的根
     * 带 `android:elevation="12dp"`，遮罩是 0，而同一个父容器**先按 Z 排序、再按声明顺序**画，
     * 于是胶囊浮在糊掉的图上：屏蔽了的作品照样能一键收藏、下载、跳评论，屏蔽等于只糊了张图。
     * 别改成给遮罩提 elevation —— 那只挡住「看见」，胶囊仍在底下响应点击。
     *
     * 用一个状态位挡在 [showFabBar] 里，而不是就地 hide 一次：列表滚动监听会在上滑时把胶囊
     * 放回来（`onScrolled` → [showFabBar]），遮罩底下的列表虽然点不到、fling 惯性和程序滚动
     * 仍会走那条回调，单靠一次 hide 挡不住。
     *
     * 幂等（值没变直接返回）是必须的：本 observer 在**没被屏蔽**时也照常发射，每次都调
     * [showFabBar] 会把用户下滑收起的胶囊硬顶回来。
     */
    private fun setMuteMaskActive(active: Boolean) {
        if (muteMaskActive == active) return
        muteMaskActive = active
        if (active) {
            hideFabBar(immediate = true)
        } else {
            showFabBar()
        }
    }

    private fun hideFabBar(immediate: Boolean = false) {
        if (!fabShown && !immediate) return
        fabShown = false
        val fabBar = chromeBind.fabBar.root
        fabBar.animate().cancel()
        val hiddenTranslation = fabBar.height + 100f
        if (immediate) {
            fabBar.translationY = hiddenTranslation
            fabBar.alpha = 0f
            fabBar.visibility = View.INVISIBLE
        } else {
            fabBar.visibility = View.VISIBLE
            fabBar.animate()
                .translationY(hiddenTranslation)
                .alpha(0f)
                .setDuration(FAB_ANIMATION_DURATION_MS)
                .withEndAction { if (!fabShown) fabBar.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun showFabBar() {
        if (composerActive) return // 内联输入栏浮着时不放回胶囊
        if (muteMaskActive) return // 整页屏蔽遮罩盖着时同理，见 setMuteMaskActive
        if (fabShown) return
        fabShown = true
        val fabBar = chromeBind.fabBar.root
        fabBar.animate().cancel()
        fabBar.visibility = View.VISIBLE
        fabBar.animate().translationY(0f).alpha(1f).setDuration(FAB_ANIMATION_DURATION_MS).start()
    }

    // ── 底部内联评论输入栏 ─────────────────────────────────────────────────────

    private fun setupComposer() {
        commentComposer = CommentComposerController.attach(
            fragment = this,
            view = chromeBind.commentComposer,
            panelRoot = chromeBind.composerRoot,
            panelContentView = feedBinding.feedListView,
            palette = palette,
            presentation = CommentComposerPresentation.ON_DEMAND_OVERLAY,
            composer = composer,
            onSent = ::applySentComment,
            onPanelStateChanged = ::onComposerStateChanged,
            onPanelDismissStarted = { closingState ->
                if (closingState == PanelState.KEYBOARD) {
                    chromeBind.composerRoot.background = null
                }
            },
            onPanelDismissCancelled = ::onComposerStateChanged,
        )
    }

    /** 评论区「留下你的评论吧」入口(由 commentsRenderer 调)。 */
    internal fun showComposer() {
        chromeBind.composerRoot.setBackgroundColor(requireContext().getColor(R.color.v3_bg))
        if (composerActive) {
            commentComposer?.showKeyboard()
            return
        }
        composerActive = true
        hideFabBar(immediate = true)
        commentComposer?.showKeyboard()
    }

    private fun hideComposerBar() {
        composerActive = false
        commentComposer?.hide()
        chromeBind.composerRoot.background = null
        showFabBar()
    }

    private fun onComposerStateChanged(state: PanelState) {
        if (view == null) return // 面板/IME 回调若在视图销毁后到达,别碰 chromeBind(与 renderDownloadFab 对齐)
        if (state == PanelState.NONE) {
            val imeUp = ViewCompat.getRootWindowInsets(chromeBind.composerRoot)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!imeUp && commentComposer?.isEmpty == true) {
                hideComposerBar()
            } else if (!imeUp) {
                chromeBind.composerRoot.background = null
            }
        } else {
            if (state == PanelState.KEYBOARD) {
                chromeBind.composerRoot.setBackgroundColor(requireContext().getColor(R.color.v3_bg))
            } else {
                chromeBind.composerRoot.background = null
            }
            hideFabBar()
        }
    }

    private fun applySentComment(result: SentComment) {
        val (parentCommentId, comment) = result
        // 内联只发顶层评论;插到预览区最前
        if (parentCommentId <= 0L) {
            feedViewModel.updateItems<ArtworkCommentsItem> { it.prepend(comment) }
        }
        commentComposer?.dismiss()
    }

    // ── inset ──────────────────────────────────────────────────────────────

    private fun handleSystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(chromeBind.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top, v.paddingRight, v.paddingBottom)
            windowInsets
        }
        // 胶囊底距(导航栏 inset + 24dp)与二级大图页共用同一套逻辑,保证两页落点一致
        fabBarController.attachBottomInsetMargin()
        // 列表铺到屏幕最底,底 padding = navBar inset 让末条停在导航栏之上(clipToPadding=false 已设)
        val listView = feedBinding.feedListView
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, insets.bottom)
            windowInsets
        }
        // 折叠「收起」胶囊钉在顶栏(toolbar + 可选重试横幅)之下(见 #881)
        val pillGap = 8.ppppx
        chromeBind.topOverlayColumn.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom == oldBottom || _chromeBind == null) return@addOnLayoutChangeListener
            val pill = chromeBind.collapsePill
            val lp = pill.layoutParams as FrameLayout.LayoutParams
            val target = bottom + pillGap
            if (lp.topMargin != target) {
                lp.topMargin = target
                pill.layoutParams = lp
            }
        }
    }

    // ── toolbar / 悬浮胶囊点击 / more 菜单 ──────────────────────────────────────

    private fun setupNavBar() {
        chromeBind.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        // 下载 / 收藏顺序偏好
        fabBarController.applyDownloadOrderPreference()

        chromeBind.fabBar.fabDownloadContainer.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            artworkViewModel.triggerDownload()
            if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isIs_bookmarked) {
                fabBarController.setBookmarked(true)
                PixivOperate.postLikeDefaultStarType(illust)
            }
        }
        chromeBind.fabBar.fabDownloadContainer.setOnLongClickListener {
            val illust = ObjectPool.get<IllustsBean>(illustId).value
                ?: return@setOnLongClickListener true
            val baseAct = requireActivity() as? ceui.lisa.activities.BaseActivity<*>
            val resNames = arrayOf(
                getString(R.string.resolution_original),
                getString(R.string.resolution_large),
                getString(R.string.resolution_medium),
                getString(R.string.resolution_square_medium),
            )
            val resValues = arrayOf(
                Params.IMAGE_RESOLUTION_ORIGINAL,
                Params.IMAGE_RESOLUTION_LARGE,
                Params.IMAGE_RESOLUTION_MEDIUM,
                Params.IMAGE_RESOLUTION_SQUARE_MEDIUM,
            )
            QMUIDialog.MenuDialogBuilder(requireContext())
                .addItems(resNames) { dialog, which ->
                    if (illust.page_count == 1) {
                        IllustDownload.downloadIllustFirstPageWithResolution(illust, resValues[which], baseAct)
                    } else {
                        IllustDownload.downloadIllustAllPagesWithResolution(illust, resValues[which], baseAct)
                    }
                    artworkViewModel.refreshDownloadFab()
                    dialog.dismiss()
                }
                .show()
            true
        }

        // Manager 下载完成广播 → 刷新 FAB(轮询期间不干扰)
        val downloadFinishReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // ViewPager 的前后缓存页也注册了 receiver，但它们仅 STARTED；只让当前 RESUMED
                // 页面查下载状态，避免一次完成广播唤醒三页 DB 探测。
                if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !artworkViewModel.isPollingProgress
                ) {
                    artworkViewModel.refreshDownloadFab()
                }
            }
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            downloadFinishReceiver, IntentFilter(Params.DOWNLOAD_FINISH),
        )
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                LocalBroadcastManager.getInstance(requireContext())
                    .unregisterReceiver(downloadFinishReceiver)
            }
        })

        chromeBind.fabBar.fabBookmark.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            val willBookmark = !illust.isIs_bookmarked
            // 乐观着色与权威渲染(isBookmarked observer)同走 controller,取同一个内容色,
            // 避免取消收藏当帧闪一帧错色(详见 V3FabBarController.setBookmarked)。
            fabBarController.setBookmarked(willBookmark)
            PixivOperate.postLikeDefaultStarType(illust)
            if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar) {
                IllustDownload.downloadIllustAllPages(illust)
            }
        }

        chromeBind.fabBar.fabBookmark.setOnLongClickListener {
            val illust = ObjectPool.get<IllustsBean>(illustId).value
                ?: return@setOnLongClickListener true
            SelectTagBottomSheet.show(
                this, illust.id, Params.TYPE_ILLUST, illust.tagNames,
            )
            true
        }

        chromeBind.navMore.setOnClick { showMoreMenu() }
    }

    private fun showMoreMenu() {
        val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return
        showV3Menu {
            item(getString(R.string.share), R.drawable.ic_share_black_24dp) {
                object : ShareIllust(requireContext(), illust) {
                    override fun onPrepare() {}
                }.execute()
            }
            item(getString(R.string.string_454), R.drawable.ic_share_black_24dp) {
                shareFirstImage(illust)
            }
            item(getString(R.string.string_355_2), R.drawable.ic_baseline_launch_24) {
                Common.copy(requireContext(), ShareIllust.URL_Head + illust.id)
            }
            item(getString(R.string.string_1), R.drawable.ic_baseline_settings_24) {
                MuteTagSheet.show(childFragmentManager, illust.tags, illust.user)
            }
            item(getString(R.string.string_355), R.drawable.ic_visibility_off_black_24dp) {
                PixivOperate.muteIllust(illust)
            }
            item(getString(R.string.flag_post), R.drawable.ic_baseline_flag_24) {
                val intent = Intent(requireContext(), TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "举报插画")
                intent.putExtra(ceui.loxia.flag.FlagDescFragment.FlagObjectIdKey, illust.id.toLong())
                intent.putExtra(
                    ceui.loxia.flag.FlagDescFragment.FlagObjectTypeKey,
                    ceui.lisa.models.ObjectSpec.POST,
                )
                startActivity(intent)
            }
            if (!illust.isGif) {
                item(getString(R.string.string_0), R.drawable.ic_remove_red_eye_black_24dp) {
                    applyForceOriginal()
                }
                item(getString(R.string.string_ai_upscale), R.drawable.ic_upscale_add_photo) {
                    ModelPickerDialog.pickOrUseDefault(childFragmentManager) { model ->
                        aiHelper?.performUpscale(illust, model)
                    }
                }
                item(getString(R.string.string_ai_rembg), R.drawable.ic_baseline_filter_24) {
                    RembgModelPickerDialog.pickOrUseDefault(childFragmentManager) { model ->
                        aiHelper?.performRembg(illust, model)
                    }
                }
            }
            if (Dev.showPlazaShareInArtwork) {
                item(getString(R.string.plaza_share_illust_to_plaza), R.drawable.ic_plaza_forum_24) {
                    val intent = Intent(requireContext(), TemplateActivity::class.java)
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "发帖")
                    intent.putExtra(
                        ceui.pixiv.plaza.ui.PlazaComposeFragment.ARG_PREFILL_ILLUST_ID,
                        illust.id.toLong(),
                    )
                    startActivity(intent)
                }
            }
        }
    }

    /**
     * 只给非 fullSpan 条目(相关卡片)加间距;顶部大图页 + header 区块(fullSpan)零 offset。
     * 对齐 legacy 的 RelatedOnlySpaceDecoration。
     */
    private class RelatedOnlySpaceDecoration(
        private val space: Int,
        private val spanCount: Int,
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val lp = view.layoutParams
            if (lp !is StaggeredGridLayoutManager.LayoutParams || lp.isFullSpan) return
            outRect.bottom = space
            val spanIndex = lp.spanIndex
            outRect.left = if (spanIndex == 0) space else space / 2
            outRect.right = if (spanIndex == spanCount - 1) space else space / 2
        }
    }

    companion object {
        private const val FAB_ANIMATION_DURATION_MS = 200L

        @JvmStatic
        fun newInstance(illustId: Int): ArtworkV3Fragment {
            return ArtworkV3Fragment().apply {
                arguments = Bundle().apply { putInt("illust_id", illustId) }
            }
        }

        @JvmStatic
        fun newInstance(illustId: Long): ArtworkV3Fragment = newInstance(illustId.toInt())
    }
}
