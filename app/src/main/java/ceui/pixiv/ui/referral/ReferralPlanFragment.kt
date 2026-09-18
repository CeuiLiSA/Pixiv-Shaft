package ceui.pixiv.ui.referral

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.tencent.mmkv.MMKV
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.navigation.TemplateRoute

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
            override fun retry() {
                if (SessionManager.isLoggedIn) model.refresh() else {
                    ReferralPendingInvite.remember(arguments?.getString(ARG_CODE))
                    startActivity(Intent(requireContext(), TemplateActivity::class.java)
                        .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.LOGIN.key))
                }
            }
            override fun open(kind: ReferralSheetKind, task: ReferralTask?, cardId: Long?) = openSheet(kind, task, null, cardId)
            override fun campaign(campaign: String) { model.campaign(campaign) }
            override fun toggleTheme() {
                val state = model.value
                val dark = ReferralColors(requireContext(), state.darkOverride, state.accentOverride).dark
                model.appearance(!dark, state.accentOverride)
            }
        })

    private fun openSheet(kind: ReferralSheetKind, task: ReferralTask?, code: String?, cardId: Long? = null) {
        if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(SHEET) != null) return
        ReferralPlanSheet.newInstance(kind, task, code, cardId).show(childFragmentManager, SHEET)
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
        if (!state.snapshot.enabled || state.snapshot.inviterUid != null || state.snapshot.view(ReferralTask.INVITE)?.enabled != true) return
        // 问过一次就不再问，**不管有没有找到码**。
        //
        // 只在「找到了」时才置位过一版，那样每一次状态更新（切 tab、换筛选、领完卡刷新）
        // 都会再读一次剪贴板 —— 而 Android 12 起每次读别的 App 复制的内容都会弹一条
        // 系统提示。用户在这一页上点几下就被弹几次。
        asked = true
        val fromLink = parseReferralCode(arguments?.getString(ARG_CODE))
        val code = fromLink ?: clipboardCode() ?: return
        // 自己的码不弹。分享出去一次（复制链接 / 复制邀请文案 / 长按选中那串大字）之后，
        // 剪贴板里就一直躺着一个合法邀请码 —— 而自邀必然被服务端以 self_referral 拒掉。
        // 不挡的话，每次进这一页都是一个注定失败的框。
        if (code == state.snapshot.code) return
        // 剪贴板来的码只主动问一次，且这个「问过」必须跨页面记住：[asked] 只活在一个
        // Fragment 实例里，从抽屉再点一次进来就是新的实例，而剪贴板里那串码不会自己消失。
        // 问过之后绑定入口仍在页脚，想绑随时能绑。
        //
        // 深链不受这条限制：那是用户刚刚点了朋友的邀请链接，意图明确，每次都该问。
        if (fromLink == null) {
            if (ReferralAskedCodes.asked(code)) return
            ReferralAskedCodes.remember(code)
        }
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
        model.refresh()
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

/**
 * 已经主动问过绑定的邀请码。设备本地（MMKV），不进 Settings —— 这是一条「别再打扰他」
 * 的记录，跨设备同步没有意义。
 *
 * 只记最近几个：它是防打扰，不是台账。真正的绑定关系只有服务端有。
 */
private object ReferralAskedCodes {
    private val key get() = "referral_asked_bind_codes_${SessionManager.loggedInUid}"
    private const val MAX = 8

    // 依赖 Shaft.onCreate 里的 MMKV.initialize()，本类只在推介页上被碰到，远在其后。
    private val store: MMKV by lazy { MMKV.defaultMMKV() }

    // 邀请码只有 [23456789A-Z]，逗号拼接不会和码本身撞上。
    private fun codes(): List<String> =
        store.decodeString(key).orEmpty().split(',').filter { it.isNotBlank() }

    fun asked(code: String): Boolean = code in codes()

    fun remember(code: String) {
        store.encode(key, (listOf(code) + codes()).distinct().take(MAX).joinToString(","))
    }
}

/** 深链先遇到登录页时保留邀请码；登录成功到主页后接回推介页，仍由用户确认绑定。 */
object ReferralPendingInvite {
    private const val KEY = "referral_pending_invite"
    @JvmStatic fun remember(raw: String?) {
        parseReferralCode(raw)?.let { MMKV.defaultMMKV().encode(KEY, it) }
    }
    @JvmStatic fun consume(): String? {
        val store = MMKV.defaultMMKV()
        val code = store.decodeString(KEY)
        store.removeValueForKey(KEY)
        return parseReferralCode(code)
    }
}
