package ceui.pixiv.ui.user

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.pixiv.chat.base.toUserMessage
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.ui.common.toUserFeedItems
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.dialog.WitTipDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 「关注列表」页（feeds 框架版，替代 legacy FragmentFollowUser + FollowUserRepo + UAdapter）。
 * 某人正在关注的画师；复用 [UserFeedFragment] 的用户卡渲染 / 关注切换 / LIKED_USER 广播同步 /
 * 点击进画师页。
 *
 * 与 legacy 的行为对齐点：
 * - 两种形态：独立（TemplateActivity「正在关注」，showToolbar=true，自带 toolbar + 跳页菜单）/
 *   内嵌（[ceui.lisa.fragments.FragmentCollection] type=2 的公开/私密两 tab，showToolbar=false，
 *   跳页菜单挂在容器 toolbar 上、转调本页 [showJumpPageDialog]）；
 * - starType（public/private）是**入参**不是页内开关：两个 tab 是两个 Fragment 实例、各自
 *   固定一个 restrict，所以读 arguments 即可，不需要 FollowingIllustFeedFragment 那样的
 *   RestrictViewModel（那页的 restrict 才会被宿主在运行时推进来）；
 * - 懒加载（autoLoad=false）对齐 legacy 的 BaseLazyFragment：不替用户偷偷请求没打开过的私密
 *   关注 tab。**宿主 pager 必须用 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT**，否则懒加载不生效；
 * - 「跳页」：offset 分页语义见 [jumpToPage]。
 */
class FollowUserFeedFragment : UserFeedFragment() {

    // legacy 用 int USER_ID（TemplateActivity 路由 getIntExtra / FragmentCollection 传
    // (int) SessionManager.loggedInUid）；loxia 侧接口收 Long，取用处再转。
    private val userId: Int by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt(Params.USER_ID)
    }
    private val starType: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.STAR_TYPE) ?: Params.TYPE_PUBLIC
    }
    private val showToolbar: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getBoolean(Params.FLAG)
    }

    /**
     * 跳页起点归 VM：数据源被 FeedViewModel 长期持有、比 Fragment 实例活得久，按零捕获约定不能
     * 读 Fragment 字段；本 VM 与 FeedViewModel 同一个 store、同生共死，捕获它既不漏也不会读到
     * 上一代的值（同 FollowingIllustFeedFragment 的 RestrictViewModel）。顺带让跳页结果旋转存活。
     */
    private val jumpViewModel: FollowUserJumpViewModel by viewModels()

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：Fragment 属性先取成局部 val；jump 是兄弟 VM（生命周期 ≥ FeedViewModel），可捕获
        val uid = userId.toLong()
        val restrict = starType
        val jump = jumpViewModel
        pixivFeedSource({
            // offset 只作用于首屏；refresh 时重读 jump.startOffset，翻页照常走响应的 next_url
            Client.appApi.getFollowingUsers(uid, restrict, jump.startOffset.takeIf { it > 0 })
        }) { resp, _ -> resp.user_previews.toUserFeedItems() }
    }

    // 内嵌 FragmentCollection 的 pager（无底栏）时补底部手势条 inset；带 toolbar 独立页由
    // setUpToolbar 自理（裸 feed 形态才生效，不会重复套）。
    override val applyBottomSafeInset: Boolean = true

    // showToolbar 是运行时参数：系统重建 Fragment 只走无参构造，不能靠构造器传 contentLayoutId，
    // 改在这里按参数选骨架（两张布局都带同结构的 feed_root）
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val layoutId = if (showToolbar) R.layout.fragment_toolbar_feed else ceui.pixiv.feeds.R.layout.fragment_feed
        return inflater.inflate(layoutId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!showToolbar) return
        // 独立 toolbar 场景（「正在关注」入口）自己挂跳页菜单；
        // FragmentCollection 容器场景的菜单由容器自己挂（对齐 legacy initToolbar 的分工）。
        val binding = FragmentToolbarFeedBinding.bind(view)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.setText(R.string.string_232)
        binding.toolbar.inflateMenu(R.menu.follow_user_jump)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_jump_page) {
                showJumpPageDialog()
                true
            } else {
                false
            }
        }
    }

    /**
     * 跳到任意页。page 从 1 开始；offset = (page-1)*30 直接交给 Pixiv API。
     *
     * 对齐 legacy `repo.setStartOffset + repo.setNextUrl("") + mRefreshLayout.autoRefresh()`：
     * feeds 里 [ceui.pixiv.feeds.FeedFragment.forceRefresh] = 回顶 + `FeedViewModel.refresh()`，
     * 而 refresh 必定重跑 `source.load(null)`（→ 带新 offset 的首屏请求）并用新响应的 next_url
     * 整代换掉游标——legacy 那句手动「清掉旧 next_url」在框架里是刷新语义自带的，不用再写。
     *
     * view 未创建时 forceRefresh 安全 no-op：此时首屏也还没拉过，会自然用上面刚写进 VM 的新
     * offset（对齐 legacy `mRefreshLayout == null` 直接 return 的守卫）。
     */
    fun jumpToPage(page: Int) {
        jumpViewModel.startOffset = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        forceRefresh()
    }

    /**
     * 跳页流程：先拉 userId 的 user_detail 拿 total_follow_users，用它换算 totalPages 作为输入
     * 上限，再弹输入框。公开（TemplateActivity toolbar / FragmentCollection 容器 toolbar 都调它）。
     *
     * 取数说明：Pixiv user_detail 只回单个 total_follow_users，自己看自己时通常就是公开关注数；
     * 对私人关注 tab 它不一定准，但 Pixiv 也没暴露私人计数，把同一个值作为上限的「软约束」够用：
     * 越界会被预校验拦下、不会真发空请求。
     */
    fun showJumpPageDialog() {
        // 无 view / 未 attach 时安全 no-op（对齐 legacy jumpToPage 的 mModel==null 守卫）：
        // FragmentCollection 视图重建后会重新 new 一份 allPages 数组，而 pager 复用的是
        // FragmentManager 恢复的旧实例，数组里那份孤儿实例永不 attach —— 碰 viewLifecycleOwner 会抛
        if (view == null) return
        val activity = activity ?: return
        if (!activity.isAlive()) return
        val loading = WitTipDialog.Builder(activity)
            .setTipWord(getString(R.string.user_jump_loading))
            .create()
        loading.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val total = try {
                Client.appApi.getUserProfile(userId.toLong()).profile?.total_follow_users ?: 0
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                // 不吞错：legacy 走 NullCtrl → ErrorCtrl 弹映射后的文案，这里保持同样的可见性
                if (view != null) Common.showToast(ex.toUserMessage(requireContext()))
                null
            } finally {
                // 放 finally：视图销毁导致协程取消时也收得掉，loading 不会挂在 activity 上泄漏窗口
                if (loading.isShowing && activity.isAlive()) loading.dismiss()
            }
            if (total == null || view == null || !activity.isAlive()) return@launch
            if (total <= 0) {
                Common.showToast(getString(R.string.follow_user_jump_no_users))
                return@launch
            }
            showPagePicker(activity, (total + PAGE_SIZE - 1) / PAGE_SIZE)
        }
    }

    private fun showPagePicker(activity: Activity, totalPages: Int) {
        val builder = WitDialog.EditTextDialogBuilder(activity)
        builder.setTitle(R.string.user_jump_page_dialog_title)
            .setPlaceholder(getString(R.string.user_jump_page_hint, totalPages))
            .setInputType(InputType.TYPE_CLASS_NUMBER)
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(R.string.sure, WitDialogAction.ActionListener { dialog, _ ->
                val page = builder.editText.text?.toString()?.trim()?.toIntOrNull()
                // 越界不关窗，让用户直接改（对齐 legacy showPagePicker）
                if (page == null || page < 1 || page > totalPages) {
                    Common.showToast(getString(R.string.user_jump_page_range_error, totalPages))
                    return@ActionListener
                }
                dialog.dismiss()
                jumpToPage(page)
            })
            .show()
    }

    private fun Activity.isAlive(): Boolean = !isFinishing && !isDestroyed

    companion object {

        /** Pixiv /v1/user/following 每页固定 30 条。 */
        const val PAGE_SIZE = 30

        @JvmStatic
        @JvmOverloads
        fun newInstance(
            userID: Int,
            starType: String,
            showToolbar: Boolean = false,
        ): FollowUserFeedFragment {
            return FollowUserFeedFragment().apply {
                arguments = Bundle().apply {
                    putInt(Params.USER_ID, userID)
                    putString(Params.STAR_TYPE, starType)
                    putBoolean(Params.FLAG, showToolbar)
                }
            }
        }
    }
}

/** 跳页起点（数据归 VM 约定：旋转存活，feed source 直接读它）。0 = 从第一页拉。 */
class FollowUserJumpViewModel : ViewModel() {
    var startOffset: Int = 0
}
