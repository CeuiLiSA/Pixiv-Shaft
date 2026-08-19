package ceui.pixiv.ui.usage

import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.databinding.FragmentNana7miUsageBinding
import ceui.lisa.fragments.BaseFragment
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Nana7miCheckoutClient
import ceui.loxia.Nana7miCheckoutResult
import ceui.loxia.Nana7miPlan
import ceui.loxia.Nana7miQuotaResult
import ceui.loxia.Nana7miQuotaWindow
import ceui.loxia.PLAN_MAX
import ceui.loxia.PLAN_PRO
import ceui.loxia.fetchNana7miQuota
import ceui.loxia.requestAfdianCheckout
import ceui.pixiv.config.RemoteAppConfig
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitRowStyle
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/**
 * 借号用量页 —— 服务端两只配额桶（`src/account.js` 的 5 小时 / 每周）的只读视图。
 *
 * 版式借 Claude Code 的 usage 页：一行一只桶，标题与百分比同排，下面是重置倒计时和进度条。
 * 落到本仓库的语汇上就是 MD3-E 分段行（[WitRowStyle]）+ V3 中性文字色：整页只有百分比和
 * 进度条带颜色（[usageAccent] 的状态色），其余一律中性 —— 强调色只给这一页真正的读数。
 *
 * 三个前提，改这页时别丢：
 *  - **桶不是滑动窗口**：到 `resetsAt` 整份额度归零，所以文案是「X 后重置」，不是「X 后 +1 次」。
 *  - **倒计时按服务端时间算**：拿响应里的 `serverTime` 当基准，设备时钟不可信。
 *  - **只给百分比，不报绝对次数**：上限是服务端可调的运营参数，报出去就成了用户预期，
 *    以后调小就变成「砍额度」。用户真正要问的两件事——还能不能用、什么时候回满——
 *    百分比和重置时间都答了。
 *
 * 打开这页不会消耗任何额度：`/v1/account/nana7mi/quota` 是纯读接口，也不会开新的计时桶。
 */
class Nana7miUsageFragment : BaseFragment<FragmentNana7miUsageBinding>() {

    private companion object {
        /** 进入警戒色阶的百分比。以下是主题色，从这里开始 黄 → 橙 → 红。 */
        const val WARN_FROM = 60

        /**
         * 这一页在下单请求里的署名。服务端把它连同 App 版本、机型一起记进购买台账，
         * 于是「主动翻到用量页来买」和「被额度挡住才来买」在后台是分得开的两件事。
         */
        const val CHECKOUT_ENTRY = "usage_page"

        /**
         * 在售的档位，按价格从低到高。这份表要和爱发电上真实挂着的方案对齐 —— app 这边
         * 只是把价格提前摆出来，收款和发货都以爱发电回来的订单为准，两边不一致会当场翻车。
         *
         * [Plan.bestValue] 不是随手贴的标签：20x 的单价是 ¥2/x，5x 是 ¥4/x，确实更划算。
         * 改价之后记得重算，别让这个徽章说谎。
         *
         * 卖点只说**倍率**，不说绝对次数 —— 和这页顶上那条规矩是同一条：上限是服务端可调的
         * 运营参数，写进卖点就成了承诺，以后调小就变成「砍额度」。
         */
        val PLANS = listOf(
            Plan(
                key = PLAN_PRO,
                titleRes = R.string.nana7mi_usage_plan_5x_title,
                descRes = R.string.nana7mi_usage_plan_5x_desc,
                monthlyYuan = 20,
                multiplier = 5,
                brand = Brand(a1 = 0xFF5BB0FF.toInt(), tint = 0xFF2E7BD6.toInt(),
                    base = 0xFF0B1526.toInt()),
            ),
            Plan(
                key = PLAN_MAX,
                titleRes = R.string.nana7mi_usage_plan_20x_title,
                descRes = R.string.nana7mi_usage_plan_20x_desc,
                monthlyYuan = 40,
                multiplier = 20,
                brand = Brand(a1 = 0xFFFFC85C.toInt(), tint = 0xFFC77A16.toInt(),
                    base = 0xFF150E04.toInt()),
                bestValue = true,
            ),
        )
    }

    private class Plan(
        /** 服务端的档位 key，用来认出「这一行就是他买的那一档」。 */
        val key: String,
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int,
        val monthlyYuan: Int,
        val multiplier: Int,
        val brand: Brand,
        val bestValue: Boolean = false,
    )

    /**
     * 一档的固定品牌色，抄自订阅封面（pixshaft-covers/cover.html）——**不跟主题色走**。
     *
     * 这两档在爱发电的方案封面、商品图、对外物料上就是这两套颜色：5x 是蓝转青，20x 是
     * 金转橙。用户是先在外面看到封面才进来买的，App 里的卡片要是跟着主题色变，同一档
     * 在两处就是两个东西了。所以这里写死，只按日夜切换取哪一档色阶。
     *
     * 封面是深底设计，色值不能照搬到浅色界面上，所以按**角色**映射而不是按值搬：
     *  - [tint] 中间调：实心面（按钮、档位牌）和浅色主题下的文字。深底上的 [a1] 放到
     *    白底上会糊，而 [tint] 配白字是仓库里实心按钮一贯的配法
     *  - [a1] 亮调：只给深色主题下的文字
     *  - [base] 封面底色：只用来派生水波和分隔线那几层低不透明度
     */
    private class Brand(
        @ColorInt val a1: Int,
        @ColorInt val tint: Int,
        @ColorInt val base: Int,
    ) {
        /** 文字用的强调色：浅色主题取中间调，深色主题取亮调。 */
        @ColorInt fun text(isDark: Boolean): Int = if (isDark) a1 else tint
    }

    /** 正在换下单链接的那一档。同时也是防连点：一次只允许一笔在飞。 */
    private var checkoutInFlight: String? = null

    /** initData 那次加载不算「回到这页」，见 [onResume]。 */
    private var everResumed = false

    /**
     * 当前档位。先用冷启动缓存的那份（第一帧就有答案），额度接口返回后换成服务端此刻认的。
     *
     * 认的是 [Nana7miPlan.owned]（他买了什么），不是 `key`（按什么计量）—— 试运营期间
     * 服务端给所有人抬到 Max，拿 `key` 去高亮会把没付钱的人显示成 Max 订户。
     */
    private var plan: Nana7miPlan? = null

    override fun initLayout() {
        mLayoutID = R.layout.fragment_nana7mi_usage
    }

    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }
        baseBind.errorState.setOnClickListener { load() }
        val palette = V3Palette.from(mContext)
        baseBind.errorAction.setTextColor(palette.primary)
        plan = RemoteAppConfig.nana7miPlan
        bindPlanLabel()
        setupPlans(palette)
        load()
    }

    /**
     * 标题旁边的「当前方案 Max（20x）」。
     *
     * 说的是他**买的那一档**（[Nana7miPlan.ownedDisplay]）。计量档位在试运营期间会被服务端
     * 抬高，拿那个来写「当前方案」等于告诉一个没付钱的人他买了 Max。
     *
     * 拿不到就整块 gone。空着一块，比写个假的让人误判自己订阅状态强。
     */
    private fun bindPlanLabel() {
        val owned = plan?.ownedDisplay
        baseBind.usagePlanLabel.apply {
            if (owned == null) {
                visibility = View.GONE
            } else {
                text = getString(R.string.nana7mi_usage_plan_label_current, owned.first, owned.second)
                visibility = View.VISIBLE
            }
        }
    }

    /**
     * 底部订阅区。按定价页排，不按设置项排 —— 版式理由写在 item_nana7mi_plan_card.xml 里。
     *
     * 这里只做运行时才能做的事：把卡片底、分隔线、按钮底从主题色派生出来，所以十档预设和
     * 自定义 HEX 都自动跟随。方案表是数据不是布局：加档、改价、改文案只动 [PLANS]。
     */
    private fun setupPlans(palette: V3Palette) {
        val host = baseBind.planRows
        host.removeAllViews()
        val inflater = LayoutInflater.from(mContext)
        PLANS.forEach { plan ->
            val card = inflater.inflate(R.layout.item_nana7mi_plan_card, host, false)
            bindPlan(card, plan, palette)
            host.addView(card)
        }
    }

    private fun bindPlan(card: View, plan: Plan, palette: V3Palette) {
        val current = this.plan?.takeIf { it.isPaid && it.owned == plan.key }
        val accent = plan.brand.text(palette.isDark)
        // 已订阅的那一档整张卡描一道实边：一屏两张卡，得让「我在这一档上」一眼看出来，
        // 而不是靠读徽章上那四个字。
        card.background = planCardBackground(plan.brand, highlighted = current != null)

        card.findViewById<TextView>(R.id.plan_title).apply {
            text = getString(plan.titleRes)
            setTextColor(accent)
        }
        card.findViewById<TextView>(R.id.plan_tier_pill).apply {
            // 直接用服务端的档位 key 大写，不进 strings：PRO / MAX 是商品名，不是要翻译的
            // 文案，而且这个词必须和服务端、后台、客服口径逐字一致——落到 7 份翻译里迟早会
            // 有一份被改成别的说法。
            text = plan.key.uppercase(Locale.ROOT)
            background = pillBackground(plan.brand)
            setTextColor(ContextCompat.getColor(mContext, R.color.always_white))
        }
        card.findViewById<TextView>(R.id.plan_desc).text = when {
            // 已经买了这一档：卖点换成他真正关心的那件事——什么时候到期。
            current != null -> expiryText(current)
            else -> getString(plan.descRes)
        }
        card.findViewById<TextView>(R.id.plan_price).apply {
            text = getString(R.string.nana7mi_usage_plan_price, plan.monthlyYuan)
            setTextColor(accent)
        }
        // 分隔线跟着品牌色走一档很淡的，用中性发丝线会在一张有色卡片上显脏。
        card.findViewById<View>(R.id.plan_divider)
            .setBackgroundColor(withAlpha(plan.brand.tint, 0.22f))
        card.findViewById<TextView>(R.id.plan_badge).apply {
            // 「当前方案」压过「更划算」：他已经在这一档上了，再劝他划算没有意义。
            val badge = when {
                current != null -> getString(R.string.nana7mi_usage_plan_current)
                plan.bestValue -> getString(R.string.nana7mi_usage_plan_badge)
                else -> null
            }
            text = badge
            visibility = if (badge != null) View.VISIBLE else View.GONE
            if (badge != null) {
                background = GradientDrawable().apply {
                    cornerRadius = 999f * resources.displayMetrics.density
                    setColor(withAlpha(plan.brand.tint, 0.18f))
                }
                setTextColor(accent)
            }
        }
        bindFeatures(card.findViewById(R.id.plan_features), plan, accent)
        bindCta(card, plan, current != null)
    }

    /** 同一个色相压到指定不透明度。品牌色只有四个定值，其余层次都从它们派生。 */
    @ColorInt
    private fun withAlpha(@ColorInt color: Int, fraction: Float): Int =
        ColorUtils.setAlphaComponent(color, (255 * fraction).toInt().coerceIn(0, 255))

    /**
     * 卖点清单。三条都说得起：热度排序是这东西本身，倍率同时抬两只桶（服务端两档一起乘），
     * 到期自动回免费档也是真的（服务端没有任何东西会去续期，过期的记录当场读作 Free）。
     *
     * 倍率写「5×」而不是「25 次 / 5 小时」——绝对次数是运营参数，见 [PLANS] 上的注释。
     */
    private fun bindFeatures(host: LinearLayout, plan: Plan, @ColorInt accent: Int) {
        host.removeAllViews()
        val inflater = LayoutInflater.from(mContext)
        val lines = listOf(
            getString(R.string.nana7mi_usage_plan_feature_sort),
            getString(R.string.nana7mi_usage_plan_feature_multiplier, plan.multiplier),
            getString(R.string.nana7mi_usage_plan_feature_lapse),
        )
        lines.forEach { line ->
            val row = inflater.inflate(R.layout.item_nana7mi_plan_feature, host, false)
            row.findViewById<TextView>(R.id.feature_check).setTextColor(accent)
            row.findViewById<TextView>(R.id.feature_text).text = line
            host.addView(row)
        }
    }

    /**
     * 卡片底部的整幅按钮。已订阅的那一档写「续费」——他点进来多半就是为了续，写「选择」
     * 会让人以为是要换一档。
     *
     * 整张卡也可点，和按钮同一个动作：卡片这么大，要求用户精准命中底部那一条不合理。
     */
    private fun bindCta(card: View, plan: Plan, isCurrent: Boolean) {
        val cta = card.findViewById<TextView>(R.id.plan_cta)
        val busy = checkoutInFlight == plan.key
        val anyBusy = checkoutInFlight != null
        cta.text = when {
            busy -> getString(R.string.nana7mi_usage_plan_cta_loading)
            isCurrent -> getString(R.string.nana7mi_usage_plan_cta_renew)
            else -> getString(R.string.nana7mi_usage_plan_cta_choose, getString(plan.titleRes))
        }
        cta.background = ctaBackground(plan.brand)
        cta.setTextColor(ContextCompat.getColor(mContext, R.color.always_white))
        // 有一笔在飞时整组按钮都停用：两条下单链接同时开出去，用户会在浏览器里看到两个
        // 订单页，而其中一个是他已经不想要的那一档。
        cta.alpha = if (anyBusy && !busy) 0.4f else 1f
        val click = View.OnClickListener { startCheckout(plan) }
        cta.isEnabled = !anyBusy
        cta.setOnClickListener(if (anyBusy) null else click)
        card.isEnabled = !anyBusy
        card.setOnClickListener(if (anyBusy) null else click)
    }

    /**
     * 换一条带身份的下单链接，然后交给浏览器。
     *
     * **必须走服务端换链接，不能在客户端拼爱发电的 URL。** 链接里那段签名令牌是「这单是谁买的」
     * 的唯一凭据；少了它，订单回到服务端时没有身份，只能落进后台等人工认领，而用户那边是
     * 付了钱却什么都没发生。所以换链接失败时就老实报错让他重试，绝不退回去开一个裸链接。
     *
     * 用 Custom Tab 而不是内置 WebView：付款要跳微信/支付宝，那是 app intent，普通 WebView
     * 跟不过去，会停在一个永远转圈的收银台上。
     */
    private fun startCheckout(plan: Plan) {
        if (checkoutInFlight != null) return
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        checkoutInFlight = plan.key
        setupPlans(V3Palette.from(mContext))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = Client.pixshaft.requestAfdianCheckout(
                uid = uid,
                plan = plan.key,
                client = Nana7miCheckoutClient(
                    appVersion = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    entry = CHECKOUT_ENTRY,
                    device = Build.MODEL,
                    locale = Locale.getDefault().toLanguageTag(),
                    android = Build.VERSION.RELEASE,
                ),
            )
            checkoutInFlight = null
            setupPlans(V3Palette.from(mContext))
            when (result) {
                is Nana7miCheckoutResult.Success -> openCheckout(result.url)
                Nana7miCheckoutResult.Unavailable ->
                    Common.showToast(getString(R.string.nana7mi_usage_plan_unavailable))
                is Nana7miCheckoutResult.Failure ->
                    Common.showToast(getString(R.string.nana7mi_usage_plan_checkout_failed))
            }
        }
    }

    private fun openCheckout(url: String) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(mContext, Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            Common.showToast(getString(R.string.nana7mi_usage_plan_no_browser))
        }
    }

    /**
     * 回到这页就重新拉一次 —— 刚从收银台回来的人，进来就该看到额度已经变了。
     *
     * 跳过第一次：[initData] 刚拉过，重复一次只是白打一个请求。
     */
    override fun onResume() {
        super.onResume()
        // 回来这一次是**静默刷新**：不转圈、失败也不换成错误页。从收银台回来看到整页先空白
        // 再重画，读起来像页面崩了重开一次；而这时候屏幕上那份数据本来就还是对的，最多差
        // 一次刚买的档位——真刷到了就地换掉，刷不到保持原样，没有一种情况值得把它清空。
        if (everResumed) load(withSpinner = false) else everResumed = true
    }

    /**
     * 卡片底。已订阅的那一档描实边（alpha60），其余是发丝边 —— 差别要在余光里就成立，
     * 不能只靠读文字。
     */
    private fun planCardBackground(brand: Brand, highlighted: Boolean): RippleDrawable {
        val density = resources.displayMetrics.density
        val radius = 20f * density
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(withAlpha(brand.tint, 0.10f))
            setStroke(
                ((if (highlighted) 1.5f else 1f) * density).toInt().coerceAtLeast(1),
                withAlpha(brand.tint, if (highlighted) 0.55f else 0.26f),
            )
        }
        // mask 决定 ripple 的形状：没有它水波会漫成方角，露出圆角外面
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(withAlpha(brand.tint, 0.20f)), fill, mask)
    }

    /** 右上角档位牌的底：同按钮一样的实心品牌色，只是圆成胶囊。 */
    private fun pillBackground(brand: Brand): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 999f * resources.displayMetrics.density
        setColor(brand.tint)
    }

    /**
     * 按钮底：实心品牌中间调，整页唯一一处实心填充 —— 它是这页唯一要人做的动作。
     *
     * 用 [Brand.tint] 而不是封面上那截亮色：`#5FE6DC`、`#FFC85C` 那两头太亮，
     * 白字压不住、深字又跳，而中间调配白字是仓库里实心按钮一贯的配法（见 pillPrimary）。
     * 色相还是那两档各自的，蓝和金分得开，只是不再拉渐变。
     */
    private fun ctaBackground(brand: Brand): RippleDrawable {
        val density = resources.displayMetrics.density
        val radius = 14f * density
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(brand.tint)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(withAlpha(brand.base, 0.22f)), fill, mask)
    }

    /**
     * 「2026/09/18 到期」。只给日期不给时分：月付订阅的到期精确到分钟对用户没有意义，
     * 反而让一行本来一眼扫过的信息变成要读的数字。
     */
    private fun expiryText(plan: Nana7miPlan): String {
        val until = plan.ownedExpiresAt
            ?: return getString(R.string.nana7mi_usage_plan_current_no_expiry)
        return getString(
            R.string.nana7mi_usage_plan_expires,
            DateFormat.getDateFormat(mContext).format(Date(until)),
        )
    }

    /**
     * @param withSpinner 首次进入（和手动重试）要有加载态；[onResume] 的静默刷新不要——
     *   它是在已经画好的内容上做替换，转圈和错误页都只会把用户看得好好的一屏拿走。
     */
    private fun load(withSpinner: Boolean = true) {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) {
            // 未登录不是「加载失败」：没有可重试的东西，所以这一态不给重试文案。
            showError(getString(R.string.nana7mi_usage_login_required), retryable = false)
            return
        }
        if (withSpinner) showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = Client.pixshaft.fetchNana7miQuota(uid)) {
                is Nana7miQuotaResult.Success -> {
                    // 服务端此刻认的档位比冷启动缓存的那份新——刚买完还没重启 app 的人，
                    // 打开这页就该看到自己已经是订户了。
                    result.plan?.let {
                        plan = it
                        bindPlanLabel()
                        setupPlans(V3Palette.from(mContext))
                        // 顺手把缓存刷新了：刚买完的人从这页退回去，侧边栏徽章就已经对了。
                        RemoteAppConfig.updateNana7miPlan(uid, it)
                    }
                    render(result.quotas, result.serverTime)
                }
                // 静默刷新失败就当没刷过：屏幕上那份数据还是有效的，把它换成错误页是在
                // 拿走一份能用的东西去换一句「加载失败」。
                else -> if (withSpinner) showError(getString(R.string.nana7mi_usage_error), retryable = true)
            }
        }
    }

    private fun showLoading() {
        baseBind.loading.visibility = View.VISIBLE
        baseBind.contentScroll.visibility = View.GONE
        baseBind.errorState.visibility = View.GONE
    }

    private fun showError(message: String, retryable: Boolean) {
        baseBind.loading.visibility = View.GONE
        baseBind.contentScroll.visibility = View.GONE
        baseBind.errorState.visibility = View.VISIBLE
        baseBind.errorText.text = message
        baseBind.errorAction.visibility = if (retryable) View.VISIBLE else View.GONE
        baseBind.errorState.isClickable = retryable
    }

    private fun render(quotas: List<Nana7miQuotaWindow>, serverTime: Long) {
        baseBind.loading.visibility = View.GONE
        baseBind.errorState.visibility = View.GONE
        baseBind.contentScroll.visibility = View.VISIBLE

        val host = baseBind.usageRows
        host.removeAllViews()
        val inflater = LayoutInflater.from(mContext)
        quotas.forEachIndexed { index, window ->
            val row = inflater.inflate(R.layout.item_nana7mi_usage_row, host, false)
            row.setBackgroundResource(WitRowStyle.rowBackground(index, quotas.size))
            bindRow(row, window, serverTime)
            host.addView(row)
        }
        // 分段行的中性底换成主题 tint —— 必须等整组都 addView 完再调一次（见 WitRowStyle 文档）
        WitRowStyle.applyThemedRowBg(baseBind.usageRows)
    }

    private fun bindRow(row: View, window: Nana7miQuotaWindow, serverTime: Long) {
        row.findViewById<TextView>(R.id.usage_title).text = titleOf(window)

        val percentView = row.findViewById<TextView>(R.id.usage_percent)
        val percentLabel = row.findViewById<TextView>(R.id.usage_percent_label)
        val bar = row.findViewById<ProgressBar>(R.id.usage_bar)
        val resetView = row.findViewById<TextView>(R.id.usage_reset)

        // remaining 为 null = 服务端把这只桶关了（不是 0 次可用）。没有分母就没有百分比，
        // 也就没有进度条 —— 这一行降级成一句「不限」，不去编一个 100% 或 0%。
        val unlimited = window.remaining == null || window.max <= 0
        if (unlimited) {
            percentView.text = getString(R.string.nana7mi_usage_unlimited)
            // 「不限」是词不是读数，压回二级字号，别让它顶着 24sp 抢走整页的视觉重心
            percentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            percentView.setTextColor(ContextCompat.getColor(mContext, R.color.v3_text_2))
            percentLabel.visibility = View.GONE
            bar.visibility = View.GONE
        } else {
            // used 是小数（翻页 0.2 次），向下取整：还没走完一整个百分点就别显示成走完了
            val percent = (window.used * 100.0 / window.max).toInt().coerceIn(0, 100)
            val accent = usageAccent(percent)
            percentView.text = getString(R.string.nana7mi_usage_used_percent, percent)
            percentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            percentView.setTextColor(accent)
            percentLabel.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            bar.progress = percent
            bar.progressTintList = ColorStateList.valueOf(accent)
            bar.progressBackgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(mContext, R.color.v3_progress_track),
            )
        }

        resetView.text = resetTextOf(window, serverTime)
    }

    /**
     * 用量色：[WARN_FROM] 以下是主题色（正常），到了警戒段才走 黄 → 橙 → 红。
     *
     * 两条规矩，都是踩过坑才定的：
     *
     *  1. **正常段不参与插值**，到 [WARN_FROM] 是硬切换。试过让主题色平滑过渡到黄：蓝到黄的
     *     RGB 直线插值必经灰绿，中段直接变成一条脏绿的条。变色是状态变化，本来就该是跳变。
     *  2. **低用量不能染暖色**。试过整条量程都走黄→红，结果 10% 也是黄的 —— 用量还早却先
     *     摆出警示语气，等真的快满了反而没有更强的信号可用了。
     *
     * 警戒段内只在 `v3_gold` 和 `v3_danger` 之间插值，中段自然落在橙 —— 橙不必单独定义，
     * 它就在黄到红的路上。100% 正好是纯红，所以「用尽」也不需要单独一个分支。
     *
     * 进度条和百分比数字共用这一个颜色 —— 两者说的是同一件事，分开染色只会让人以为
     * 它们各自还有别的含义。
     */
    private fun usageAccent(percent: Int): Int {
        if (percent < WARN_FROM) return V3Palette.from(mContext).primary
        return ColorUtils.blendARGB(
            ContextCompat.getColor(mContext, R.color.v3_gold),
            ContextCompat.getColor(mContext, R.color.v3_danger),
            ((percent - WARN_FROM).toFloat() / (100 - WARN_FROM)).coerceIn(0f, 1f),
        )
    }

    /**
     * 已知的桶给专名，未知的按窗口长度兜底。服务端说过「以后加一只桶不需要客户端改」——
     * 这个 else 分支就是那句话在客户端这边的兑现，不能改成 when 穷举。
     */
    private fun titleOf(window: Nana7miQuotaWindow): String = when (window.key) {
        "session" -> getString(R.string.nana7mi_usage_window_session)
        "weekly" -> getString(R.string.nana7mi_usage_window_weekly)
        else -> getString(R.string.nana7mi_usage_window_generic, window.windowHours)
    }

    private fun resetTextOf(window: Nana7miQuotaWindow, serverTime: Long): String {
        // resetsAt 为 null = 当前没有开着的桶（这个号还没借过，或 5 小时那只已经过期）。
        // 那就没有可倒计时的东西 —— 下一次借号才会开新桶。
        val resetsAt = window.resetsAt ?: return getString(R.string.nana7mi_usage_not_started)
        val remainMs = resetsAt - serverTime
        if (remainMs <= 0L) return getString(R.string.nana7mi_usage_resetting)

        // 和额度提示 Snackbar 共用同一份格式化：同一段时间在两处不能有两种说法。
        return getString(
            R.string.nana7mi_usage_resets_in,
            Nana7miQuotaFormat.duration(mContext, remainMs),
        )
    }
}
