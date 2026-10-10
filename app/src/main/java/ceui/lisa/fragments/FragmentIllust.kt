package ceui.lisa.fragments

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import ceui.pixiv.utils.playToggleHaptic
import kotlinx.coroutines.launch
import android.os.Bundle
import android.os.Handler
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.ViewStub
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.followedLabelRes
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.adapters.AbstractIllustAdapter
import ceui.lisa.adapters.IllustAdapter
import ceui.pixiv.actions.FollowVisibility
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.ui.detail.ArtworkThumbsSheet
import ceui.pixiv.ui.detail.TagEditSheet
import ceui.pixiv.ui.detail.UgoiraPlayerAdapter
import ceui.pixiv.ui.detail.ViewerPageLink
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentIllustBinding
import ceui.pixiv.ui.muted.MuteTagSheet
import ceui.lisa.download.IllustDownload
import ceui.pixiv.api.model.Illust
import ceui.lisa.models.ObjectSpec
import ceui.lisa.notification.CallBackReceiver
import ceui.lisa.utils.Common
import ceui.lisa.utils.SystemBarMetrics
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.lisa.utils.ShareIllust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.communication.StateEntry
import ceui.pixiv.communication.android.collectIn
import ceui.pixiv.download.DownloadRecordStateSource
import ceui.pixiv.ui.synonym.SynonymMatchView
import ceui.pixiv.widget.SpoilerBlurView
import ceui.pixiv.widgets.ProgressTextButton
import ceui.pixiv.widgets.V3TagFlowView
import ceui.pixiv.utils.combineLatest
import ceui.pixiv.utils.toTagsBeans
import ceui.loxia.User
import ceui.pixiv.ui.flag.FlagDescFragment
import ceui.pixiv.snapshot.AutoSnapshotEngine
import ceui.pixiv.snapshot.AutoSnapshotRepository
import ceui.pixiv.snapshot.SnapshotManagerFragment
import ceui.pixiv.snapshot.SnapshotRepository
import ceui.pixiv.snapshot.SnapshotRuntimeCache
import ceui.pixiv.snapshot.SnapshotViewerData
import ceui.pixiv.snapshot.localizeIllust
import ceui.pixiv.snapshot.showSnapshotCreateDialog
import ceui.pixiv.ui.share.shareFirstImage
import ceui.pixiv.ui.share.saveArtworkPoster
import ceui.pixiv.ui.upscale.IllustAiHelper
import ceui.pixiv.utils.buildPinnedTagPreviewJson
import ceui.pixiv.utils.isHostStillResumed
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.utils.singleLineTitle

import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import ceui.pixiv.witstudio.dialog.WitDialog.CheckableDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.pixiv.ui.navigation.TemplateRoute

class FragmentIllust : BaseLazyFragment<FragmentIllustBinding>() {
    private var autoSnapshotVisit: AutoSnapshotEngine.ArtworkVisit? = null

    /**
     * 本页实例是否已经计过一次「进入」：切后台回来、横滑滑回都不重复计。
     * 过 onSaveInstanceState 带过旋屏重建 —— 否则旋几次就能凭空凑满「反复进入」阈值。
     */
    private var autoSnapshotEntered = false

    /**
     * 本页被横滑降级（onPause 时宿主仍 RESUMED）、还没被滑回来。这样的页视觉上已经离开，
     * 旋屏时也要评估 —— 否则它挂起着的那段停留只结算不评估，触发就丢了。
     */
    private var autoSnapshotDemoted = false

    private val safeArgs by lazy { IllustArgs(requireArguments()) }

    private val snapshotId: String? get() = arguments?.getString(SnapshotManagerFragment.ARG_SNAPSHOT_ID)
    private val snapshotIsAuto: Boolean
        get() = arguments?.getBoolean(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, false) ?: false
    private val isSnapshotMode: Boolean get() = snapshotId != null
    private var snapshotViewerData: SnapshotViewerData? = null
    private var snapshotBean: Illust? = null
    private var snapshotUser: User? = null

    private class IllustArgs(b: Bundle) {
        val illustId: Int = b.getInt("illust_id")
    }
    private val vm by viewModels<FragmentIllustViewModel> {
        FragmentIllustViewModel.Factory(safeArgs.illustId.toLong(), requireContext())
    }
    private var mReceiver: CallBackReceiver? = null
    private var recyHeight = 0
    private var aiHelper: IllustAiHelper? = null

    // 两块「首帧必然不可见」的 chrome 的懒 inflate 状态（与 ArtworkV3Fragment 同一套）：
    // AI 覆盖层（11 个 View）只在跑 AI 任务时用；屏蔽遮罩（≈8 个 View，含 SpoilerBlurView 的
    // 两个自定义 View）只在作品被屏蔽时才亮。而本页同样跑在 VActivity 的 ViewPager 里
    // （offscreenPageLimit=1），一次点击至少建两页 —— 所以这两块也要推迟到真要用的那一刻。
    private var abandonedFrameReady = false
    private var aiOverlayReady = false

    private val abandonedFrameView: View
        get() = ViewCompat.requireViewById(baseBind.root, R.id.abandoned_frame)
    private val abandonedSpoilerView: SpoilerBlurView
        get() = ViewCompat.requireViewById(baseBind.root, R.id.abandoned_spoiler)
    private val cancelMuteIllustView: ProgressTextButton
        get() = ViewCompat.requireViewById(baseBind.root, R.id.cancel_mute_illust)
    private val cancelMuteUserView: ProgressTextButton
        get() = ViewCompat.requireViewById(baseBind.root, R.id.cancel_mute_user)

    // 注意：DataBinding 不给 <ViewStub> 生成强类型字段（本布局是 DataBinding 版，字段被生成成
    // View），所以这两个 stub 得自己从 root 取。inflate 之后 stub 会从父容器移除，但两个 ensure*
    // 都有 ready 守卫，只会取一次。
    private val abandonedFrameStub: ViewStub
        get() = ViewCompat.requireViewById(baseBind.root, R.id.abandoned_frame_stub)
    private val aiOverlayStub: ViewStub
        get() = ViewCompat.requireViewById(baseBind.root, R.id.ai_overlay_stub)

    // 信息区（second_linear：统计 / 标签 / 同义词 / 简介 / ID / 尺寸，≈21 个 View）的懒 inflate。
    // 它和上面两块不一样 —— 不是「常隐」，而是**整块都在首屏之下**。只对非当前页延后一个消息。
    private var infoSectionReady = false

    private val secondLinearStub: ViewStub
        get() = ViewCompat.requireViewById(baseBind.root, R.id.second_linear_stub)

    /** 信息区还没 inflate 时返回 null —— [setupBottomSheet] 的 layout 回调必须容忍这一瞬间。 */
    private val secondLinearView: View?
        get() = baseBind.root.findViewById(R.id.second_linear)

    // 信息区（ViewStub）里的 view：只在 setupInfoSection() 跑过之后才存在。
    private val descriptionView: TextView
        get() = ViewCompat.requireViewById(baseBind.root, R.id.description)
    private val illustIdView: TextView
        get() = ViewCompat.requireViewById(baseBind.root, R.id.illust_id)
    private val userIdView: TextView
        get() = ViewCompat.requireViewById(baseBind.root, R.id.user_id)
    private val illustSizeView: TextView
        get() = ViewCompat.requireViewById(baseBind.root, R.id.illust_size)
    private val totalViewText: TextView
        get() = ViewCompat.requireViewById(baseBind.root, R.id.total_view)
    private val totalLikeText: TextView
        get() = ViewCompat.requireViewById(baseBind.root, R.id.total_like)
    private val illustLikeView: View
        get() = ViewCompat.requireViewById(baseBind.root, R.id.illust_like)
    private val illustTagView: V3TagFlowView
        get() = ViewCompat.requireViewById(baseBind.root, R.id.illust_tag)
    private val synonymMatchView: SynonymMatchView
        get() = ViewCompat.requireViewById(baseBind.root, R.id.synonym_match)

    // ObjectPool 的每一次发射都会重跑一遍 updateIllust(收藏回流是最常见的一次),下面这组状态用来
    // 让「重建图片区」「重建标签区」「挂 sheet callback」「发头像 Glide 请求」这几件带视觉副作用的
    // 事只在真需要时做——否则收藏一下整页就闪一次(#962)。跟着 view 走,onDestroyView 里清掉。
    private var renderedImageSignature: String? = null
    private var renderedSynonymTags: List<Pair<String?, String?>>? = null
    private var renderedSynonymEnabled = false

    /**
     * 溢出菜单的点击回调读这个字段，而不是让闭包捕获 `illust` —— 见 [handleMenuItem]。
     * 每次 `updateIllust` 都会刷新它，所以菜单不必为了「换 bean」而重建。
     */
    private var menuIllust: Illust? = null

    /** 菜单「形状」（快照 / 动图 / 页数）。没变就不重建 —— 11 项菜单的 inflate 不便宜。 */
    private var renderedMenuShape: String? = null
    private var bottomSheetCallbackAttached = false

    /**
     * 本次视图生命周期里是否已经纠正过抽屉的 peek 高度。
     *
     * 第一次必须**不带动画**：布局里 `app:behavior_peekHeight` 写死 180dp，而真实值 `bottomBar.height`
     * 比它矮 —— 信息区就位时，多出来的那一段正好露出信息区顶部（浏览 / 收藏数那一行）。
     * 带动画就会「先露一帧再滑下去」；不带就直接落到正确位置，用户看不到那一帧。
     * 之后的重复纠正（简介补拉到货、内容长高）照常带动画。
     */
    private var bottomSheetPeekApplied = false
    private var pageProgressPillAttached = false

    /**
     * 本次视图生命周期里，视口是否被 [ViewerPageLink]（二级大图翻页）挪动过。
     *
     * 用来区分「用户翻回进场页」该不该跟着回：没挪过就说明他根本没翻走，别去动他自己滚出来的位置。
     */
    private var viewerViewportSynced = false

    /** 大图最后要求本页停在第几页（-1 = 没要求过）。退出归位要靠它定位「当前页那一格」。 */
    private var viewerSyncedPage = -1

    /**
     * [ViewerPageLink] 用的作品键。
     *
     * 快照详情页没有 `illust_id` 参数，取快照里那份 illust 的 id 才能和大图侧广播的对上；
     * 还没加载出来时给 0，匹配自然落空（安全降级）。
     */
    private val viewerLinkIllustId: Long
        get() = if (isSnapshotMode) snapshotBean?.id ?: 0L else safeArgs.illustId.toLong()
    private val pageProgressLocation = IntArray(2)
    /** 页码浮标当前指着哪一页(0 基);浮标不在场时为 -1。长按预览拿它当高亮/初始滚动位。 */
    private var pageProgressIndex = -1
    private var sheetDeltaY = 0
    private var loadedAvatarUrl: String? = null

    public override fun initLayout() {
        mLayoutID = R.layout.fragment_illust
    }

    // fragment_illust.xml 已从 DataBinding 改为 ViewBinding（布局里没有任何 DataBinding 表达式，
    // <data> 里那个 LiveData<User> variable 从没被引用、代码里也从没 set 过）。DataBindingUtil.inflate
    // 对非 <layout> 根的布局返回 null，所以这里必须自己走 ViewBinding 的 inflate。
    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentIllustBinding =
        FragmentIllustBinding.inflate(inflater, container, false)

    override fun bindExisting(root: View): FragmentIllustBinding =
        FragmentIllustBinding.bind(root)

    override fun initView() {
        // 导航栏占位要在快照 early-return 之前挂好,否则离线快照页底栏压在手势条上。
        applyNavigationBarInset()
        if (isSnapshotMode) {
            setupSnapshotView()
            return
        }
        val illustLiveData = ObjectPool.get<Illust>(safeArgs.illustId.toLong())
        illustLiveData.observe(viewLifecycleOwner) { illust ->
            updateIllust(illust)
        }
        vm.downloadState.collectIn(
            viewLifecycleOwner,
            minState = Lifecycle.State.RESUMED,
            onError = { Timber.tag(DownloadRecordStateSource.LOG_TAG).e(it, "subscription failed illustId=%d", safeArgs.illustId) },
        ) { state ->
            if (state is StateEntry.Value) {
                val label = if (state.value) R.string.string_337 else R.string.string_72
                baseBind.download.setText(label)
                Timber.tag(DownloadRecordStateSource.LOG_TAG).d(
                    "render illustId=%d downloaded=%s label=%s",
                    safeArgs.illustId, state.value, getString(label),
                )
            }
        }
        // 网页 ajax 的每页真实宽高到达 → 喂给当前大图 adapter,预置各页展示 ratio(下载前摆准高度)。
        // adapter 建得比数据晚就由这里补,数据比 adapter 晚就由建处 seed(见 IllustAdapter 建处)。
        vm.pageDimensions.observe(viewLifecycleOwner) { dims ->
            (baseBind.recyclerView.adapter as? IllustAdapter)?.seedPageDimensions(dims)
        }
        val userId = illustLiveData.value?.user?.id ?: return
        val userLiveData = ObjectPool.get<User>(userId)
        userLiveData.observe(viewLifecycleOwner) { user ->
            updateUser(user)
            Common.showLog("updateUser invoke ${user.is_followed}")
        }
        // 「怎么关的」不在 User 里，变化时上面那条不会响 —— 同 V3 详情页，见 FollowVisibility.changes。
        FollowVisibility.changes.observe(viewLifecycleOwner) { changed ->
            if (changed == userId) userLiveData.value?.let { updateUser(it) }
        }

        val illust = illustLiveData.value ?: return
        // 这里原来还有一句 `baseBind.user = userLiveData`（DataBinding 的 variable 赋值）。
        // 但布局里从来没有 `@{user...}` 表达式引用它 —— 那是个「设了没人看」的死绑定，
        // 唯一效果是每次进页多一次 requestRebind 空跑。布局已改 ViewBinding，这行随之删除。

        observeMuteStatus(illust)
    }

    private fun applyNavigationBarInset() {
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            if (insets.bottom > 0) {
                baseBind.bottomPlaceHolder.isVisible = true
                baseBind.bottomPlaceHolder.updateLayoutParams {
                    height = insets.bottom
                }
            } else {
                baseBind.bottomPlaceHolder.isVisible = false
            }
            windowInsets
        }
    }

    // ── 快照只读模式（实验）──────────────────────────────────────────────
    private fun setupSnapshotView() {
        val id = snapshotId ?: return
        val cached = SnapshotRuntimeCache.get(id)
        if (cached != null) {
            bindSnapshotView(cached)
            return
        }
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // 快照可能已被管理页删掉 / manifest 损坏 —— loadViewerData 会抛。
            // 裸 launch 里逃逸的异常直接崩进程,这里就地兜住:提示 + 关页。
            val loaded = try {
                withContext(Dispatchers.IO) {
                    if (snapshotIsAuto) {
                        AutoSnapshotRepository.loadAutoViewerData(appContext, id)
                    } else {
                        SnapshotRepository.loadViewerData(appContext, id)
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.w(e, "[Snapshot] open classic viewer failed, id=%s", id)
                Common.showToast(getString(R.string.snapshot_open_failed, e.message ?: ""))
                finish()
                return@launch
            }
            SnapshotRuntimeCache.put(id, loaded)
            bindSnapshotView(loaded)
        }
    }

    private fun bindSnapshotView(data: SnapshotViewerData) {
        snapshotViewerData = data
        snapshotBean = data.localizeIllust()
        snapshotUser = snapshotBean?.user
        // 独立快照数据通道：不写 ObjectPool，只使用本地字段驱动渲染。
        val bean = snapshotBean ?: return
        updateIllust(bean)
        bean.user?.let { updateUser(it) }
        applySnapshotReadOnlyOverrides()
    }

    private fun applySnapshotReadOnlyOverrides() {
        val data = snapshotViewerData ?: return
        baseBind.download.text = getString(R.string.snapshot_downloaded_label)
        baseBind.download.setOnClickListener(null)
        baseBind.download.setOnLongClickListener(null)

        baseBind.postLike.setOnClickListener { snapshotUnsupportedToast() }
        baseBind.postLike.setOnLongClickListener { snapshotUnsupportedToast(); true }
        // 信息区是 ViewStub。本函数在 bindSnapshotView 里紧跟 updateIllust 之后跑，通常已经建好；
        // 万一视图还没 RESUMED（会被延后一个消息），它建好时会自己补上这条拦截（见 setupInfoSection）。
        if (secondLinearView != null) {
            illustLikeView.setOnClickListener { snapshotUnsupportedToast() }
        }
        baseBind.follow.setOnClickListener { snapshotUnsupportedToast() }
        baseBind.unfollow.setOnClickListener { snapshotUnsupportedToast() }
        baseBind.relaIllustBrief.setOnClickListener { snapshotUnsupportedToast() }
        baseBind.userName.setOnClickListener { snapshotUnsupportedToast() }
        baseBind.userName.setOnLongClickListener { snapshotUnsupportedToast(); true }
        baseBind.related.setOnClickListener { snapshotUnsupportedToast() }

        baseBind.comment.setOnClickListener {
            if (data.comments != null) {
                openSnapshotComments()
            } else {
                Common.showToast(getString(R.string.snapshot_no_comments_toast))
            }
        }
    }

    private fun snapshotUnsupportedToast() {
        Common.showToast(getString(R.string.snapshot_unsupported_toast))
    }

    private fun openSnapshotComments() {
        val data = snapshotViewerData ?: return
        val intent = Intent(mContext, TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SNAPSHOT_COMMENTS.key)
        intent.putExtra("objectId", data.illust.id)
        intent.putExtra("objectArthurId", data.illust.user?.id ?: 0L)
        intent.putExtra("objectType", ceui.pixiv.api.model.ObjectType.ILLUST)
        intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_ID, snapshotId)
        intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, snapshotIsAuto)
        startActivity(intent)
    }

    private fun applySnapshotLocalPages(adapter: IllustAdapter) {
        val data = snapshotViewerData ?: return
        val pageCount = data.illust.page_count.coerceAtLeast(1)
        for (i in 0 until pageCount) {
            data.pageFile(i)?.let { file -> adapter.putLocalPageUri(i, Uri.fromFile(file)) }
        }
    }

    /**
     * 被屏蔽遮罩的懒 inflate（理由见字段处注释）。触发点 = 屏蔽 observer 第一次报「有屏蔽记录」。
     *
     * 「离开」按钮的接线也一起延迟 —— 它原来在 [updateIllust] 里无条件接，而按钮现在住在
     * ViewStub 的目标布局里，不 inflate 就没有这个 View。
     */
    private fun ensureAbandonedFrame() {
        if (abandonedFrameReady) return
        abandonedFrameReady = true
        abandonedFrameStub.inflate()
        ViewCompat.requireViewById<ProgressTextButton>(baseBind.root, R.id.leave).setOnClick {
            viewLifecycleOwner.lifecycleScope.launch {
                it.showProgress()
                delay(600L)
                requireActivity().finish()
                it.hideProgress()
            }
        }
    }

    /** AI 画质增强 / 智能抠图覆盖层的懒 inflate。触发点 = [IllustAiHelper] 各入口。 */
    private fun ensureAiOverlay() {
        if (aiOverlayReady) return
        aiOverlayReady = true
        aiOverlayStub.inflate()
    }

    private fun observeMuteStatus(illust: Illust) {

        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.getAppDatabase(requireContext()).searchDao()
            val muteIllust = withContext(Dispatchers.IO) {
                dao.getIllustMuteEntityByID(illust.id.toInt())
            }
            val muteUser = withContext(Dispatchers.IO) {
                dao.getUserMuteEntityByIDLiveData(illust.user?.id ?: 0L)
            }
            combineLatest(muteIllust, muteUser).observe(viewLifecycleOwner) {
                val illustEntity = it.first
                val userEntity = it.second
                if (illustEntity == null && userEntity == null) {
                    baseBind.contentFrame.isVisible = true
                    // 常态是「没被屏蔽」——那时遮罩从没被建过，连碰都不用碰（见 ensureAbandonedFrame）。
                    if (abandonedFrameReady) abandonedFrameView.isVisible = false
                } else {
                    baseBind.contentFrame.isVisible = false
                    // 遮罩子树首帧不建：只有被屏蔽的作品才亮，而它挂着 SpoilerBlurView 的两个自定义 View。
                    ensureAbandonedFrame()
                    abandonedFrameView.isVisible = true
                    // 整页遮罩不再是一块纯黑：糊掉的作品图 + spoiler 粒子。
                    // bind 幂等(同一封面不重发请求)，可以跟着 observer 每次发射照调。
                    abandonedSpoilerView.bind(Glide.with(this@FragmentIllust), GlideUtil.getMediumImg(illust))
                    cancelMuteIllustView.isVisible = illustEntity != null
                    cancelMuteUserView.isVisible = userEntity != null

                    if (illustEntity != null) {
                        cancelMuteIllustView.setOnClick {
                            viewLifecycleOwner.lifecycleScope.launch {
                                it.showProgress()
                                delay(600L)
                                // 同 ArtworkV3Fragment：删库和内存名单一并交给 store，
                                // 别自己 deleteMuteEntity 绕开它的单线程写队列
                                IllustMuteStore.setMuted(illustEntity.id.toLong(), false)
                                it.hideProgress()
                            }
                        }
                    }
                    if (userEntity != null) {
                        cancelMuteUserView.setOnClick {
                            viewLifecycleOwner.lifecycleScope.launch {
                                it.showProgress()
                                delay(600L)
                                dao.deleteMuteEntity(userEntity)
                                it.hideProgress()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateUser(user: User) {
        val userId = user.id
        if (user.is_followed == true) {
            baseBind.follow.isVisible = false
            baseBind.unfollow.isVisible = true
            baseBind.unfollow.text = getString(followedLabelRes(userId))
            baseBind.unfollow.setOnClick {
                playToggleHaptic(it, false)
                unfollowUser(it, userId)
            }
            baseBind.unfollow.setOnLongClickListener {
                PixivActions.switchFollowVisibility(userId)
                true
            }
        } else {
            baseBind.unfollow.isVisible = false
            baseBind.follow.isVisible = true
            baseBind.follow.setOnClick {
                playToggleHaptic(it, true)
                followUser(it, userId, PixivActions.defaultFollowRestrict())
            }
            baseBind.follow.setOnLongClickListener {
                followUser((it as ProgressTextButton), userId, Params.TYPE_PRIVATE)
                true
            }
        }
        baseBind.relaIllustBrief.setOnClick {
            val intent = Intent(mContext, UActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            startActivity(intent)
        }
        baseBind.userName.setOnClick {
            val intent = Intent(mContext, UActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            startActivity(intent)
        }
        baseBind.userName.setOnLongClickListener {
            Common.copy(mContext, user.name)
            true
        }

        baseBind.userName.text = user.name
    }

    /**
     * 信息区（`second_linear`：统计 / 标签 / 同义词 / 简介 / ID / 尺寸，≈21 个 View）的装配。
     *
     * 从 [updateIllust] 拆出来，好让**非当前页**能把它延后一个消息：这一整块都在首屏之下
     * （首屏是 RecyclerView 的大图 + 底部条），而点中间那张卡时 pager 会一次实例化
     * cur-1 / cur / cur+1 三页（`offscreenPageLimit=1`）—— ×3 ≈ 63 个 View 是眼下最大的一笔。
     *
     * 当前页仍在同一帧做完（`onResume` 只给当前页），所以滑过去看到的信息区不会缺。
     * 底部 sheet 的几何是在 `coreLinear` 的 layout 回调里读 `secondLinear.height` 的，
     * 信息区延后 inflate 会让那次布局变化再触发一遍回调，自动纠正 —— 见 [setupBottomSheet]。
     */
    private fun setupInfoSection(illust: Illust) {
        if (!infoSectionReady) {
            infoSectionReady = true
            secondLinearStub.inflate()
        }
        setupTags(illust)
        setupInfo(illust)
        setupDescription(illust)
        setupStats(illust)
        // 「收藏数」入口原来接在 setupActionButtons() 里 —— 但那个 view 现在住在信息区，
        // 只能在 inflate 之后接。跟着信息区一起延后，判据一致。
        if (isSnapshotMode) {
            // 快照页只读：这个入口也拦掉（正常路径下 applySnapshotReadOnlyOverrides 会拦一次，
            // 这里兜的是「视图还没 RESUMED、信息区被延后」那一档）。
            illustLikeView.setOnClickListener { snapshotUnsupportedToast() }
        } else {
            illustLikeView.setOnClick {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(Params.CONTENT, illust)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.ILLUST_LIKERS.key)
                startActivity(intent)
            }
        }
    }

    private fun updateIllust(illust: Illust) {
        // 快照是「当时那一刻」的存档，在线可见性判断不该作用在它上面：Gson 默认丢弃 null 字段，
        // 精简来源的 bean 存进 illust.json 后 visible 会缺失 → 反序列化成 null → 一打开就
        // 提示「作品不存在」并自动关页。id 那条仍然保留(存档本身坏了才会命中)。
        if (illust.id == 0L || (!isSnapshotMode && illust.visible != true)) {
            Common.showToast(R.string.string_206)
            Handler().postDelayed({ finish() }, 1000)
            return
        }

        // 「离开」按钮的接线已随遮罩一起延迟到 ensureAbandonedFrame()（按钮现在住在 ViewStub 里）。

        setupTitle(illust)
        setupToolbarMenu(illust)
        attachPageProgressPill()
        setupLikeButton(illust)
        setupBottomSheet(illust)
        setupActionButtons(illust)
        setupDownloadButton(illust)
        loadUserAvatar(illust)
        // 作者行属于首屏，帧同步设 —— 它不能跟着信息区一起延后（见 setupAuthorRow）。
        setupAuthorRow(illust)
        // 信息区整块在首屏之下：当前页本帧做完，非当前页延后一个消息（见 setupInfoSection）。
        // 判据用视图生命周期 —— pager 只让当前页 RESUMED（BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT）。
        if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            setupInfoSection(illust)
        } else {
            // 非当前页：延后一个消息。
            //
            // ⚠️ 这里**不能**写 `val host = view ?: return` —— `BaseFragment.onCreateView()` 是在
            // onCreateView 里就调 `initView()` 的，而 `Fragment.getView()` 要等 onCreateView
            // **返回之后**才被赋值，所以那一刻 `view` 恒为 null，整段会被静默跳过。
            // 快照页正是这条路径（`bindSnapshotView` 在 initView 里同步跑），信息区永远建不出来
            // —— 表现就是「快照页内容空态」（抽屉 peek 也停在 0，整条抽屉都不出现）。
            // 改用 `baseBind.root`：它就是本页的根视图，post 会排在 attach 之后执行。
            val root = baseBind.root
            root.post { if (root === baseBind?.root) setupInfoSection(illust) }
        }
    }

    private fun setupTitle(illust: Illust) {
        if (!isSnapshotMode && illust.series != null && !TextUtils.isEmpty(illust.series.title)) {
            val clickableSpan: ClickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val intent = Intent(mContext, TemplateActivity::class.java)
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.MANGA_SERIES_DETAIL.key)
                    intent.putExtra(Params.MANGA_SERIES_ID, illust.series.id.toInt())
                    startActivity(intent)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = Common.resolveThemeAttribute(
                        mContext,
                        androidx.appcompat.R.attr.colorPrimary
                    )
                }
            }
            val seriesString = getString(R.string.string_229)
            val spannableString = SpannableString(
                String.format("@%s %s", seriesString, illust.title.singleLineTitle())
            )
            spannableString.setSpan(
                clickableSpan, 0, seriesString.length + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            baseBind.title.movementMethod = LinkMovementMethod.getInstance()
            baseBind.title.text = spannableString
        } else {
            baseBind.title.text = illust.title.singleLineTitle()
        }
    }

    private fun setupToolbarMenu(illust: Illust) {
        // 菜单回调读这个字段，而不是让闭包捕获 illust —— 见 [handleMenuItem]。
        menuIllust = illust
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }

        // 菜单「形状」（快照 / 动图 / 页数）没变就不重建。
        // 菜单有 11 项，而 updateIllust 每次 ObjectPool 发射都会重跑（收藏回流最常见）。
        val shape = "${isSnapshotMode}|${illust.isGif()}|${illust.page_count}"
        if (shape == renderedMenuShape) return
        renderedMenuShape = shape

        baseBind.toolbar.menu?.clear()
        baseBind.toolbar.inflateMenu(R.menu.share)
        if (!isSnapshotMode && !illust.isGif() && illust.page_count == 1) {
            baseBind.toolbar.menu.add(R.string.comic_reader_enter_illust).setOnMenuItemClickListener {
                startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMIC_READER.key)
                    putExtra(Params.ILLUST_ID, illust.id)
                })
                true
            }
        }
        if (isSnapshotMode) {
            // 快照只读：溢出菜单只保留复制链接 / 分享首图 / 画质增强 / 智能抠图。
            intArrayOf(
                R.id.action_share,
                R.id.action_dislike,
                R.id.action_mute_illust,
                R.id.action_flag_illust,
                R.id.action_show_original,
                R.id.action_snapshot,
            ).forEach { id -> baseBind.toolbar.menu?.findItem(id)?.isVisible = false }
        }
        // 动图(ugoira)的 original 是 zip,加载原图/画质增强/抠图都没法处理,隐藏这几项(对齐 V3 详情页)。
        if (illust.isGif()) {
            baseBind.toolbar.menu?.findItem(R.id.action_ai_upscale)?.isVisible = false
            baseBind.toolbar.menu?.findItem(R.id.action_ai_rembg)?.isVisible = false
            baseBind.toolbar.menu?.findItem(R.id.action_show_original)?.isVisible = false
            // 动图的 original 是 zip,SnapshotGenerator 一进门就拒;别把注定失败的入口摆出来。
            baseBind.toolbar.menu?.findItem(R.id.action_snapshot)?.isVisible = false
        }
        baseBind.toolbar.setOnMenuItemClickListener { menuItem ->
            // 读字段而不是闭包捕获的 illust —— 菜单只按「形状」建一次，回调永远拿最新 bean。
            menuIllust?.let { handleMenuItem(menuItem.itemId, it) } ?: false
        }
    }

    /**
     * 溢出菜单的点击处理。
     *
     * 单独拆出来，是为了让 [setupToolbarMenu] 不必为了「换 bean」而重建整个菜单 —— 菜单有
     * 11 项，`inflateMenu` 不便宜，而 `updateIllust` 在**每次 ObjectPool 发射**时都会重跑
     * （收藏回流最常见）。这也正是 [setupTags] 里那句「收藏回流只更新菜单闭包」想要的形态。
     */
    private fun handleMenuItem(itemId: Int, illust: Illust): Boolean = when (itemId) {
        R.id.action_share -> {
            object : ShareIllust(mContext, illust) {
                override fun onPrepare() {}
            }.execute()
            true
        }
        R.id.action_share_image -> {
            shareFirstImage(illust)
            false
        }
        R.id.action_save_poster -> {
            // 与页码浮标同源：多图作品保存当前看到的页；图片区不在视口时回退首图。
            saveArtworkPoster(illust, pageProgressIndex.coerceAtLeast(0))
            true
        }
        R.id.action_snapshot -> {
            showSnapshotCreateDialog(illust)
            true
        }
        R.id.action_dislike -> {
            MuteTagSheet.show(childFragmentManager, illust.tags?.toTagsBeans(), illust.user)
            true
        }
        R.id.action_copy_link -> {
            Common.copy(mContext, ShareIllust.URL_Head + illust.id)
            true
        }
        R.id.action_show_original -> {
            val adapter = IllustAdapter(
                mActivity, this@FragmentIllust, illust, recyHeight, true
            )
            baseBind.recyclerView.adapter = adapter
            vm.pageDimensions.value?.let { adapter.seedPageDimensions(it) }
            true
        }
        R.id.action_mute_illust -> {
            PixivOperate.muteIllust(illust)
            true
        }
        R.id.action_flag_illust -> {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.FLAG_REASON.key)
            // TemplateActivity 读这个 extra 走 getLongExtra,Illust.id 本身就是 Long,
            // 别收窄成 Int,否则 Int/Long extra 类型不匹配,读回来静默变 0。
            intent.putExtra(FlagDescFragment.FlagObjectIdKey, illust.id)
            intent.putExtra(FlagDescFragment.FlagObjectTypeKey, ObjectSpec.POST)
            startActivity(intent)
            true
        }
        R.id.action_ai_upscale -> {
            ceui.pixiv.ui.upscale.ModelPickerDialog.pickOrUseDefault(childFragmentManager) { model ->
                aiHelper?.performUpscale(illust, model)
            }
            true
        }
        R.id.action_ai_rembg -> {
            ceui.pixiv.ui.upscale.RembgModelPickerDialog.pickOrUseDefault(childFragmentManager) { model ->
                aiHelper?.performRembg(illust, model)
            }
            true
        }
        else -> false
    }

    private fun setupLikeButton(illust: Illust) {
        if (illust.isBookmarked) {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp)
        } else {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp)
        }
        baseBind.postLike.setOnClick {
            val willBookmark = !illust.isBookmarked
            if (illust.isBookmarked) {
                baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp)
            } else {
                baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp)
            }
            playToggleHaptic(it, willBookmark)
            PixivOperate.postLikeDefaultStarType(illust)
            // 收藏后自动下载只在用户主动收藏(非取消)时触发,避免和"下载时自动收藏"循环联动(issue #880)。
            if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar) {
                IllustDownload.downloadIllustAllPages(illust)
            }
        }
        baseBind.postLike.setOnLongClickListener(object : OnLongClickListener {
            override fun onLongClick(v: View): Boolean {
                SelectTagBottomSheet.show(
                    this@FragmentIllust, illust.id.toInt(), Params.TYPE_ILLUST, illust.tagNames.toTypedArray(),
                )
                return true
            }
        })
    }

    private fun setupTags(illust: Illust) {
        val tags = illust.tags.orEmpty().toTagsBeans()
        val flow = illustTagView
        val synonymTags = tags.map { it.name to it.translated_name }
        val synonymEnabled = Shaft.sSettings.isSynonymDictEnabled
        if (synonymTags != renderedSynonymTags || synonymEnabled != renderedSynonymEnabled) {
            // 收藏回流只更新菜单闭包；重做同义词匹配会收起用户已展开的内容（#962）。
            renderedSynonymTags = synonymTags
            renderedSynonymEnabled = synonymEnabled
            synonymMatchView.setWorkTags(tags)
        }
        if (isSnapshotMode) {
            flow.overflowActionText = null
            flow.onOverflowClick = null
            flow.onPinTag = null
            flow.onViewAuthorWorks = null
            flow.setOnItemClickListener { _, _ -> snapshotUnsupportedToast() }
            flow.setOnItemLongClickListener { item, _ ->
                if (item.name.isNotEmpty()) Common.copy(mContext, item.name)
                true
            }
            flow.setJavaTags(tags)
            return
        }
        flow.overflowActionText = "+ " + getString(R.string.work_tag_edit_entry)
        flow.onOverflowClick = { TagEditSheet.show(childFragmentManager, illust.id.toLong()) }
        flow.setJavaTags(tags)
        flow.setOnItemClickListener { _, position ->
            val intent = Intent(mContext, SearchActivity::class.java)
            intent.putExtra(Params.KEY_WORD, tags[position].name)
            intent.putExtra(Params.INDEX, 0)
            startActivity(intent)
        }
        flow.setOnItemLongClickListener(null)
        flow.onPinTag = { name, translated, pinned ->
            val tag = ceui.lisa.models.TagsBean().apply {
                this.name = name
                this.translated_name = translated
            }
            PixivOperate.insertPinnedSearchHistory(name, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD,
                pinned, if (pinned) buildPinnedTagPreviewJson(tag, illust) else null)
            Common.showToast(R.string.operate_success)
        }
        flow.onViewAuthorWorks = illust.user?.id?.takeIf { it > 0L }?.let { userId ->
            { tagName ->
                startActivity(Intent(mContext, TemplateActivity::class.java).apply {
                    putExtra(Params.USER_ID, userId)
                    putExtra(Params.KEY_WORD, tagName)
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, if (illust.isManga()) {
                        TemplateRoute.USER_MANGA_BY_TAG.key
                    } else {
                        TemplateRoute.USER_ILLUSTS_BY_TAG.key
                    })
                })
            }
        }
    }

    private fun setupInfo(illust: Illust) {
        illustSizeView.text = getString(R.string.string_193, illust.width, illust.height)
        illustIdView.text = getString(R.string.string_194, illust.id)
        userIdView.text = getString(R.string.string_195, illust.user?.id)
        illustIdView.setOnClick { Common.copy(mContext, illust.id.toString()) }
        userIdView.setOnClick { Common.copy(mContext, illust.user?.id.toString()) }
    }

    /**
     * 图片区（RecyclerView + adapter）真正依赖的数据指纹。指纹没变就不重建 adapter（#962）。
     * 覆盖面对齐 [IllustAdapter] / [UgoiraPlayerAdapter] 实际读的字段：
     * 用哪个 adapter([isGif])、几页([page_count])、pos0 定高([width]/[height])、各页图 url。
     * 精简 bean → detail 全量覆盖（#569：池里 bean 缺分页图/原图）这一步 url 会从无到有，
     * 指纹必变，图片区照样重建；而收藏回流那种「只动 is_bookmarked / total_bookmarks」的
     * 池发射指纹不变，不再触发重建。
     */
    private fun imageAreaSignature(illust: Illust): String {
        val urls = if (illust.page_count <= 1) {
            illust.meta_single_page?.original_image_url.orEmpty()
        } else {
            illust.meta_pages?.joinToString("|") { it.image_urls?.original.orEmpty() }.orEmpty()
        }
        return "${illust.isGif()}|${illust.page_count}|${illust.width}x${illust.height}|$urls"
    }

    /**
     * 右上角常驻页码浮标(#1058):不进阅读器、直接在详情页往下滑看多图时,标出「当前页 / 总页」。
     *
     * 刷新时机挂在图片列表的排版回调上,而不是「换了 adapter 就算一次」:adapter 是在
     * [setupBottomSheet] 的 onGlobalLayout 里建的,那一刻子 View 还没排版,读到的 top/bottom
     * 全是 0;排版回调还顺带覆盖了「图加载完撑高条目」这类没有滚动的位移。
     *
     * 监听只挂一次 —— ObjectPool 每发射一次都会重跑 [updateIllust],但 recyclerView 本身不换。
     *
     * 挂和摘都锚在「附着到窗口」上,不能放到 onDestroyView 里摘:FragmentManager 是先把 view
     * 从容器里 removeView(已 detach)、再走 onDestroyView 的,那时候 `getViewTreeObserver()`
     * 返回的已经是一份新建的游离 observer,remove 静默落空 —— 监听会一直留在**窗口**那份上,
     * 每次 layout 空跑一遍还钉着已销毁的 Fragment。详情页在 ViewPager 里翻一路就攒一路。
     */
    private fun attachPageProgressPill() {
        if (pageProgressPillAttached) return
        pageProgressPillAttached = true
        val listView = baseBind.recyclerView
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                refreshPageProgressPill()
            }
        })
        val layoutListener =
            OnGlobalLayoutListener {
                refreshPageProgressPill()
                publishViewportRect()
            }
        listView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            }

            // detach 派发时 mAttachInfo 还没置空,这里拿到的仍是窗口那份,摘得掉。
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            }
        })
        if (listView.isAttachedToWindow) {
            listView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }
        attachPagesPreview()
    }

    /**
     * 阅读浮标**长按** → 多图预览(#1085)。web 端那枚页码按钮是单击预览的,这里改成长按 —— V3 那枚
     * 胶囊里「收起」那一段本来就吃单击,两棵树的手势保持一致。
     *
     * 面板拉起来时浮标是靠 alpha 淡出的(见 [setupBottomSheet]),View 还在、照样收触摸;透明了还能
     * 长按出一张预览就成了「按到空气」,所以顺带按 alpha 挡掉。
     *
     * 选页结果走 fragment result 回来而不是 lambda:sheet 跨横屏会重建,回调必然失效(同 #1023)。
     */
    private fun attachPagesPreview() {
        baseBind.pageProgressPill.setOnLongClickListener { v ->
            if (v.alpha < 0.5f) return@setOnLongClickListener false
            val illust = currentIllust() ?: return@setOnLongClickListener false
            if (illust.isGif()) return@setOnLongClickListener false
            val models = if (isSnapshotMode) {
                // 快照页的图在本地,缩略图别回网上取(离线打开时那边什么也拿不到)。
                val data = snapshotViewerData ?: return@setOnLongClickListener false
                ArtworkThumbsSheet.localModels(illust.page_count) { data.pageFile(it) }
            } else {
                ArtworkThumbsSheet.networkModels(illust)
            }
            if (!ArtworkThumbsSheet.show(
                    this,
                    models,
                    pageProgressIndex.coerceAtLeast(0),
                    readerIllustId = illust.id.takeUnless { isSnapshotMode },
                )) {
                return@setOnLongClickListener false
            }
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
        childFragmentManager.setFragmentResultListener(
            ArtworkThumbsSheet.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            jumpToPage(bundle.getInt(ArtworkThumbsSheet.KEY_PAGE_INDEX))
        }
    }

    /** 池 / 快照两条来源统一取当前作品的 bean。快照不写 ObjectPool,只有本地字段那一份。 */
    private fun currentIllust(): Illust? =
        if (isSnapshotMode) snapshotBean
        else ObjectPool.get<Illust>(safeArgs.illustId.toLong()).value

    /**
     * 跳到预览里选中的那一页。V2 的图片区是一页一条目的 [LinearLayoutManager],没有折叠态,
     * 位置就是页序。落位对齐列表顶缘,与页码读数的锚线口径一致(浮标压着谁就读谁)。
     */
    private fun jumpToPage(index: Int) {
        val listView = baseBind.recyclerView
        val total = (listView.adapter as? IllustAdapter)?.itemCount ?: return
        if (index !in 0 until total) return
        val lm = listView.layoutManager
        if (lm is LinearLayoutManager) lm.scrollToPositionWithOffset(index, 0)
        else listView.scrollToPosition(index)
    }

    /**
     * 收二级大图翻页落定的广播（[ViewerPageLink]），把列表视口滚到同一页。
     *
     * 挂在 STARTED 上：大图是透明窗口，本页在它之下只走 onPause、仍是 STARTED，所以收集器全程
     * 活着；而 STARTED 又能保证「切后台 / 离开」时立刻停手。
     *
     * `page == entryPage` 只在**先前真被挪动过**时才跟着回。否则「点开又原页退出」这种没翻过页的
     * 会话会被无谓地顶到顶对齐，破坏用户自己滚出来的位置。
     */
    private fun wireViewerPageLink() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ViewerPageLink.pings.collect { ping ->
                    if (ping.illustId != viewerLinkIllustId) return@collect
                    if (ping.page == ping.entryPage && !viewerViewportSynced) return@collect
                    viewerViewportSynced = true
                    viewerSyncedPage = ping.page
                    jumpToPage(ping.page)
                }
            }
        }
    }

    /**
     * 把「当前页那一格」的屏幕矩形回传给大图，供退出时归位。
     *
     * 挂在列表的排版回调上（与 [refreshPageProgressPill] 同源）：`scrollToPositionWithOffset` 只是
     * 设下待定位置，要等这一帧排版跑完那一格才有真实坐标。矩形与列表可视区求交 —— 高图的整格
     * 远超屏幕，取可见段才不至于把归位落点算到屏幕外（宽度不变，缩放比例仍然正确）。
     *
     * `viewerSyncedPage < 0`（没进过大图）时直接返回，所以平时每帧滚动不付这份开销。
     */
    private fun publishViewportRect() {
        val page = viewerSyncedPage
        if (page < 0) return
        val listView = baseBind.recyclerView
        val lm = listView.layoutManager ?: return
        val cell = lm.findViewByPosition(page)
        if (cell == null) {
            // 还没排到这一格也要回传（rect 给 null）：告诉大图「详情页已挪动」，它就退回淡出，不缩向失准的进场矩形。
            ViewerPageLink.publishViewport(viewerLinkIllustId, page, null)
            return
        }
        val cellLoc = IntArray(2)
        cell.getLocationOnScreen(cellLoc)
        val listLoc = IntArray(2)
        listView.getLocationOnScreen(listLoc)
        val left = cellLoc[0]
        val top = maxOf(cellLoc[1], listLoc[1])
        val right = cellLoc[0] + cell.width
        val bottom = minOf(cellLoc[1] + cell.height, listLoc[1] + listView.height)
        ViewerPageLink.publishViewport(
            viewerLinkIllustId,
            page,
            if (right > left && bottom > top) intArrayOf(left, top, right, bottom) else null,
        )
    }

    /**
     * 「当前页」取**正被浮标盖着的那一页**,而不是视口正中那一页 —— 浮标就悬在 toolbar 下方,
     * 拿它自己那条线去问「我盖着谁」最直观;竖幅长图也不会因为中线正好落在页缝里而跳数。
     * 具体是:可见的页里,顶边已经越过锚线的最后一页;都还没越过(刚进页面)就取最靠前那页。
     *
     * 总页数直接问 adapter:动图走的是 [UgoiraPlayerAdapter],不是 [IllustAdapter],自然拿不到
     * 页数、浮标也就不出现 —— 动图本来就没有「第几页」。
     */
    private fun refreshPageProgressPill() {
        val pill = baseBind.pageProgressPill
        val listView = baseBind.recyclerView
        val total = (listView.adapter as? IllustAdapter)?.itemCount ?: 0
        val layoutManager = listView.layoutManager
        if (total <= 1 || layoutManager == null || listView.childCount == 0) {
            pageProgressIndex = -1
            pill.isVisible = false
            return
        }
        // toolbar 与列表分属两棵子树(列表还会随底部面板滑动整体位移),锚线走屏幕坐标换算。
        baseBind.toolbar.getLocationOnScreen(pageProgressLocation)
        val anchorOnScreen = pageProgressLocation[1] + baseBind.toolbar.height
        listView.getLocationOnScreen(pageProgressLocation)
        val anchorY = anchorOnScreen - pageProgressLocation[1]
        var current = -1
        var firstVisible = -1
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i)
            val position = layoutManager.getPosition(child)
            if (position < 0 || position >= total) continue
            if (firstVisible < 0 || position < firstVisible) firstVisible = position
            if (child.top <= anchorY && position > current) current = position
        }
        if (current < 0) current = firstVisible
        if (current < 0) {
            pageProgressIndex = -1
            pill.isVisible = false
            return
        }
        // 到底了：最后一页后面没有内容可垫，浮标永远压不到它 —— 这时读数应该认「正在看的最后一页」。
        if (!listView.canScrollVertically(1)) {
            var lastVisible = current
            for (i in 0 until listView.childCount) {
                val p = layoutManager.getPosition(listView.getChildAt(i))
                if (p in 0 until total && p > lastVisible) lastVisible = p
            }
            current = lastVisible
        }
        pageProgressIndex = current
        val text = getString(R.string.artwork_page_indicator, current + 1, total)
        applyPageProgressText(pill, text)
        pill.isVisible = true
    }

    /**
     * 把「当前页 / 总页」写进浮标 —— **必须在布局之外写**，否则浮标会卡在上一段文字的尺寸上、
     * 分母被折行裁掉。
     *
     * 两个调用点都会落在布局过程中：[attachPageProgressPill] 里挂在列表上的
     * OnGlobalLayoutListener（排版收尾派发），以及 `scrollToPositionWithOffset` 这类**待定滚动**
     * —— 它在 RecyclerView.onLayout() 里被消费，随之派发的 onScrolled 同样在布局中。此时 setText
     * 触发的 requestLayout 会被框架并进第二趟布局、或直接吞掉（logcat 实证："requestLayout()
     * improperly called by ... app:id/page_progress_pill during layout: running second layout
     * pass"），浮标的已测量宽高就停在**上一段文字**的尺寸上；而它是 wrap_content、没有
     * singleLine / maxLines，新文字装不下就会折到第二行，第二行落在浮标盒子之外被裁 —— 表现成
     * 「2 /」、分母不见了，而且要等下一次真正的排版才复原（有时一直不复原）。
     *
     * post 到布局之外再写：setText 的 requestLayout 会被正常受理，浮标按新文字重新量一次、
     * **自己长大**，既不折行也不硬裁（刻意不加 singleLine / ellipsize：那只是把裁切换个地方）。
     */
    private fun applyPageProgressText(pill: TextView, text: String) {
        if (pill.text?.toString() == text) return
        // 还没上屏就没有排版在跑，直接写；也免得把 runnable 丢进 RunQueue 一直挂着。
        // 还隐藏着（首次出现 / 滑离图片后滑回）也当场写：调用方紧接着就把它设为可见，显隐变化
        // 本身就会带着新文字重新量一次；推迟写反而会让它先以空白或上一次的读数露一帧。
        if (!pill.isAttachedToWindow || !pill.isVisible) {
            pill.text = text
            return
        }
        pill.post {
            if (pill.text?.toString() != text) pill.text = text
        }
    }

    private fun setupBottomSheet(illust: Illust) {
        val sheetBehavior: BottomSheetBehavior<*> = BottomSheetBehavior.from(baseBind.coreLinear)

        // 布局里 `app:behavior_peekHeight` 写死 0dp（不是真实值），真正的 peek 在这里定 ——
        // 而且必须**赶在首次布局之前**定好：一旦晚到 onGlobalLayout，首帧就会按错的 peek 摆，
        // 把信息区顶部（浏览 / 收藏数那一行）露出来。那一刻 bottom_bar 还没被量过，
        // 手动量一次（它 match_parent 宽，取屏宽即可；量错一点也没关系，下面的回调会再纠一次）。
        if (!bottomSheetPeekApplied && baseBind.bottomBar.height <= 0) {
            val bar = baseBind.bottomBar
            val width = bar.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val measured = bar.measuredHeight
            if (measured > 0) sheetBehavior.peekHeight = measured
        }

        baseBind.coreLinear.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view ?: return
                context ?: return
                // 信息区是 ViewStub，非当前页会晚一个消息才建出来。它还没建时**不能算** ——
                // 拿 0 当高度会把 coreLinear 定死在一个很矮的值上，之后信息区建出来也撑不开，
                // 表现就是「抽屉只能往上拉几个像素」。直接返回，等它建好；那一次布局变化会让
                // 本回调再跑一遍，届时读到真实高度。
                val infoView = secondLinearView ?: return
                val realHeight = baseBind.bottomBar.height +
                        baseBind.viewDivider.height +
                        infoView.height
                val maxHeight = resources.displayMetrics.heightPixels * 3 / 4
                val params = baseBind.coreLinear.layoutParams
                val slideMaxHeight = Math.min(realHeight, maxHeight)
                params.height = slideMaxHeight
                baseBind.coreLinear.layoutParams = params
                val bottomCardHeight = baseBind.bottomBar.height
                sheetDeltaY = slideMaxHeight - bottomCardHeight
                // 第一次纠正不带动画，理由见 bottomSheetPeekApplied 的注释。
                sheetBehavior.setPeekHeight(bottomCardHeight, bottomSheetPeekApplied)
                bottomSheetPeekApplied = true

                val headParams = baseBind.helperView.layoutParams
                headParams.height = bottomCardHeight - DensityUtil.dp2px(16.0f)
                baseBind.helperView.layoutParams = headParams
                // 每次发射都重挂一个 callback 会让它无限累积(同一次 onSlide 被回调 N 次),挂一次就够。
                // deltaY 走字段而不是闭包:sheet 会随内容(简介补拉到货)重新量高,回调必须用最新的那份。
                if (!bottomSheetCallbackAttached) {
                    bottomSheetCallbackAttached = true
                    sheetBehavior.addBottomSheetCallback(object : BottomSheetCallback() {
                        override fun onStateChanged(bottomSheet: View, newState: Int) {}
                        override fun onSlide(bottomSheet: View, slideOffset: Float) {
                            baseBind.refreshLayout.translationY = -sheetDeltaY * slideOffset * 0.7f
                            // 面板拉起来就不是在「看图」了,浮标跟着淡出(#1058)。
                            baseBind.pageProgressPill.alpha = 1f - slideOffset
                        }
                    })
                }
                recyHeight = baseBind.recyclerView.height
                // 上面的高度测算每次都要跑(简介补拉到货后 sheet 要重新长高),但图片区不能跟着重建:
                // 换 layoutManager + new adapter = 所有大图从零重新加载,这就是收藏一下整页闪一次的原因(#962)。
                val signature = imageAreaSignature(illust)
                if (signature != renderedImageSignature) {
                    renderedImageSignature = signature
                    baseBind.recyclerView.layoutManager = LinearLayoutManager(mContext)
                    if (illust.isGif()) {
                        // ugoira 内联播放:以前 VActivity 把动图甩去独立的 FragmentSingleUgora,
                        // 现在留在本页,用解耦的 UgoiraPlayerAdapter 进页即自动加载+播放。
                        val maxHeight = resources.displayMetrics.heightPixels * 3 / 4
                        baseBind.recyclerView.adapter =
                            UgoiraPlayerAdapter(illust, viewLifecycleOwner, maxHeight)
                    } else {
                        val adapter = IllustAdapter(mActivity, this@FragmentIllust, illust, recyHeight, false)
                        baseBind.recyclerView.adapter = adapter
                        if (isSnapshotMode) {
                            adapter.setSnapshotId(snapshotId)
                            adapter.setSnapshotIsAuto(snapshotIsAuto)
                            applySnapshotLocalPages(adapter)
                        } else {
                            vm.pageDimensions.value?.let { adapter.seedPageDimensions(it) }
                        }
                    }
                } else {
                    // 不重建,但 bean 实例可能已被池的 merge 换成新的一份(收藏态就在里面)。
                    // 只顶掉引用、不动视图,免得 adapter 的长按下载和跳二级详情读到过期的收藏态。
                    (baseBind.recyclerView.adapter as? AbstractIllustAdapter<*>)?.rebindIllust(illust)
                }
                baseBind.coreLinear.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    private fun setupActionButtons(illust: Illust) {
        baseBind.related.setOnClick {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.RELATED_ILLUSTS.key)
            // TemplateActivity 按 getIntExtra 读 ILLUST_ID,Illust.id 是 Long 必须收窄
            intent.putExtra(Params.ILLUST_ID, illust.id.toInt())
            intent.putExtra(Params.ILLUST_TITLE, illust.title)
            startActivity(intent)
        }
        baseBind.comment.setOnClick {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMMENTS.key)
            // TemplateActivity 按 getIntExtra 读 ILLUST_ID,Illust.id 是 Long 必须收窄
            intent.putExtra(Params.ILLUST_ID, illust.id.toInt())
            intent.putExtra(Params.ILLUST_TITLE, illust.title)
            startActivity(intent)
        }
        // 「收藏数」入口（illust_like）现在住在信息区 ViewStub 里，接线已移到 setupInfoSection()。
    }

    private fun setupDescription(illust: Illust) {
        val caption = illust.caption
        if (caption.isNullOrEmpty()) {
            descriptionView.visibility = View.GONE
            return
        }
        descriptionView.visibility = View.VISIBLE
        // HtmlTextView.setHtml 在 caption 含 <a> 链接时会直接吐出空串（#552）。
        // 换成 androidx HtmlCompat.fromHtml + LinkMovementMethod，文本和可点链接都能正常渲染。
        descriptionView.text = androidx.core.text.HtmlCompat.fromHtml(
            caption, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
        )
        descriptionView.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * 作者行（`rela_illust_brief`：头像 / 用户名 / 投递时间）。
     *
     * 它在 `bottom_bar` 里、属于**首屏**，所以必须**帧同步**设置 —— 不能跟着信息区一起延后。
     * 尤其投递时间：布局里写的是 `@string/string_68` 占位，晚一个消息就会先露占位文本。
     */
    private fun setupAuthorRow(illust: Illust) {
        baseBind.postTime.text = String.format(
            "%s投递", Common.getLocalYYYYMMDDHHMMString(illust.create_date)
        )
    }

    /** 浏览 / 收藏计数。它们住在信息区（首屏之下），所以跟着 [setupInfoSection] 一起延后。 */
    private fun setupStats(illust: Illust) {
        totalViewText.text = (illust.total_view ?: 0).toString()
        totalLikeText.text = (illust.total_bookmarks ?: 0).toString()
    }

    private fun setupDownloadButton(illust: Illust) {
        baseBind.download.setChangeAlphaWhenPress(true)
        baseBind.related.setChangeAlphaWhenPress(true)
        baseBind.comment.setChangeAlphaWhenPress(true)
        baseBind.download.setOnClick { v: View? ->
            val resolution = Shaft.sSettings.defaultImageResolution.let {
                if (it.isNullOrEmpty()) Params.IMAGE_RESOLUTION_ORIGINAL else it
            }
            Timber.tag(DownloadRecordStateSource.LOG_TAG).d(
                "click illustId=%d pages=%d resolution=%s label=%s",
                illust.id, illust.page_count, resolution, baseBind.download.text,
            )
            if (illust.page_count == 1) {
                IllustDownload.downloadIllustFirstPageWithResolution(illust, resolution, mContext as BaseActivity<*>)
            } else {
                IllustDownload.downloadIllustAllPagesWithResolution(illust, resolution, mContext as BaseActivity<*>)
            }
            if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isBookmarked) {
                PixivOperate.postLikeDefaultStarType(illust)
            }
        }
        baseBind.download.setOnLongClickListener {
            val IMG_RESOLUTION_TITLE = arrayOf(
                getString(R.string.string_280),
                getString(R.string.string_281),
                getString(R.string.string_282),
                getString(R.string.string_283)
            )
            val IMG_RESOLUTION = arrayOf(
                Params.IMAGE_RESOLUTION_ORIGINAL,
                Params.IMAGE_RESOLUTION_LARGE,
                Params.IMAGE_RESOLUTION_MEDIUM,
                Params.IMAGE_RESOLUTION_SQUARE_MEDIUM
            )
            CheckableDialogBuilder(mContext)
                .addItems(IMG_RESOLUTION_TITLE) { dialog, which ->
                    Timber.tag(DownloadRecordStateSource.LOG_TAG).d(
                        "click_resolution illustId=%d pages=%d resolution=%s",
                        illust.id, illust.page_count, IMG_RESOLUTION[which],
                    )
                    if (illust.page_count == 1) {
                        IllustDownload.downloadIllustFirstPageWithResolution(
                            illust, IMG_RESOLUTION[which], mContext as BaseActivity<*>
                        )
                    } else {
                        IllustDownload.downloadIllustAllPagesWithResolution(
                            illust, IMG_RESOLUTION[which], mContext as BaseActivity<*>
                        )
                    }
                    dialog.dismiss()
                }
                .create()
                .show()
            true
        }
    }

    private fun loadUserAvatar(illust: Illust) {
        val url = illust.user?.profile_image_urls?.medium
        // Glide 的 into() 会先清空 target 再起新请求,即使命中内存缓存也会空一帧。池每发射一次就
        // 重发一次 → 收藏一下头像闪一下(#962)。url 没变、图还在,就什么都不用做。
        if (url == loadedAvatarUrl && baseBind.userHead.drawable != null) return
        loadedAvatarUrl = url
        Glide.with(mContext)
            .load(GlideUtil.getUrl(url))
            .error(R.drawable.no_profile)
            .into(baseBind.userHead)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoSnapshotEntered = savedInstanceState?.getBoolean(KEY_AUTO_SNAPSHOT_ENTERED, false) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTO_SNAPSHOT_ENTERED, autoSnapshotEntered)
    }

    override fun onResume() {
        super.onResume()
        autoSnapshotDemoted = false
        // 大图会话已结束（退出动画播完才 finish、这里才 resume）：联动状态归零。否则
        // viewerSyncedPage 会一直 ≥ 0，让 publishViewportRect 在之后每一次排版空跑。
        viewerViewportSynced = false
        viewerSyncedPage = -1
        if (!isSnapshotMode) {
            // 凭证还在手里说明上一次「可见」还没结算（进二级大图页 / 横滑离开再回来）：表不重开、
            // 不重复计进入，只把停过的那一段接着算。
            if (autoSnapshotVisit == null) {
                val autoSnapshotIllust = ObjectPool.get<Illust>(safeArgs.illustId.toLong()).value
                autoSnapshotVisit = AutoSnapshotEngine.onArtworkPageVisible(
                    illustId = safeArgs.illustId.toLong(),
                    type = autoSnapshotIllust?.type,
                    pageCount = autoSnapshotIllust?.page_count ?: 0,
                    countAsEntry = !autoSnapshotEntered,
                )
                autoSnapshotEntered = true
            } else {
                // 横滑回来：表被挂起过，从这里接着走（被半透明层盖住时它从没停过，这里是空操作）。
                AutoSnapshotEngine.onArtworkPageResumed(autoSnapshotVisit)
            }
            // 从二级大图页返回后，把进程内已缓存 ORIGINAL 的页直接回填，不重绑列表。
            (baseBind.recyclerView.adapter as? IllustAdapter)?.showCachedOriginalOverlays()
        }
    }

    override fun onPause() {
        if (!isSnapshotMode) {
            // 宿主还 RESUMED ⇒ 是「本页被降级」（横滑到相邻作品），宿主还在、随时会滑回来：
            // 挂起表，不结算也不评估。被自家半透明层（二级大图页及更上层）盖住、或切后台时
            // 宿主自己先 paused，这里什么都不做：计时继续走，不结算也不评估。
            if (autoSnapshotVisit != null && isHostStillResumed()) {
                AutoSnapshotEngine.onArtworkPageSuspended(autoSnapshotVisit)
                autoSnapshotDemoted = true
            }
        }
        super.onPause()
    }

    /**
     * 结算并停表。凭证只消费一次，所以 onStop 之后接着来的 onDestroyView 是空操作。
     *
     * [evaluate] 为假用于旋屏：视觉没离开，不该触发生成，但停留确实发生了，记下来不丢。
     */
    private fun settleAutoSnapshot(evaluate: Boolean) {
        val visit = autoSnapshotVisit ?: return
        autoSnapshotVisit = null
        AutoSnapshotEngine.onArtworkPageLeft(visit, evaluate)
    }

    override fun onStop() {
        // 宿主停止 = 切后台 / 页面结束；旋屏也走这里，但不算离开，只结算不评估。
        // 横滑走的页例外：旋屏前它就已经不在视线里了。
        settleAutoSnapshot(evaluate = autoSnapshotDemoted || activity?.isChangingConfigurations != true)
        super.onStop()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        aiHelper = IllustAiHelper(this, baseBind.root, ensureOverlay = ::ensureAiOverlay)
        // 必须在快照的 early-return 之前：快照详情页同样走这条联动。
        wireViewerPageLink()
        if (isSnapshotMode) return
        val intentFilter = IntentFilter()
        val illust = ObjectPool.get<Illust>(safeArgs.illustId.toLong()).value ?: return
        mReceiver = CallBackReceiver { context, intent ->
            val bundle = intent.extras
            if (bundle != null) {
                val id = bundle.getInt(Params.ID)
                if (illust.id == id.toLong()) {
                    val isLiked = bundle.getBoolean(Params.IS_LIKED)
                    // Illust 不可变:收藏态 / 计数已由 PixivActions.writeIllustBookmarkLocally 写进
                    // ObjectPool(本页 observer 会重跑 updateIllust),这里只按广播即时刷一下 UI。
                    val latest = ObjectPool.get<Illust>(illust.id).value ?: illust
                    if (isLiked) {
                        baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp)
                    } else {
                        baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp)
                    }
                    // 信息区是 ViewStub，非当前页会晚一个消息才建出来 —— 广播有可能先到。
                    // 建好之后 updateIllust → setupStats 会补上正确值，这里漏一次不影响终态。
                    if (secondLinearView != null) {
                        totalLikeText.text = (latest.total_bookmarks ?: 0).toString()
                    }
                }
            }
        }
        intentFilter.addAction(Params.LIKED_ILLUST)
        mReceiver?.let {
            LocalBroadcastManager.getInstance(mContext).registerReceiver(it, intentFilter)
        }
        aiHelper?.restoreUpscaleIfRunning(safeArgs.illustId)
    }

    override fun onDestroy() {
        mReceiver?.let {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(it)
        }
        super.onDestroy()
    }

    override fun onDestroyView() {
        // 兜底：页面被销毁（pager 页回收 / 进程内导航销毁）时把还没结算的那一段交出去。
        settleAutoSnapshot(evaluate = true)
        try {
            baseBind.recyclerView.adapter = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pageProgressPillAttached = false
        pageProgressIndex = -1
        viewerViewportSynced = false
        viewerSyncedPage = -1
        renderedImageSignature = null
        renderedSynonymTags = null
        bottomSheetCallbackAttached = false
        bottomSheetPeekApplied = false
        menuIllust = null
        renderedMenuShape = null
        sheetDeltaY = 0
        loadedAvatarUrl = null
        aiHelper = null
        // 懒 inflate 的标志随视图销毁归零：视图重建后拿到的是新的 ViewStub，
        // 不归零的话 ensure*() 会误判为「已经建过」而跳过，那两块就再也不出现了。
        abandonedFrameReady = false
        aiOverlayReady = false
        infoSectionReady = false
        super.onDestroyView()
    }

    override fun vertical() {
        baseBind.toolbar.setPadding(0, SystemBarMetrics.statusBarHeight(requireContext()), 0, 0)
    }

    companion object {
        /** 旋屏重建时把「已计过进入」带过去，见 autoSnapshotEntered。 */
        private const val KEY_AUTO_SNAPSHOT_ENTERED = "auto_snapshot_entered"

        @JvmStatic
        fun newInstance(illustId: Int): FragmentIllust {
            return FragmentIllust().apply {
                arguments = Bundle().apply {
                    putInt("illust_id", illustId)
                }
            }
        }
    }
}
