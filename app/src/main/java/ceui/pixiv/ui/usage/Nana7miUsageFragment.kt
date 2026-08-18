package ceui.pixiv.ui.usage

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentNana7miUsageBinding
import ceui.lisa.fragments.BaseFragment
import ceui.loxia.Client
import ceui.loxia.Nana7miQuotaResult
import ceui.loxia.Nana7miQuotaWindow
import ceui.loxia.fetchNana7miQuota
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitRowStyle
import kotlinx.coroutines.launch

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
    }

    override fun initLayout() {
        mLayoutID = R.layout.fragment_nana7mi_usage
    }

    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }
        baseBind.errorState.setOnClickListener { load() }
        val palette = V3Palette.from(mContext)
        baseBind.errorAction.setTextColor(palette.primary)
        load()
    }

    private fun load() {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) {
            // 未登录不是「加载失败」：没有可重试的东西，所以这一态不给重试文案。
            showError(getString(R.string.nana7mi_usage_login_required), retryable = false)
            return
        }
        showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = Client.pixshaft.fetchNana7miQuota(uid)) {
                is Nana7miQuotaResult.Success -> render(result.quotas, result.serverTime)
                else -> showError(getString(R.string.nana7mi_usage_error), retryable = true)
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
