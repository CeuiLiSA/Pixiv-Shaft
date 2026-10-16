package ceui.pixiv.ui.account

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentAccountSwitchV3Binding
import ceui.lisa.databinding.ItemAccountSwitchRowV3Binding
import ceui.lisa.fragments.SettingsCatalog
import ceui.pixiv.api.model.AccountResponse
import ceui.loxia.User
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Local
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.ui.common.getHumanReadableMessage
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ceui.pixiv.witstudio.theme.WitRowStyle
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 账号管理（V3 / MD3-Expressive），替代 legacy `ceui.lisa.fragments.FragmentLocalUsers`。
 * 承载在 [TemplateActivity]（"账号管理"），从「设置 · 账号 → 账号管理」进入。
 *
 * 页面就三件事：**切号**、**添加账号**、**把多余的那个删掉**。所以版式按重要性铺开——
 * 当前账号做成 hero 卡（大头像 + 名字 + 当前使用中 / Premium 徽章 + 邮箱 + 登录时间），
 * 其他账号是一组 MD3-E 分段行（点行即切），最后一张独行是「添加账号」。legacy 版把所有账号
 * 平铺成同一种带分割线的行、当前账号只多一个绿勾，扫一眼分不出「我现在是谁」，
 * 这是这次重做要解决的第一个问题。
 *
 * 第二个问题是**藏起来的操作**：legacy 的「导出登录信息」是行尾一个没有任何文字说明的箭头
 * 图标，「复制账号名」则藏在长按里。现在统一收进行尾 ⋮ 弹出的 [AccountActionsSheet]，
 * 每条都带文案和图标。
 *
 * 第三个问题是**删不掉**：legacy 想清掉一个多余账号，得先切过去（整个 app 重启一次）再进
 * 设置里退出登录。现在「其他账号」的行尾直接给一个垃圾桶，弹一次确认就把本机这一份连凭证带
 * 收藏镜像、首屏快照、私有配置一起清掉（见 [AccountDataPurge]）。当前账号的 hero 卡**没有**
 * 这个入口 —— 删自己得先退出登录，那是另一条链路。
 *
 * 视觉与设置页同源：行背景走 [WitRowStyle.rowBackground] 并交给
 * [SettingsCatalog.applyThemedRowBg] 染主题色底 + hairline；hero 卡底 / 分节标签 / 徽章
 * 由 [V3Palette] 按当前主题色算，日夜自适配。
 *
 * 行为一律与 legacy 对齐（切号 = [Local.saveUser] + [Common.restart]，导出 JSON 前塞
 * [Params.USER_KEY] 以便登录页识别），本次只重做视觉与信息层级，不动账号数据。
 */
class AccountSwitchV3Fragment : Fragment(R.layout.fragment_account_switch_v3) {

    private val binding by viewBinding(FragmentAccountSwitchV3Binding::bind)

    private val avatarGlide by lazy { Glide.with(this) }

    private lateinit var palette: V3Palette

    /** 一个本地账号：DB 里存的登录态 JSON + 它的入库（= 登录 / 导入）时间。 */
    private class LocalAccount(val model: AccountResponse, val user: User, val loginTime: Long)

    /** 已渲染出来的账号，按 uid 索引 —— [AccountActionsSheet] 的回调只带 uid 回来。 */
    private val accountsByUid = mutableMapOf<Long, LocalAccount>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        palette = V3Palette.from(requireContext())
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        applyInsets()
        applyTheme()
        binding.addAccountRow.setOnClick { openLoginPage() }
        listenSheetResult()
        loadAccounts()
    }

    /**
     * 接 [AccountActionsSheet] 选中的操作。sheet 挂在 childFragmentManager 上，所以监听也注册在
     * 这里；用 viewLifecycleOwner 绑定，视图没了就不再回调。
     */
    private fun listenSheetResult() {
        childFragmentManager.setFragmentResultListener(
            AccountActionsSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val account = accountsByUid[bundle.getLong(AccountActionsSheet.RESULT_USER_ID)]
                ?: return@setFragmentResultListener
            when (bundle.getString(AccountActionsSheet.RESULT_ACTION)) {
                AccountActionsSheet.ACTION_SWITCH -> switchTo(account)
                AccountActionsSheet.ACTION_EXPORT -> exportAccount(account)
                AccountActionsSheet.ACTION_COPY ->
                    Common.copy(requireContext(), account.user.account.orEmpty())
                AccountActionsSheet.ACTION_DELETE -> confirmDelete(account)
            }
        }
    }

    /**
     * 宿主 [TemplateActivity] 走 EdgeToEdge，且 activity_fragment.xml 不碰 inset，
     * 所以顶（状态栏）给 toolbar、底（导航栏 / 手势条）给滚动区，由本页一处派发。
     * 回传原 insets 而不是 CONSUMED：本页没有别的 fitsSystemWindows 视图，留着不影响谁，
     * 但万一以后加了子视图也能照常拿到。
     */
    private fun applyInsets() {
        val dp = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.accountRoot) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.toolbar.updatePadding(top = bars.top)
            binding.scroll.updatePadding(bottom = bars.bottom + (12 * dp).toInt())
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.accountRoot)
    }

    /** 主题色注入：hero 卡底 / 徽章 / 分节标签 / 添加行的图标圆底，全部跟随 app 主题色。 */
    private fun applyTheme() {
        val dp = resources.displayMetrics.density
        binding.currentCard.background = palette.settingsCardBg(28f * dp, (1 * dp).toInt())

        binding.badgeCurrent.background = pill(palette.alpha20)
        binding.badgeCurrent.setTextColor(palette.textAccent)
        // Premium 徽章不跟主题色走：金色是 pixiv 自己的品牌语义，换主题也不该变。
        binding.badgePremium.background = pill(V3Palette.withAlpha(goldColor(), 0.16f))

        binding.othersLabel.setTextColor(palette.textAccent)

        // 图标圆底：在行底 cardFill 上再混一截主题色，比行底稍显色（同设置主页）。
        val iconCircle = ColorUtils.blendARGB(
            palette.cardFill, palette.primary, if (palette.isDark) 0.16f else 0.14f
        )
        (binding.addIconWrap.background.mutate() as? GradientDrawable)?.setColor(iconCircle)
        SettingsCatalog.applyThemedRowBg(binding.addAccountRow)
        SettingsCatalog.applyThemedRowBg(binding.othersEmpty)
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 999f * resources.displayMetrics.density
        setColor(color)
    }

    private fun goldColor(): Int = ContextCompat.getColor(requireContext(), R.color.v3_gold)

    // ── 数据 ────────────────────────────────────────────────────────

    /**
     * 读本地账号表（loginTime 倒序）。坏掉的行（JSON 解析失败 / 没有 user 字段）直接跳过，
     * 不让一条脏数据把整页读空——legacy 是整批 fromJson，一条崩全页崩。
     */
    private fun loadAccounts() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().allUser
                        .mapNotNull { entity ->
                            val model = runCatching {
                                Shaft.sGson.fromJson(entity.userGson, AccountResponse::class.java)
                            }.getOrNull()
                            val user = model?.user ?: return@mapNotNull null
                            LocalAccount(model, user, entity.loginTime)
                        }
                }
            }
            result.onSuccess { render(it) }.onFailure { e ->
                // 本地账号读取 / 解析失败要让用户知道，不能静默显示一页空账号（沿用 legacy）。
                Common.showToast(e.getHumanReadableMessage(requireContext()))
                render(emptyList())
            }
        }
    }

    private fun render(accounts: List<LocalAccount>) {
        val current = currentAccount(accounts)
        accountsByUid.clear()
        (accounts + listOfNotNull(current)).forEach { accountsByUid[it.user.id] = it }
        bindCurrent(current)

        val others = accounts.filter { it.user.id != current?.user?.id }
        binding.othersContainer.removeAllViews()
        others.forEachIndexed { index, account ->
            binding.othersContainer.addView(buildRow(account, index, others.size))
        }
        binding.othersEmpty.isVisible = others.isEmpty()
        binding.othersHint.isVisible = others.isNotEmpty()
        binding.othersEmpty.setText(
            if (current == null) R.string.account_switch_empty
            else R.string.account_switch_only_one
        )
    }

    /**
     * 当前账号 = 账号表里 uid 与登录态一致的那条。DB 里没有它（老版本遗留 / 备份恢复过 /
     * 表被清过）时退回 SharedPreferences 里的登录态，否则 hero 卡会空着，页面看上去像没登录。
     */
    private fun currentAccount(accounts: List<LocalAccount>): LocalAccount? {
        val currentUid = SessionManager.loggedInUid
        accounts.firstOrNull { it.user.id == currentUid }?.let { return it }
        val fallback = Local.getUser() ?: return null
        val user = fallback.user ?: return null
        return LocalAccount(fallback, user, 0L)
    }

    private fun bindCurrent(account: LocalAccount?) {
        binding.currentCard.isVisible = account != null
        val user = account?.user ?: return

        binding.currentName.text = user.name
        binding.currentHandle.text = "@${user.account.orEmpty()}"
        binding.currentMail.text = user.mail_address?.takeIf { it.isNotBlank() }
            ?: getString(R.string.no_mail_address)
        binding.currentPremiumBadge.isVisible = user.is_premium == true
        binding.badgePremium.isVisible = user.is_premium == true
        bindLoginTime(binding.currentLoginTime, account.loginTime)
        avatarGlide.load(GlideUtil.getHead(user))
            .error(R.drawable.no_profile)
            .into(binding.currentAvatar)

        binding.currentMore.setOnClick { showActions(account, isCurrent = true) }
    }

    private fun buildRow(account: LocalAccount, index: Int, total: Int): View {
        val row = ItemAccountSwitchRowV3Binding.inflate(
            layoutInflater, binding.othersContainer, false
        )
        val user = account.user
        row.rowName.text = user.name
        // 账号名与邮箱各占一行：legacy 把两者用「 · 」拼成一行，稍长的邮箱后半截必被省略号
        // 吃掉，扫一眼分不出这台设备上存的到底是哪个账号。账号名本身为空时整行隐掉，
        // 而不是留一个孤零零的「@」。
        row.rowHandle.text = user.account?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
        row.rowHandle.isVisible = row.rowHandle.text.isNotEmpty()
        row.rowMail.text = user.mail_address?.takeIf { it.isNotBlank() }
            ?: getString(R.string.no_mail_address)
        row.rowPremiumBadge.isVisible = user.is_premium == true
        bindLoginTime(row.rowTime, account.loginTime)
        avatarGlide.load(GlideUtil.getHead(user))
            .error(R.drawable.no_profile)
            .into(row.rowAvatar)

        row.accountRow.setBackgroundResource(rowBackground(index, total))
        SettingsCatalog.applyThemedRowBg(row.accountRow)
        row.accountRow.setOnClick { switchTo(account) }
        row.rowMore.setOnClick { showActions(account, isCurrent = false) }
        row.rowDelete.setOnClick { confirmDelete(account) }
        return row.root
    }

    /** MD3-E 分段行背景的四种形态（独行 / 段首 / 段中 / 段尾），同设置页。 */
    private fun rowBackground(index: Int, total: Int): Int =
        WitRowStyle.rowBackground(index, total)

    /**
     * 登录时间。loginTime 是这条账号**入库**（登录 / 剪贴板导入 / 邮箱恢复）的时刻，切号不会
     * 刷新它，所以文案只说「登录于」，不敢说「上次使用」。备份恢复来的行可能没有这个字段，
     * 此时整行隐掉而不是显示 1970。
     */
    private fun bindLoginTime(view: TextView, loginTime: Long) {
        view.isVisible = loginTime > 0L
        if (loginTime > 0L) {
            view.text = getString(R.string.account_switch_login_time, dateFormat.format(Date(loginTime)))
        }
    }

    /**
     * 每次渲染现取一个 formatter：设为常量会把 app 启动那一刻的 locale 焊死（lint ConstantLocale），
     * 而本 app 支持在设置里就地切语言；[SimpleDateFormat] 本身也不是线程安全的。
     * 一页最多几条账号，重建的开销可以忽略。
     */
    private val dateFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // ── 行为 ────────────────────────────────────────────────────────

    /**
     * 切号：写登录态 → 重启回 MainActivity（CLEAR_TASK）→ 关掉自己，与 legacy 完全一致。
     * 整个 app 的很多单例（token / cookie / 首页数据）都是按登录用户初始化的，不重启换不干净。
     */
    private fun switchTo(account: LocalAccount) {
        Local.saveUser(account.model)
        Common.showToast(
            getString(R.string.account_switch_switching, account.user.name.orEmpty())
        )
        Common.restart()
        requireActivity().finish()
    }

    /**
     * 行尾 ⋮：把 legacy 藏在无标注的导出图标 / 长按里的两个操作摆到明处（外加当前账号之外的
     * 「切换」）。选中项由 [AccountActionsSheet] 经 fragment result 回传，见 [listenSheetResult]。
     */
    private fun showActions(account: LocalAccount, isCurrent: Boolean) {
        AccountActionsSheet.show(childFragmentManager, account.user, isCurrent)
    }

    /**
     * 行尾垃圾桶 / sheet 里的「从本地删除」：把这一份账号从本机移除。
     *
     * 删的**只是本机这一份**（凭证 + 收藏镜像 + 首屏快照 + 私有配置），线上 pixiv 账号分毫
     * 不动 —— 文案里必须把这句话讲清楚，否则「删除账号」四个字看着像要销号。
     *
     * 二次确认走项目规范的 Wit 弹窗：正向操作是「取消」，负向（标红）才是「删除」，
     * 与设置页退出登录同一套心智。确认之后真正干活的是 [AccountDataPurge]。
     */
    private fun confirmDelete(account: LocalAccount) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        WitDialog.MessageDialogBuilder(act)
            .setTitle(R.string.account_delete)
            .setMessage(
                getString(R.string.account_delete_message, account.user.name.orEmpty())
            )
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.account_delete_action, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                deleteAccount(account)
            }
            .show()
    }

    /**
     * 后台清完这个账号的一切，再就地刷新列表。
     *
     * 整页重读而不是只从 [accountsByUid] 里摘一条：删掉最后一条时「其他账号」要优雅退化成
     * 空态、剩下的分段行要重算段首/段尾圆角 —— 这些正好都是 [loadAccounts] 已经在做的事，
     * 在内存里改一处反而等于把同一套规则再写一遍。
     *
     * 当前登录的账号完全不受影响：这里删的只可能是「其他账号」那一组的行，
     * 会话、token、cookie 一概不碰，所以不需要重启。
     *
     * 用 applicationContext 而不是 requireContext()：清理是挂起调用，回来时视图可能已经不在了。
     */
    private fun deleteAccount(account: LocalAccount) {
        val uid = account.user.id
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                AccountDataPurge.purgeAccountData(appContext, uid)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Common.showToast(t.getHumanReadableMessage(appContext))
                return@launch
            }
            loadAccounts()
            Common.showToast(
                getString(R.string.account_delete_done, account.user.name.orEmpty())
            )
        }
    }

    /**
     * 导出登录信息到剪贴板。写入 [Params.USER_KEY] 是登录页「从剪切板导入」的识别标记
     * （FragmentLogin 用 `json.contains(USER_KEY)` 判断剪贴板里是不是账号），不能省。
     */
    private fun exportAccount(account: LocalAccount) {
        account.model.local_user = Params.USER_KEY
        Common.copy(requireContext(), Shaft.sGson.toJson(account.model), false)
        Common.showToast(getString(R.string.account_switch_exported))
    }

    private fun openLoginPage() {
        startActivity(
            Intent(requireContext(), TemplateActivity::class.java)
                .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.LOGIN.key)
        )
    }
}
