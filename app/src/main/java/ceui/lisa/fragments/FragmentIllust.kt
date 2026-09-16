package ceui.lisa.fragments

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.lifecycle.lifecycleScope
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
import ceui.pixiv.ui.translate.translateTag
import ceui.pixiv.ui.detail.ArtworkThumbsSheet
import ceui.pixiv.ui.detail.TagEditSheet
import ceui.pixiv.ui.detail.UgoiraPlayerAdapter
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentIllustBinding
import ceui.pixiv.ui.muted.MuteTagSheet
import ceui.lisa.download.IllustDownload
import ceui.pixiv.api.model.Illust
import ceui.lisa.models.ObjectSpec
import ceui.lisa.models.TagsBean
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
import ceui.pixiv.widgets.ProgressTextButton
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
import ceui.pixiv.ui.synonym.SynonymOperate
import ceui.pixiv.ui.upscale.IllustAiHelper
import ceui.pixiv.utils.buildPinnedTagPreviewJson
import ceui.pixiv.utils.setOnClick

import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import ceui.pixiv.witstudio.dialog.WitDialog.CheckableDialogBuilder
import ceui.pixiv.witstudio.dialog.WitDialog.MessageDialogBuilder
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.pixiv.ui.navigation.TemplateRoute

class FragmentIllust : BaseLazyFragment<FragmentIllustBinding>() {
    private var autoSnapshotVisit: AutoSnapshotEngine.ArtworkVisit? = null

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

    // ObjectPool 的每一次发射都会重跑一遍 updateIllust(收藏回流是最常见的一次),下面这组状态用来
    // 让「重建图片区」「重建标签区」「挂 sheet callback」「发头像 Glide 请求」这几件带视觉副作用的
    // 事只在真需要时做——否则收藏一下整页就闪一次(#962)。跟着 view 走,onDestroyView 里清掉。
    private var renderedImageSignature: String? = null
    private var renderedTagSignature: String? = null
    private var bottomSheetCallbackAttached = false
    private var pageProgressPillAttached = false
    private val pageProgressLocation = IntArray(2)
    /** 页码浮标当前指着哪一页(0 基);浮标不在场时为 -1。长按预览拿它当高亮/初始滚动位。 */
    private var pageProgressIndex = -1
    private var sheetDeltaY = 0
    private var loadedAvatarUrl: String? = null

    public override fun initLayout() {
        mLayoutID = R.layout.fragment_illust
    }

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
        baseBind.user = userLiveData

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
        baseBind.illustLike.setOnClickListener { snapshotUnsupportedToast() }
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
                    baseBind.abandonedFrame.isVisible = false
                } else {
                    baseBind.contentFrame.isVisible = false
                    baseBind.abandonedFrame.isVisible = true
                    // 整页遮罩不再是一块纯黑：糊掉的作品图 + spoiler 粒子。
                    // bind 幂等(同一封面不重发请求)，可以跟着 observer 每次发射照调。
                    baseBind.abandonedSpoiler.bind(Glide.with(this@FragmentIllust), GlideUtil.getMediumImg(illust))
                    baseBind.cancelMuteIllust.isVisible = illustEntity != null
                    baseBind.cancelMuteUser.isVisible = userEntity != null

                    if (illustEntity != null) {
                        baseBind.cancelMuteIllust.setOnClick {
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
                        baseBind.cancelMuteUser.setOnClick {
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
                unfollowUser(it, userId)
            }
        } else {
            baseBind.unfollow.isVisible = false
            baseBind.follow.isVisible = true
            baseBind.follow.setOnClick {
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

    private fun updateIllust(illust: Illust) {
        // 快照是「当时那一刻」的存档，在线可见性判断不该作用在它上面：Gson 默认丢弃 null 字段，
        // 精简来源的 bean 存进 illust.json 后 visible 会缺失 → 反序列化成 null → 一打开就
        // 提示「作品不存在」并自动关页。id 那条仍然保留(存档本身坏了才会命中)。
        if (illust.id == 0L || (!isSnapshotMode && illust.visible != true)) {
            Common.showToast(R.string.string_206)
            Handler().postDelayed({ finish() }, 1000)
            return
        }

        baseBind.leave.setOnClick {
            viewLifecycleOwner.lifecycleScope.launch {
                it.showProgress()
                delay(600L)
                requireActivity().finish()
                it.hideProgress()
            }
        }

        setupTitle(illust)
        setupToolbarMenu(illust)
        attachPageProgressPill()
        setupLikeButton(illust)
        setupTags(illust)
        setupInfo(illust)
        setupBottomSheet(illust)
        setupActionButtons(illust)
        setupDescription(illust)
        setupStats(illust)
        setupDownloadButton(illust)
        loadUserAvatar(illust)
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
                String.format("@%s %s", seriesString, illust.title)
            )
            spannableString.setSpan(
                clickableSpan, 0, seriesString.length + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            baseBind.title.movementMethod = LinkMovementMethod.getInstance()
            baseBind.title.text = spannableString
        } else {
            baseBind.title.text = illust.title
        }
        baseBind.title.setOnLongClickListener {
            Common.copy(mContext, illust.title)
            true
        }
    }

    private fun setupToolbarMenu(illust: Illust) {
        baseBind.toolbar.menu?.clear()
        baseBind.toolbar.inflateMenu(R.menu.share)
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
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }
        baseBind.toolbar.setOnMenuItemClickListener(Toolbar.OnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
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
        })
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
        // 标签区重建 = 整片 chip 全部拆掉重新 inflate,肉眼就是一次闪烁。池发射(收藏回流等)带不来
        // 新标签,所以只在标签本身真的变了才重建;点击/长按监听照常重挂,始终闭包到最新的 bean(#962)。
        val tagSignature = illust.tags.orEmpty().joinToString("|") {
            "${it.name.orEmpty()}/${it.translated_name.orEmpty()}"
        }
        // issue #1023: 末尾多挂一格「编辑标签」,对齐网页版标签行末尾那个「+」。TagFlowLayout
        // 没有 footer 概念,只能把它当第 tags.size 格来渲染,并在两个监听里按下标提前拦掉 ——
        // 否则 illust.tags[position] 会越界。
        val tags = illust.tags.orEmpty().toTagsBeans()
        if (isSnapshotMode) {
            // 快照只读：标签区不渲染「编辑标签」入口，点击标签也不跳在线搜索。
            baseBind.synonymMatch.setWorkTags(tags)
            baseBind.illustTag.adapter = object : TagAdapter<TagsBean>(tags) {
                override fun getView(parent: FlowLayout, position: Int, s: TagsBean): View {
                    val tv = LayoutInflater.from(mContext).inflate(
                        R.layout.recy_single_line_text_new, parent, false
                    ) as TextView
                    var tag = s.name
                    if (!TextUtils.isEmpty(s.translated_name)) {
                        tag = tag + "/" + s.translated_name
                    }
                    tv.text = tag
                    return tv
                }
            }
            baseBind.illustTag.setOnTagClickListener { _, _, _ -> snapshotUnsupportedToast(); true }
            baseBind.illustTag.setOnTagLongClickListener { _, position, _ ->
                val tagName = tags.getOrNull(position)?.name
                if (!tagName.isNullOrEmpty()) Common.copy(mContext, tagName)
                true
            }
            return
        }
        if (tagSignature != renderedTagSignature) {
            renderedTagSignature = tagSignature
            // 同义词词典「标签匹配关系」框（issue #904）
            baseBind.synonymMatch.setWorkTags(tags)
            baseBind.illustTag.adapter = object : TagAdapter<TagsBean>(tags + TagsBean()) {
                override fun getView(parent: FlowLayout, position: Int, s: TagsBean): View {
                    val tv = LayoutInflater.from(mContext).inflate(
                        R.layout.recy_single_line_text_new, parent, false
                    ) as TextView
                    if (position >= tags.size) {
                        tv.text = "+ " + mContext.getString(R.string.work_tag_edit_entry)
                        return tv
                    }
                    var tag = s.name
                    if (!TextUtils.isEmpty(s.translated_name)) {
                        tag = tag + "/" + s.translated_name
                    }
                    tv.text = tag
                    return tv
                }
            }
        }
        baseBind.illustTag.setOnTagClickListener { view, position, parent ->
            if (position >= tags.size) {
                // 不必监听变更:V2 的标签区跟着 ObjectPool 走,TagEditSheet 写完池
                // 本页那条 observer 自然重跑 updateIllust,tagSignature 一变就重建 adapter。
                TagEditSheet.show(childFragmentManager, illust.id.toLong())
                return@setOnTagClickListener true
            }
            val intent = Intent(mContext, SearchActivity::class.java)
            intent.putExtra(Params.KEY_WORD, tags[position].name)
            intent.putExtra(Params.INDEX, 0)
            startActivity(intent)
            true
        }
        baseBind.illustTag.setOnTagLongClickListener { view, position, parent ->
            if (position >= tags.size) {
                return@setOnTagLongClickListener true
            }
            val tagBean = tags[position]
            val tagName = tagBean.name
            val searchEntity =
                PixivOperate.getSearchHistory(tagName, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD)
            val isPinned = searchEntity != null && searchEntity.isPinned
            val tagMenuBuilder = MessageDialogBuilder(mContext)
                .setTitle(tagName)
                .addAction(if (isPinned) getString(R.string.string_443) else getString(R.string.string_442)) { dialog, index ->
                    val nextPinned = !isPinned
                    val previewJson =
                        if (nextPinned) buildPinnedTagPreviewJson(tagBean, illust) else null
                    PixivOperate.insertPinnedSearchHistory(
                        tagName, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD, nextPinned, previewJson
                    )
                    Common.showToast(R.string.operate_success)
                    dialog.dismiss()
                }
                .addAction(getString(R.string.string_120)) { dialog, index ->
                    Common.copy(mContext, tagName)
                    dialog.dismiss()
                }
                // 翻译原文（#1054），与 V3 长按菜单同一入口
                .addAction(getString(R.string.string_translate_caption)) { dialog, index ->
                    translateTag(mContext, viewLifecycleOwner.lifecycleScope, tagName)
                    dialog.dismiss()
                }
            // 同义词词典（issue #904）功能总开关：默认关闭，关闭时菜单与本功能存在之前完全一致
            if (Shaft.sSettings.isSynonymDictEnabled) {
                tagMenuBuilder.addAction(getString(R.string.synonym_add_as_synonym)) { dialog, index ->
                    // 长按标签加入词典，备注自动填译文
                    SynonymOperate.showAddAsSynonymDialog(mContext, tagName, tagBean.translated_name)
                    dialog.dismiss()
                }
            }
            tagMenuBuilder.create().show()
            true
        }
    }

    private fun setupInfo(illust: Illust) {
        baseBind.illustSize.text = getString(R.string.string_193, illust.width, illust.height)
        baseBind.illustId.text = getString(R.string.string_194, illust.id)
        baseBind.userId.text = getString(R.string.string_195, illust.user?.id)
        baseBind.illustId.setOnClick { Common.copy(mContext, illust.id.toString()) }
        baseBind.userId.setOnClick { Common.copy(mContext, illust.user?.id.toString()) }
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
        val layoutListener = OnGlobalLayoutListener { refreshPageProgressPill() }
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
            val models = if (isSnapshotMode) {
                // 快照页的图在本地,缩略图别回网上取(离线打开时那边什么也拿不到)。
                val data = snapshotViewerData ?: return@setOnLongClickListener false
                ArtworkThumbsSheet.localModels(illust.page_count) { data.pageFile(it) }
            } else {
                ArtworkThumbsSheet.networkModels(illust)
            }
            if (!ArtworkThumbsSheet.show(this, models, pageProgressIndex.coerceAtLeast(0))) {
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
        pageProgressIndex = current
        val text = getString(R.string.artwork_page_indicator, current + 1, total)
        if (pill.text?.toString() != text) pill.text = text
        pill.isVisible = true
    }

    private fun setupBottomSheet(illust: Illust) {
        val sheetBehavior: BottomSheetBehavior<*> = BottomSheetBehavior.from(baseBind.coreLinear)
        baseBind.coreLinear.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view ?: return
                context ?: return
                val realHeight = baseBind.bottomBar.height +
                        baseBind.viewDivider.height +
                        baseBind.secondLinear.height
                val maxHeight = resources.displayMetrics.heightPixels * 3 / 4
                val params = baseBind.coreLinear.layoutParams
                val slideMaxHeight = Math.min(realHeight, maxHeight)
                params.height = slideMaxHeight
                baseBind.coreLinear.layoutParams = params
                val bottomCardHeight = baseBind.bottomBar.height
                sheetDeltaY = slideMaxHeight - baseBind.bottomBar.height
                sheetBehavior.setPeekHeight(bottomCardHeight, true)

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
        baseBind.illustLike.setOnClick {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.CONTENT, illust)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.ILLUST_LIKERS.key)
            startActivity(intent)
        }
    }

    private fun setupDescription(illust: Illust) {
        val caption = illust.caption
        if (caption.isNullOrEmpty()) {
            baseBind.description.visibility = View.GONE
            return
        }
        baseBind.description.visibility = View.VISIBLE
        // HtmlTextView.setHtml 在 caption 含 <a> 链接时会直接吐出空串（#552）。
        // 换成 androidx HtmlCompat.fromHtml + LinkMovementMethod，文本和可点链接都能正常渲染。
        baseBind.description.text = androidx.core.text.HtmlCompat.fromHtml(
            caption, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
        )
        baseBind.description.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun setupStats(illust: Illust) {
        baseBind.postTime.text = String.format(
            "%s投递", Common.getLocalYYYYMMDDHHMMString(illust.create_date)
        )
        baseBind.totalView.text = (illust.total_view ?: 0).toString()
        baseBind.totalLike.text = (illust.total_bookmarks ?: 0).toString()
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

    override fun onResume() {
        super.onResume()
        if (!isSnapshotMode) {
            autoSnapshotVisit = AutoSnapshotEngine.onArtworkPageVisible(
                illustId = safeArgs.illustId.toLong(),
                type = ObjectPool.get<Illust>(safeArgs.illustId.toLong()).value?.type,
            )
            // 从二级大图页返回后，把进程内已缓存 ORIGINAL 的页直接回填，不重绑列表。
            (baseBind.recyclerView.adapter as? IllustAdapter)?.showCachedOriginalOverlays()
        }
    }

    override fun onPause() {
        if (!isSnapshotMode) {
            AutoSnapshotEngine.onArtworkPageHidden(autoSnapshotVisit)
        }
        autoSnapshotVisit = null
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        aiHelper = IllustAiHelper(this, baseBind.root)
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
                    baseBind.totalLike.text = (latest.total_bookmarks ?: 0).toString()
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
        try {
            baseBind.recyclerView.adapter = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pageProgressPillAttached = false
        pageProgressIndex = -1
        renderedImageSignature = null
        renderedTagSignature = null
        bottomSheetCallbackAttached = false
        sheetDeltaY = 0
        loadedAvatarUrl = null
        aiHelper = null
        super.onDestroyView()
    }

    override fun vertical() {
        baseBind.toolbar.setPadding(0, SystemBarMetrics.statusBarHeight(requireContext()), 0, 0)
    }

    companion object {
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
