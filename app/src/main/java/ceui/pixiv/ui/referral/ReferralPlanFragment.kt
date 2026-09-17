package ceui.pixiv.ui.referral

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels

/**
 * App 推介计划。
 *
 * 版面是 `mockup/referral-plan` 那套确认过的视觉，数据全部来自 pixshaft-api：这一页
 * 上的每一个数字都对应真的 PRO 天数，客户端不自己算奖励（见 [ReferralRepository]）。
 *
 * 除了页面本身，它还负责分享链路的收口：带着邀请码进来的深链、以及落地页写进剪贴板
 * 的那串码，都在这里变成一次「要不要绑定」的询问。这是整条链路上最容易掉人的一步 ——
 * APK 不走应用商店，拿不到安装归因，新人必须自己把码填进来。
 */
class ReferralPlanFragment : Fragment() {
    private val model by viewModels<ReferralPlanViewModel>()

    /** 已经问过一次了。绑定入口一直在页脚，不该每次回到前台都弹一遍。 */
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean(STATE_ASKED) ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ReferralPageView(requireContext(), object : ReferralPageActions {
            override fun back() { requireActivity().onBackPressedDispatcher.onBackPressed() }
            override fun tab(tab: ReferralTab) { model.tab(tab) }
            override fun filter(filter: ReferralFilter) { model.filter(filter) }
            override fun retry() { model.refresh() }
            override fun open(kind: ReferralSheetKind, task: ReferralTask?) = openSheet(kind, task, null)
            override fun toggleTheme() {
                val state = model.value
                val dark = ReferralColors(requireContext(), state.darkOverride, state.accentOverride).dark
                model.appearance(!dark, state.accentOverride)
            }
        })

    private fun openSheet(kind: ReferralSheetKind, task: ReferralTask?, code: String?) {
        if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(SHEET) != null) return
        ReferralPlanSheet.newInstance(kind, task, code).show(childFragmentManager, SHEET)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model.state.observe(viewLifecycleOwner) { state ->
            (view as ReferralPageView).render(state)
            val dark = ReferralColors(requireContext(), state.darkOverride, state.accentOverride).dark
            WindowCompat.getInsetsController(requireActivity().window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            maybeAskToBind(state)
        }
    }

    /**
     * 带码进来的人，直接把绑定弹窗推到他面前。
     *
     * 码有两个来源，按可信度排：深链（`shaftintent://referral?code=…`，落地页上「已经装了？
     * 直接打开 App」那个按钮）和剪贴板（落地页进页面时就写进去了）。两个都只在**还没绑过**
     * 且活动开着时才用 —— 绑定是一次性不可改的，对着一个绑过的人弹这个框只会撞一句
     * 「你已经绑过了」。
     *
     * 等状态回来之后才问：绑定要打服务端，在还不知道他是不是已经绑过的时候弹框，等于
     * 拿一次必然失败的请求去打扰人。
     */
    private fun maybeAskToBind(state: ReferralUiState) {
        if (asked || state.loading) return
        if (!state.snapshot.enabled || state.snapshot.inviterUid != null) return
        // 问过一次就不再问，**不管有没有找到码**。
        //
        // 只在「找到了」时才置位过一版，那样每一次状态更新（切 tab、换筛选、领完卡刷新）
        // 都会再读一次剪贴板 —— 而 Android 12 起每次读别的 App 复制的内容都会弹一条
        // 系统提示。用户在这一页上点几下就被弹几次。
        asked = true
        val code = parseReferralCode(arguments?.getString(ARG_CODE)) ?: clipboardCode() ?: return
        openSheet(ReferralSheetKind.BIND, null, code)
    }

    private fun clipboardCode(): String? {
        val clip = (context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        // coerceToText 会读剪贴板，Android 12+ 会因此给用户弹一次「已读取剪贴板」提示。
        // 只在进入推介页、且确定还没绑定时读一次，不在 App 启动时到处读。
        val text = runCatching { clip.getItemAt(0).coerceToText(requireContext()).toString() }.getOrNull()
        return parseReferralCode(text)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_ASKED, asked)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        model.refreshTime()
    }

    companion object {
        private const val SHEET = "referral_sheet"
        private const val STATE_ASKED = "asked"
        const val ARG_CODE = "referral_code"

        /** @param code 深链里带来的邀请码，没有就传 null。 */
        @JvmStatic
        fun newInstance(code: String?): ReferralPlanFragment =
            ReferralPlanFragment().apply { arguments = bundleOf(ARG_CODE to code) }
    }
}
