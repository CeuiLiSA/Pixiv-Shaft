package ceui.lisa.activities

import android.content.DialogInterface
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.ActivityUserV3Binding
import ceui.lisa.helper.UserIllustJumpHelper
import ceui.lisa.http.ErrorCtrl
import ceui.lisa.http.Retro
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.models.UserFollowDetail
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SystemBarMetrics
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.actions.FollowVisibility
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.widgets.FeedBackToTopFab
import ceui.pixiv.widgets.applyV3RefreshTheme
import ceui.lisa.viewmodel.AppLevelState
import ceui.pixiv.services.requireEntityWrapper
import ceui.pixiv.services.appServices
import ceui.lisa.viewmodel.UserViewModel
import ceui.pixiv.api.Client
import ceui.pixiv.utils.Event
import ceui.loxia.Novel
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.widgets.ProgressTextButton
import ceui.loxia.User
import ceui.pixiv.api.model.WebUserDetail
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.realCoverUrl
import ceui.pixiv.ui.common.tryOpenNovelReaderDirect
import ceui.pixiv.ui.user.UserShortcutHelper
import ceui.pixiv.ui.user.UserTagSearchSheet
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayoutMediator
import ceui.pixiv.witstudio.dialog.WitDialog.MenuDialogBuilder
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.NumberFormat
import ceui.pixiv.ui.navigation.TemplateRoute

private const val KEY_TAB_KINDS = "user_v3_tab_kinds"
/**
 * 插画/漫画列表首屏加载完后回调宿主(UserV3WorkTabFragment / 其他实现方),
 * 让「标签筛选条」复用同一份数据聚合 tag,避免再单独打一次 user/illusts。
 * 宿主非本类型时(如 TemplateActivity 独立复用该 fragment)回调被忽略。
 */
interface UserIllustFirstPageListener {
    fun onUserIllustFirstPage(illusts: List<ceui.pixiv.api.model.Illust>)
}

/** 小说侧的同款首屏回调(issue #996:小说 Tab 也有标签筛选条),约定同上。 */
interface UserNovelFirstPageListener {
    fun onUserNovelFirstPage(novels: List<ceui.loxia.Novel>)
}

class UserActivityV3 : BaseActivity<ActivityUserV3Binding>() {

    private var userId = 0L
    private lateinit var mUserViewModel: UserViewModel
    private lateinit var palette: V3Palette

    // Tab 期望顺序:插画 · [漫画] · [漫画系列] · [小说] · [小说系列] · 收藏 · [约稿中] · 资料。
    // 收藏 / 资料 常驻;漫画 / 漫画系列 / 小说 / 小说系列 / 约稿中 是条件 tab(有对应作品 / 开启接受约稿才插)。
    // 小说系列紧跟小说之后(total_novel_series>0 才有,issue #951——旧版 UActivity 的入口在新版加回来);
    // 漫画系列紧跟漫画之后(total_illust_series>0 才有,同一批被落下的旧版入口,做法对齐小说系列)。
    // 约稿中紧贴资料左侧(is_accept_request=true 才有)。
    private enum class TabKind { ILLUST, MANGA, MANGA_SERIES, NOVEL, NOVEL_SERIES, COLLECTION, REQUEST, INFO }

    // 哪些 tab 该展示要等 getUserDetail 返回才知道 (total_manga / total_novels),
    // 所以空列表起步,详情到手后一次性建全量 tab —— 不再「3 tab 先上、条件 tab 后插」闪一下。
    // 旋转 / 进程重建时,从 savedInstanceState 提前恢复完整列表,避免 FragmentStateAdapter
    // 把旋转前保存的 fragment state 当成「已废弃」清掉。
    private val tabKinds = mutableListOf<TabKind>()
    private var pagerAdapter: FragmentStateAdapter? = null
    private var novelBannerLoading = false

    // user/detail 返回的确定数量(插画/漫画/小说/公开插画收藏),>0 时追加到 tab label 后面。
    // 收藏 tab 用 total_illust_bookmarks_public —— 与 header 统计条同源;小说收藏数 API 不给,不凑。
    private val tabCounts = mutableMapOf<TabKind, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前恢复完整 tab 列表,这样 BaseActivity.onCreate 里跑 setupViewPager 时
        // FragmentStateAdapter 看到的 itemId 集合就包含它们,旋转前保存的 fragment state
        // 才会被恢复而不是当成「已废弃」被清掉。首次进页(无保存状态)保持空列表,等详情返回再建。
        savedInstanceState?.getIntArray(KEY_TAB_KINDS)?.let { saved ->
            tabKinds.clear()
            saved.mapTo(tabKinds) { TabKind.entries[it] }
        }
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(KEY_TAB_KINDS, tabKinds.map { it.ordinal }.toIntArray())
    }

    override fun initLayout(): Int = R.layout.activity_user_v3

    override fun initBundle(bundle: Bundle) {
        userId = Params.getUserId(bundle)
    }

    override fun initModel() {
        mUserViewModel = ViewModelProvider(this)[UserViewModel::class.java]
        mUserViewModel.user.observe(this) { data -> displayUser(data) }
        mUserViewModel.webUserDetail.observe(this) { detail ->
            if (detail != null) displayWebUserDetail(detail)
        }

        // 屏蔽/拉黑标记走 Room。别在 onCreate 里同步查 —— 会阻塞主线程，一旦 Room 读连接池
        // 被后台的下载探测占满就 ANR（同 UActivity 现场）。挪到 IO 线程 postValue 回来。
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getAppDatabase(applicationContext).searchDao()
            val muted = dao.getUserMuteEntityByID(userId) != null
            val blocked = dao.getBlockMuteEntityByID(userId) != null
            mUserViewModel.isUserMuted.postValue(muted)
            mUserViewModel.isUserBlocked.postValue(blocked)
        }

        ObjectPool.get<User>(userId).observe(this) { user ->
            updateFollowState(user)
        }
        // 「怎么关的」是另一半事实，有自己的通知渠道（user/follow/detail 补上私密关注时
        // is_followed 没变，上面那条不会响）。见 FollowVisibility.changes。
        FollowVisibility.changes.observe(this) { changed ->
            if (changed == userId) {
                ObjectPool.get<User>(userId).value?.let { updateFollowState(it) }
            }
        }
    }

    override fun initView() {
        palette = V3Palette.from(this)
        baseBind.toolbar.setPadding(0, SystemBarMetrics.statusBarHeight(this), 0, 0)
        baseBind.toolbar.setNavigationOnClickListener { finish() }

        // 内嵌列表(插画/漫画/小说/收藏)默认背景是 fragment_center(日#FFFFFF/夜#2A2A2A),与页面
        // v3_bg(日#FAFAFA/夜#08080C)有肉眼可辨色差,交界处出现色彩断层。运行时把列表背景统一刷成
        // v3_bg(recursive=true 连带覆盖收藏 Tab 的子 fragment),只影响本页,不动共享的 fragment_base_list。
        // feed_root:收藏 Tab 的列表走 feeds 框架(FeedFragment 给裸 fragment_feed 刷 fragment_center),
        // 它的刷新层 id 是 feed_refresh_layout 不叫 refreshLayout,漏网 → 单独覆盖 feed_root 补上。
        // 本回调在 fragment 自身 onViewCreated 之后触发,能盖过 FeedFragment 刚设的 fragment_center。
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: androidx.fragment.app.FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?,
                ) {
                    val bg = getColor(R.color.v3_bg)
                    v.findViewById<View?>(R.id.refreshLayout)?.setBackgroundColor(bg)
                    v.findViewById<View?>(ceui.pixiv.feeds.R.id.feed_root)?.setBackgroundColor(bg)
                }
            }, true
        )

        // Banner 占位渐变跟随主题色(无 banner 图时露出),XML 里的静态紫渐变只是占位。
        baseBind.bannerPlaceholder.background = palette.bannerPlaceholder()

        // Apply theme-colored drawables to follow/unfollow buttons
        val density = resources.displayMetrics.density
        baseBind.follow.background = palette.pillPrimary(999f * density)
        baseBind.unfollow.background = palette.pillSecondary(999f * density, (1 * density).toInt())
        baseBind.unfollow.setTextColor(palette.textSecondary)

        // Toolbar alpha transition on scroll
        baseBind.toolbarLayout.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val offset =
                    baseBind.toolbarLayout.height - SystemBarMetrics.statusBarHeight(this@UserActivityV3) - SystemBarMetrics.toolbarHeight(this@UserActivityV3)
                baseBind.appBar.addOnOffsetChangedListener { _, verticalOffset ->
                    val abs = Math.abs(verticalOffset)
                    when {
                        abs < 15 -> {
                            baseBind.profileHeader.alpha = 1.0f
                            baseBind.toolbarTitle.alpha = 0.0f
                        }

                        offset - abs < 15 -> {
                            baseBind.profileHeader.alpha = 0.0f
                            baseBind.toolbarTitle.alpha = 1.0f
                        }

                        else -> {
                            baseBind.profileHeader.alpha = 1 + verticalOffset.toFloat() / offset
                            baseBind.toolbarTitle.alpha = -verticalOffset.toFloat() / offset
                        }
                    }
                }
                baseBind.toolbarLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })

        // 列表右下角「回顶」悬浮钮(issue #1040,设置里默认关):在建 pager 之前装,各 tab 的
        // feeds 列表(含收藏 tab 嵌套的子列表)建好视图时自动挂上;回顶时连带展开折叠头
        FeedBackToTopFab.installForHost(this, baseBind.appBar)
        setupViewPager()
        setupRefresh()
    }

    /**
     * 手动下拉刷新:只重新请求用户详情 API 本身(头像/banner/名字/统计/导航标签等)。
     * 关注详情、Web 补充信息、插画/漫画作品 tab 一律不动。
     */
    private fun setupRefresh() {
        baseBind.refreshLayout.applyV3RefreshTheme()
        // 转圈圈从 toolbar 之下开始,不顶着透明状态栏。SwipeRefreshLayout 的偏移是
        // 「起点 / 触发终点」两个像素值(SmartRefreshLayout 那边是单个 headerInsetStart),
        // 终点再往下留一段拖拽行程,否则一按住就到位、没有下拉手感。
        //
        // 必须挡住「高度没变就别重设」:setProgressViewOffset 内部会 requestLayout,而
        // addOnLayoutChangeListener 每次 layout 都回调(不管尺寸变没变),不挡就是无限 layout 循环。
        var appliedToolbarHeight = -1
        baseBind.toolbar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val start = v.height
            if (start == appliedToolbarHeight) return@addOnLayoutChangeListener
            appliedToolbarHeight = start
            baseBind.refreshLayout.setProgressViewOffset(
                false,
                start,
                start + DensityUtil.dp2px(64f),
            )
        }
        // CoordinatorLayout 套下拉刷新的官方解法:只在 AppBar 完全展开时 enable,
        // 否则 SwipeRefreshLayout 会拦截掉 AppBar 的展开手势
        //(它的 canChildScrollUp 只问直接子 View CoordinatorLayout,问不到折叠头的状态)。
        baseBind.appBar.addOnOffsetChangedListener { _, verticalOffset ->
            baseBind.refreshLayout.isEnabled = verticalOffset >= 0
        }
        // 这里原本挂了个 setOnChildScrollUpCallback,补 canChildScrollUp 问不到插画 tab
        // 那个内层 tag 滚动容器的问题。筛选条的「+N」展开态移除后,它恒为 2 行、不再可滚,
        // 容器本身也从布局里去掉了 —— 回调随之失去意义,不留空壳。
        baseBind.refreshLayout.setOnRefreshListener { refreshUserDetail() }
    }

    private fun refreshUserDetail() {
        // 用 v2/for_ios:多带 is_accept_request(驱动「约稿中」tab),字段与 UA 无关
        lifecycleScope.launch {
            try {
                val userResponse = withContext(Dispatchers.IO) { Retro.getAppApi().getUserDetailV2(userId) }
                // User 池更新 → updateFollowState 重绑关注按钮;
                // user LiveData 更新 → displayUser 重绑 header UI(幂等)。
                ObjectPool.updateUser(userResponse.user)
                // 下拉刷新后允许重选最新有封面小说(封面可能随新投稿变化)
                mUserViewModel.novelBannerFetched = false
                mUserViewModel.novelBannerNovel = null
                mUserViewModel.user.value = userResponse
                writeBackSelfProfile(userResponse)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorCtrl.handleError(e)
            } finally {
                baseBind.refreshLayout.isRefreshing = false
            }
        }
    }

    /** 看的是自己：把服务端最新资料回写会话，侧边栏/“我的”头像跟着更新。 */
    private fun writeBackSelfProfile(userResponse: UserDetailResponse) {
        if (userId != SessionManager.loggedInUid) return
        SessionManager.ingestFreshUser(userResponse.user, userId)
    }

    override fun initData() {
        baseBind.progress.visibility = View.VISIBLE
        // 用 v2/for_ios:多带 is_accept_request(驱动「约稿中」tab),字段与 UA 无关
        lifecycleScope.launch {
            try {
                val userResponse = withContext(Dispatchers.IO) { Retro.getAppApi().getUserDetailV2(userId) }
                ObjectPool.updateUser(userResponse.user)
                mUserViewModel.user.value = userResponse
                writeBackSelfProfile(userResponse)
                // Record user visit history
                runCatching {
                    (application as? ceui.pixiv.services.ServicesProvider)?.entityWrapper?.visitUser(this@UserActivityV3, userResponse.user)
                }
                appServices().appLevelState.updateFollowUserStatus(
                    userId,
                    if (userResponse.user.is_followed == true)
                        AppLevelState.FollowUserStatus.FOLLOWED
                    else
                        AppLevelState.FollowUserStatus.NOT_FOLLOW
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorCtrl.handleError(e)
                // user/detail 拉不到时兜底常驻 3 tab(插画/收藏/资料),别让整页空白
                buildAllTabs(
                    hasIllust = true,
                    hasManga = false,
                    hasMangaSeries = false,
                    hasNovel = false,
                    hasNovelSeries = false,
                    hasRequest = false,
                )
            } finally {
                baseBind.progress.visibility = View.INVISIBLE
            }
        }
        // user/follow/detail:把「已关注」细分成 公开/非公开 写进 AppLevelState。这个请求
        // 一度被删掉,理由是 getFollowUserLiveData 全仓没有读者 —— 现在关注按钮读它来区分
        // 「已关注」/「悄悄关注中」(issue #997),理由不再成立。user/detail 的 is_followed 只是
        // 个 bool,给不出可见性,而开了「关注作者默认私人关注」之后短按也可能是私密的。
        // 失败不管:精确态拿不到时 followedLabelRes 保守回落「已关注」。
        lifecycleScope.launch {
            try {
                val followDetail = withContext(Dispatchers.IO) { Retro.getAppApi().getFollowDetail(userId) }
                appServices().appLevelState.updateFollowUserStatus(userId, followStatusOf(followDetail))
                // 本地动过的话 writeRemote 自己会丢弃；真写进去了它会发通知，重绘不用这里操心。
                FollowVisibility.writeRemote(userId, followRestrictOf(followDetail))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorCtrl.handleError(e)
            }
        }

        // Fetch supplementary data from Web API (bio HTML, badges, social links)
        lifecycleScope.launch {
            try {
                val resp = Client.webApi.getWebUserDetail(userId)
                resp.body?.let { mUserViewModel.webUserDetail.value = it }
            } catch (e: Exception) {
                timber.log.Timber.d(e, "Web user detail fetch failed for user=$userId")
            }
        }
    }

    override fun hideStatusBar(): Boolean = true

    private fun setupViewPager() {
        pagerAdapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = tabKinds.size

            override fun createFragment(position: Int): Fragment = when (tabKinds[position]) {
                // 插画/漫画/小说 tab 用包装 fragment:标签筛选条在页面内部,跟随 ViewPager 横滑
                //(issue #996:筛选条从插画泛化到漫画/小说,按网页端点段分流)
                TabKind.ILLUST ->
                    UserV3WorkTabFragment.newInstance(userId, UserTagSearchSheet.CATEGORY_ILLUSTS)
                TabKind.MANGA ->
                    UserV3WorkTabFragment.newInstance(userId, UserTagSearchSheet.CATEGORY_MANGA)
                TabKind.MANGA_SERIES -> ceui.pixiv.ui.user.UserMangaSeriesFeedFragment.newInstance(userId, false)
                TabKind.NOVEL ->
                    UserV3WorkTabFragment.newInstance(userId, UserTagSearchSheet.CATEGORY_NOVELS)
                TabKind.NOVEL_SERIES -> ceui.pixiv.ui.user.UserNovelSeriesFeedFragment.newInstance(userId, false)
                TabKind.COLLECTION -> UserV3CollectionFragment.newInstance(userId)
                TabKind.REQUEST -> ceui.pixiv.ui.user.RequestPlanFeedFragment.newInstance(userId)
                TabKind.INFO -> UserV3InfoFragment()
            }

            // 稳定 id 让 notifyItemInserted 不会把已建的 fragment 推倒重来
            override fun getItemId(position: Int): Long = tabKinds[position].ordinal.toLong()

            override fun containsItem(itemId: Long): Boolean =
                tabKinds.any { it.ordinal.toLong() == itemId }
        }
        baseBind.viewPager.adapter = pagerAdapter
        // 不预加载离屏 tab:进主页只拉 user/detail,漫画/小说/收藏等内容按需在滑到该 tab 时
        // 才创建 fragment 发请求。保持 ViewPager2 默认 offscreenPageLimit(懒加载),不再强设成
        // 全 tab 保活 —— 那会一进主页就把所有 tab 的接口全打一遍。

        TabLayoutMediator(baseBind.tabLayout, baseBind.viewPager) { tab, position ->
            tab.text = tabTitle(tabKinds[position])
        }.attach()
    }

    private fun tabTitle(kind: TabKind): CharSequence {
        val base = when (kind) {
            TabKind.ILLUST -> getString(R.string.string_246)
            TabKind.MANGA -> getString(R.string.string_233)
            TabKind.MANGA_SERIES -> getString(R.string.string_230)
            TabKind.NOVEL -> getString(R.string.string_237)
            TabKind.NOVEL_SERIES -> getString(R.string.string_257)
            TabKind.COLLECTION -> getString(R.string.v3_label_bookmarks)
            TabKind.REQUEST -> getString(R.string.request_tab_title)
            TabKind.INFO -> getString(R.string.v3_label_profile_details)
        }
        val count = tabCounts[kind] ?: return base
        return "$base ${NumberFormat.getInstance().format(count)}"
    }

    private fun refreshTabTitles() {
        for (i in tabKinds.indices) {
            baseBind.tabLayout.getTabAt(i)?.text = tabTitle(tabKinds[i])
        }
    }

    private fun updateTabCount(kind: TabKind, count: Int) {
        if (count > 0) tabCounts[kind] = count else tabCounts.remove(kind)
    }

    /** 详情到手后一次性建全量 tab。只在列表为空时生效(旋转恢复/刷新路径不重建)。 */
    private fun buildAllTabs(
        hasIllust: Boolean,
        hasManga: Boolean,
        hasMangaSeries: Boolean,
        hasNovel: Boolean,
        hasNovelSeries: Boolean,
        hasRequest: Boolean,
    ) {
        if (tabKinds.isNotEmpty()) return
        if (hasIllust) tabKinds.add(TabKind.ILLUST)
        if (hasManga) tabKinds.add(TabKind.MANGA)
        if (hasMangaSeries) tabKinds.add(TabKind.MANGA_SERIES) // 紧跟漫画
        if (hasNovel) tabKinds.add(TabKind.NOVEL)
        if (hasNovelSeries) tabKinds.add(TabKind.NOVEL_SERIES) // 紧跟小说
        tabKinds.add(TabKind.COLLECTION)
        if (hasRequest) tabKinds.add(TabKind.REQUEST) // 约稿中紧贴资料左侧
        tabKinds.add(TabKind.INFO)
        pagerAdapter?.notifyItemRangeInserted(0, tabKinds.size)
    }

    /**
     * 条件 tab(漫画/小说)按需插入到指定位置。保留用户当前所在 tab,别因为插入新 tab 把人「踢」走。
     * MANGA 插 ILLUST 之后(index 1),NOVEL 插 COLLECTION 之前 —— 由调用方给 insertIndex。
     */
    private fun ensureConditionalTab(kind: TabKind, insertIndex: Int) {
        if (tabKinds.contains(kind) || insertIndex < 0 || insertIndex > tabKinds.size) return
        val currentId = baseBind.viewPager.currentItem
            .takeIf { it in tabKinds.indices }
            ?.let { tabKinds[it].ordinal.toLong() }
        tabKinds.add(insertIndex, kind)
        pagerAdapter?.notifyItemInserted(insertIndex)
        // 不动 offscreenPageLimit —— 保持懒加载,新插入的 tab 也只在滑到时才请求
        // TabLayoutMediator 自身监听 adapter dataset 变化,会自动 re-populate tabs
        if (currentId != null) {
            val restored = tabKinds.indexOfFirst { it.ordinal.toLong() == currentId }
            if (restored >= 0 && restored != baseBind.viewPager.currentItem) {
                baseBind.viewPager.setCurrentItem(restored, false)
            }
        }
    }

    private fun updateFollowState(user: User) {
        if (baseBind == null) return
        if (user.is_followed == true) {
            baseBind.follow.isVisible = false
            baseBind.unfollow.isVisible = true
            baseBind.unfollow.text = getString(followedLabelRes(userId))
            baseBind.unfollow.setOnClick { unfollowUser(it, userId) }
            baseBind.unfollow.setOnLongClickListener { true }
        } else {
            baseBind.unfollow.isVisible = false
            baseBind.follow.isVisible = true
            baseBind.follow.setOnClick { followUser(it, userId, PixivActions.defaultFollowRestrict()) }
            baseBind.follow.setOnLongClickListener {
                followUser(it as ProgressTextButton, userId, Params.TYPE_PRIVATE)
                true
            }
        }
    }

    private fun displayUser(data: UserDetailResponse) {
        val isSelf = userId == SessionManager.loggedInUid
        val profile = data.profile
        val user = data.user

        // 先记数量再建/插 tab —— TabLayoutMediator populate 时 tabTitle 才带得上数字。
        // 归 0 要移除,不然刷新后 label 挂着旧数字和 header 统计条打架。
        updateTabCount(TabKind.ILLUST, profile.total_illusts)
        updateTabCount(TabKind.MANGA, profile.total_manga)
        updateTabCount(TabKind.MANGA_SERIES, profile.total_illust_series)
        updateTabCount(TabKind.NOVEL, profile.total_novels)
        updateTabCount(TabKind.NOVEL_SERIES, profile.total_novel_series)
        updateTabCount(TabKind.COLLECTION, profile.total_illust_bookmarks_public)

        // 纯小说创作者(插画 0 + 漫画 0 + 小说>0):首页藏掉「插画作品」「漫画作品」两个空 tab。
        // 漫画 tab 本就 manga>0 才建,这里等价于额外把插画 tab 也隐掉。
        val isNovelistOnly =
            profile.total_illusts == 0 && profile.total_manga == 0 && profile.total_novels > 0
        // 开启「接受约稿」才展示「约稿中」tab(紧贴资料左侧)
        val isAcceptRequest = user.is_accept_request == true

        if (tabKinds.isEmpty()) {
            // 首次进页:详情到手,一次性建全量 tab(有漫画/小说作品 / 开启约稿才含对应 tab)
            buildAllTabs(
                hasIllust = !isNovelistOnly,
                hasManga = profile.total_manga > 0,
                hasMangaSeries = profile.total_illust_series > 0,
                hasNovel = profile.total_novels > 0,
                hasNovelSeries = profile.total_novel_series > 0,
                hasRequest = isAcceptRequest,
            )
        } else {
            // 旋转恢复 / 下拉刷新:列表已在,只按需补插条件 tab。MANGA 在插画之后,MANGA_SERIES 紧跟
            // MANGA(漫画 tab 不在时退到收藏之前的位置,等价于落在漫画本该在的地方),NOVEL / NOVEL_SERIES
            // 在收藏之前(NOVEL_SERIES 先插使其落在 NOVEL 之后),REQUEST 在资料之前(紧贴资料左侧)。
            if (profile.total_manga > 0) ensureConditionalTab(TabKind.MANGA, 1)
            if (profile.total_illust_series > 0) {
                val mangaIndex = tabKinds.indexOf(TabKind.MANGA)
                ensureConditionalTab(
                    TabKind.MANGA_SERIES,
                    if (mangaIndex >= 0) mangaIndex + 1 else tabKinds.indexOf(TabKind.COLLECTION),
                )
            }
            if (profile.total_novels > 0) {
                ensureConditionalTab(TabKind.NOVEL, tabKinds.indexOf(TabKind.COLLECTION))
            }
            if (profile.total_novel_series > 0) {
                ensureConditionalTab(TabKind.NOVEL_SERIES, tabKinds.indexOf(TabKind.COLLECTION))
            }
            if (isAcceptRequest) {
                ensureConditionalTab(TabKind.REQUEST, tabKinds.indexOf(TabKind.INFO))
            }
        }
        refreshTabTitles()

        // Banner
        val bannerUrl = profile.background_image_url
        if (!bannerUrl.isNullOrEmpty()) {
            showBanner(bannerUrl) { openImageDetail(bannerUrl, "user_${user.id}_profile_banner") }
        } else if (isNovelistOnly) {
            // 纯小说作者通常没有 profile 背景图(background_image_url 为空)：
            // 拉一页小说列表，挑最新一篇有真实封面(非占位图)的作品当 banner。
            val picked = mUserViewModel.novelBannerNovel
            when {
                // 已选过：重建后直接重绑,不重复请求(选择存活在 ViewModel 上)
                picked != null -> bindNovelCoverBanner(picked)
                !mUserViewModel.novelBannerFetched -> loadNovelCoverBanner()
            }
        }

        // Avatar
        Glide.with(mContext).load(GlideUtil.getHead(user)).into(baseBind.userAvatar)
        val avatarUrl = user.profile_image_urls?.findMaxSizeUrl()
        if (!avatarUrl.isNullOrEmpty()) {
            baseBind.userAvatar.setOnClickListener {
                openImageDetail(avatarUrl, "user_${user.id}_avatar")
            }
        }

        // Premium
        if (user.is_premium == true) {
            baseBind.premiumRing.visibility = View.VISIBLE
            baseBind.premiumBadge.visibility = View.VISIBLE
        }

        // Name, handle
        baseBind.userName.text = user.name
        baseBind.userHandle.text = "@${user.account}"
        baseBind.toolbarTitle.text = user.name

        baseBind.userName.setOnClickListener { Common.copy(mContext, user.id.toString()) }
        baseBind.userName.setOnLongClickListener {
            Common.copy(mContext, user.name)
            true
        }

        // Follow layout
        if (isSelf) {
            baseBind.followLayout.visibility = View.GONE
        }

        // More menu
        baseBind.moreAction.visibility = View.VISIBLE
        baseBind.moreAction.setOnClickListener { showMoreMenu(data, isSelf) }

        // 内联统计条:关注 · 好P友 · 收藏,每段可点
        val fmt = NumberFormat.getInstance()
        baseBind.statFollowingNum.text = fmt.format(profile.total_follow_users)
        baseBind.statMypixivNum.text = fmt.format(profile.total_mypixiv_users)

        baseBind.statFollowing.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.FOLLOWING.key)
            startActivity(intent)
        }
        baseBind.statMypixiv.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NICE_FRIENDS.key)
            startActivity(intent)
        }

        // 收藏数不再进统计条 —— 「收藏」tab label 自带数字(updateTabCount)

        // 标签筛选条(issue #569)不再单独请求 —— 改由插画列表首屏回调 onUserIllustFirstPage 驱动,复用同一份数据
    }

    /**
     * 显示 header banner。40% 黑色 overlay 贴在图片像素上 —— 用 colorFilter 而不是单独 scrim view，
     * 和 CollapsingToolbarLayout 的 parallax + contentScrim 不会打架。
     */
    private fun showBanner(url: String, onClick: () -> Unit) {
        baseBind.bannerImage.visibility = View.VISIBLE
        baseBind.bannerImage.colorFilter = PorterDuffColorFilter(0x66000000, PorterDuff.Mode.SRC_ATOP)
        Glide.with(mContext).load(GlideUtil.getUrl(url)).into(baseBind.bannerImage)
        baseBind.bannerImage.setOnClickListener { onClick() }
    }

    /** 拿某篇小说的封面当 banner，点击进这篇小说（先过「列表点击直接进正文」设置）。 */
    private fun bindNovelCoverBanner(novel: Novel) {
        val cover = novel.realCoverUrl ?: return
        showBanner(cover) {
            if (tryOpenNovelReaderDirect(novel.id)) return@showBanner
            startActivity(Intent(this, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_DETAIL.key)
                putExtra(Params.NOVEL_ID, novel.id)
            })
        }
    }

    /**
     * 纯小说作者页 banner 兜底：拉一页 /v1/user/novels，挑最新一篇有真实封面（非占位图）的作品。
     * 选中结果记在 ViewModel 上，Activity 重建后直接重绑、不重复请求；下拉刷新时页面清掉它重选。
     * 请求失败静默保留渐变占位，且不落 fetched 标记，下次进 displayUser 还能再试。
     */
    private fun loadNovelCoverBanner() {
        if (novelBannerLoading) return
        novelBannerLoading = true
        lifecycleScope.launch {
            val resp = try {
                Client.appApi.getUserCreatedNovels(userId)
            } catch (ce: CancellationException) {
                // 页面销毁 → lifecycleScope 取消：必须放行，否则协程取消被当成一次「请求失败」吞掉
                throw ce
            } catch (ex: Exception) {
                Timber.w(ex, "拉取小说封面 banner 失败")
                null
            } finally {
                novelBannerLoading = false
            } ?: return@launch

            mUserViewModel.novelBannerFetched = true
            val novel = resp.novels.firstOrNull { it.realCoverUrl != null } ?: return@launch
            mUserViewModel.novelBannerNovel = novel
            bindNovelCoverBanner(novel)
        }
    }

    private fun displayWebUserDetail(detail: WebUserDetail) {
        val dp = resources.displayMetrics.density
        val isSelf = userId == SessionManager.loggedInUid

        // ── Badges row ───────────────────────────────────────────────
        var showBadges = false

        // "互相关注" badge — followedBack means the user follows us back
        if (!isSelf && detail.followedBack == true) {
            baseBind.badgeFollowsYou.isVisible = true
            baseBind.badgeFollowsYou.background = makeBadgeBg(dp, palette.alpha20)
            showBadges = true
        }

        // 好P友 badge
        if (detail.isMypixiv == true) {
            baseBind.badgeMypixiv.isVisible = true
            baseBind.badgeMypixiv.background = makeBadgeBg(dp, palette.alpha20)
            showBadges = true
        }

        // Official badge — pinned next to the name, not in badges_row.
        if (detail.official == true) {
            baseBind.badgeOfficial.isVisible = true
        }

        if (showBadges) {
            baseBind.badgesRow.isVisible = true
        }

        // ── Message button ───────────────────────────────────────────
        // 1v1 chat over shaft-api-v2 (anonymous of pixiv; identity = uid only,
        // see docs/ws-chat-integration.md). Show only when:
        //   - not myself
        //   - I'm logged in (ShaftHmacAuthProvider needs SessionManager.loggedInUid > 0)
        //   - pixiv's `canSendMessage` flag is true (preserves existing UX guard)
        if (!isSelf && detail.canSendMessage == true && ceui.pixiv.session.SessionManager.isLoggedIn) {
            baseBind.msgBtn.isVisible = true
            baseBind.msgBtn.background = makeBadgeBg(dp, palette.alpha20)
            baseBind.msgBtn.imageTintList = android.content.res.ColorStateList.valueOf(palette.textAccent)
            baseBind.msgBtn.setOnClick {
                val intent = android.content.Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.CHAT.key)
                intent.putExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, userId)
                startActivity(intent)
            }
        }
    }

    private fun makeBadgeBg(dp: Float, strokeColor: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 999f * dp
            setColor(0x0AFFFFFF)
            setStroke((1 * dp).toInt(), strokeColor)
        }
    }

    // 标签筛选条(issue #569)已整体迁入 UserV3WorkTabFragment —— 它住在插画/漫画/小说 Tab 页面
    // 内部,跟随 ViewPager 横滑,数据复用列表首屏(onUserIllustFirstPage 等),进主页零额外请求。

    private fun showMoreMenu(data: UserDetailResponse, isSelf: Boolean) {
        val isMuted = mUserViewModel.isUserMuted.value == true
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (data.profile.total_illusts > 0) {
            labels.add("跳转到插画…")
            actions.add { jumpTo(data.user.id, UserIllustJumpHelper.Kind.ILLUST, TemplateRoute.USER_ILLUSTS) }
        }
        if (data.profile.total_manga > 0) {
            labels.add("跳转到漫画…")
            actions.add { jumpTo(data.user.id, UserIllustJumpHelper.Kind.MANGA, TemplateRoute.USER_MANGA) }
        }
        labels.add(getString(R.string.string_436)) // 相关用户
        actions.add {
            val intent = Intent(this, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, data.user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.RELATED_USERS.key)
            startActivity(intent)
        }
        if (data.profile.total_illusts > 0) {
            labels.add(getString(R.string.bulk_user_menu_download_all_illust))
            actions.add {
                startBatchFetch(
                    userIdLong = data.user.id,
                    type = ceui.pixiv.db.queue.WorkType.ILLUST,
                    authorName = data.user.name ?: "user",
                )
            }
        }
        if (data.profile.total_manga > 0) {
            labels.add(getString(R.string.bulk_user_menu_download_all_manga))
            actions.add {
                startBatchFetch(
                    userIdLong = data.user.id,
                    type = ceui.pixiv.db.queue.WorkType.MANGA,
                    authorName = data.user.name ?: "user",
                )
            }
        }
        labels.add(getString(R.string.bulk_user_menu_open_download_manager))
        actions.add {
            val intent = Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DOWNLOAD_MANAGER.key) // route key
            startActivity(intent)
        }
        // issue #1027:把作者主页固定成桌面图标。桌面不支持时(部分三方 launcher)不摆这一项
        if (UserShortcutHelper.isSupported(this)) {
            labels.add(getString(R.string.add_to_home_screen))
            actions.add { UserShortcutHelper.pin(this, data.user) }
        }
        // 置顶作者:把常看的画师钉在搜索首页,和「置顶标签」各占一段,互不挤占。
        val entityWrapper = requireEntityWrapper()
        val userPinned = entityWrapper.isUserPinned(data.user.id)
        labels.add(getString(if (userPinned) R.string.unpin_user else R.string.pin_user))
        actions.add {
            if (userPinned) {
                entityWrapper.unpinUser(applicationContext, data.user.id)
                Common.showToast(getString(R.string.unpin_user_success))
            } else {
                entityWrapper.pinUser(applicationContext, data.user)
                Common.showToast(getString(R.string.pinned_user_added, data.user.name.orEmpty()))
            }
        }
        if (!isSelf) {
            labels.add(
                if (isMuted) getString(R.string.cancel_block_this_users_work)
                else getString(R.string.block_this_users_work)
            )
            actions.add {
                // mute switch lives in UserV3InfoFragment now; just push state via shared
                // UserViewModel and the fragment's observer keeps the switch in sync.
                if (isMuted) {
                    PixivOperate.unMuteUser(data.user)
                    mUserViewModel.isUserMuted.value = false
                } else {
                    PixivOperate.muteUser(data.user)
                    mUserViewModel.isUserMuted.value = true
                }
                mUserViewModel.refreshEvent.value = Event(100, 0L)
            }
            // issue #959: pixiv 账号级「拉黑」,和上面那条纯本地的「屏蔽」是两回事,菜单里并列摆着。
            labels.add(getString(R.string.pixiv_block_menu))
            actions.add {
                ceui.pixiv.ui.user.PixivBlockOperate.showBlockDialog(
                    this, data.user.id, data.user.name.orEmpty()
                )
            }
        }
        if (labels.isEmpty()) return

        MenuDialogBuilder(mActivity)
            .addItems(labels.toTypedArray()) { dialog: DialogInterface, which: Int ->
                dialog.dismiss()
                actions.getOrNull(which)?.invoke()
            }
            .show()
    }

    private fun startBatchFetch(userIdLong: Long, type: String, authorName: String) {
        val typeLabel = getString(
            if (type == ceui.pixiv.db.queue.WorkType.MANGA) R.string.bulk_type_manga
            else R.string.bulk_type_illust
        )
        val source = ceui.pixiv.ui.bulk.AuthorWorksSource(
            userId = userIdLong,
            type = type,
        )
        val taskName = getString(R.string.bulk_task_name, authorName, typeLabel)
        ceui.pixiv.ui.bulk.FetchProgressDialog.show(
            supportFragmentManager,
            ceui.pixiv.ui.bulk.bulkEnqueueIllusts(this, source, taskName),
        )
        // 不在这里 notifyNewItems —— 等 fetcher 全部抓完才统一唤醒消费者
    }

    private fun jumpTo(userID: Long, kind: UserIllustJumpHelper.Kind, route: TemplateRoute) {
        UserIllustJumpHelper.showJumpDialog(this, userID, kind) { offset, pickedDate ->
            if (isFinishing || isDestroyed) return@showJumpDialog
            val intent = Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, route.key)
            intent.putExtra(Params.USER_ID, userID)
            intent.putExtra(Params.INITIAL_OFFSET, offset)
            if (pickedDate != null) intent.putExtra(Params.TARGET_DATE, pickedDate)
            startActivity(intent)
        }
    }

    private fun openImageDetail(imageUrl: String, saveName: String) {
        startActivity(Intent(mContext, TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.IMAGE_DETAIL.key)
            putExtra(Params.URL, imageUrl)
            putExtra(Params.TITLE, saveName)
        })
    }
}
