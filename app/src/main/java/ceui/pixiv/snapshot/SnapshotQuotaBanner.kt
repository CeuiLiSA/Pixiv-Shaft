package ceui.pixiv.snapshot

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentSnapshotManagerBinding
import ceui.pixiv.witstudio.theme.V3Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.text.DecimalFormat

/**
 * 快照管理页「自动快照占用」条。
 *
 * 条本身是 AppBarLayout 的滚动子项（scroll|enterAlways），收起 / 恢复完全交给
 * AppBarLayout 原生驱动，和 Toolbar 同步；这里只负责两件事：
 * 1. 只在「全部」Tab 显示；
 * 2. 把当前占用 / 上限写进条内。
 *
 * 不做任何 translationY、高度动画或列表 padding —— 那些会破坏 AppBarLayout 的
 * 滚动几何，也会让 Toolbar 收起失效。
 */
internal class SnapshotQuotaBanner(
    private val fragment: Fragment,
    private val binding: FragmentSnapshotManagerBinding,
) {

    /**
     * 这条挂在 AppBarLayout 里，底色就是主题色实底，所以不能套 settingsCard 那种浅卡面
     * —— 在实色 bar 上贴一块别的材质，会跟 Toolbar 格格不入。
     *
     * 前景统一取 [V3Palette.onPrimary]：它是实底主色配套的文字 / 图标色，亮色主题
     * （盛夏黄这类）会自动压深，白字不会消失；轨道用同一个色的低透明度版本。
     */
    fun applyTheme() {
        val context = fragment.context ?: return
        val onBar = V3Palette.from(context).onPrimary
        binding.autoSnapshotQuotaBanner.background = null
        // 两者都用完整 onPrimary：降透明度会正好落进 TabLayout 未选中态（#eceff1）的观感，
        // 看起来像「没选中」。主次改用字重 / 字号区分，不用透明度。
        binding.autoSnapshotQuotaTitle.setTextColor(onBar)
        binding.autoSnapshotQuotaValue.setTextColor(onBar)
        binding.autoSnapshotQuotaProgress.progressTintList =
            ColorStateList.valueOf(onBar)
        binding.autoSnapshotQuotaProgress.progressBackgroundTintList =
            ColorStateList.valueOf(V3Palette.withAlpha(onBar, TRACK_ALPHA))
    }

    /** 「全部」Tab 的列表 reload 完成后重新读一次占用。 */
    fun attach(tab: SnapshotListFragment) {
        tab.onAutoSnapshotQuotaChanged = { refresh() }
    }

    fun onTabSelected(position: Int) {
        val banner = binding.autoSnapshotQuotaBanner
        if (position == 0) {
            banner.visibility = View.VISIBLE
            // 切回「全部」时让 Toolbar + 占用条回到展开态，避免停在上一个 Tab 的收起位置。
            binding.appBar.setExpanded(true, true)
            refresh()
        } else {
            banner.visibility = View.GONE
        }
    }

    private fun refresh() {
        val appContext = fragment.context?.applicationContext ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val usedBytes = withContext(Dispatchers.IO) {
                runCatching {
                    AutoSnapshotRepository.listAuto(appContext).sumOf { it.totalSize }
                }.getOrDefault(0L)
            }
            val limitMb = Shaft.sSettings.getAutoSnapshotMaxMb()
            val limitText = if (limitMb == AutoSnapshotQuota.UNLIMITED_LIMIT_MB) {
                appContext.getString(R.string.setting_auto_snapshot_quota_unlimited)
            } else {
                formatQuotaSize(appContext, AutoSnapshotQuota.maxBytesForLimit(limitMb))
            }
            binding.autoSnapshotQuotaValue.text = appContext.getString(
                R.string.snapshot_quota_banner_value,
                formatQuotaSize(appContext, usedBytes),
                limitText,
            )
            binding.autoSnapshotQuotaProgress.progress = quotaProgress(usedBytes, limitMb)
        }
    }

    /**
     * 占用极小时，`used * MAX / limit` 的整数除法会直接落到 0；就算算出 1、2，
     * 4dp 条高 + 全圆端也会被圆角吃掉，看起来像完全没有占用。
     *
     * 所以只要确实有占用，就保底给到「填充段至少一个条高」的进度：
     * 圆端胶囊在一倍条高时正好是一颗可见的圆点，再小就会被圆角抹平。
     */
    private fun quotaProgress(usedBytes: Long, limitMb: Int): Int {
        if (limitMb == AutoSnapshotQuota.UNLIMITED_LIMIT_MB || usedBytes <= 0L) return 0
        val limitBytes = AutoSnapshotQuota.maxBytesForLimit(limitMb)
        if (limitBytes <= 0L) return 0
        val raw = (usedBytes.toDouble() * PROGRESS_MAX / limitBytes).roundToInt()
        return raw.coerceIn(minVisibleProgress(), PROGRESS_MAX)
    }

    /**
     * 最小可见进度。用真实测量宽度算「一个条高」的占比；还没排版出来时退化为
     * [MIN_VISIBLE_RATIO_FALLBACK]（常见手机上约等于一个条高）。
     */
    private fun minVisibleProgress(): Int {
        val width = binding.autoSnapshotQuotaProgress.width
        val height = binding.autoSnapshotQuotaProgress.height
        val ratio = if (width > 0 && height > 0) {
            (height.toDouble() / width).coerceIn(MIN_VISIBLE_RATIO_MIN, MIN_VISIBLE_RATIO_MAX)
        } else {
            MIN_VISIBLE_RATIO_FALLBACK
        }
        return (ratio * PROGRESS_MAX).roundToInt().coerceAtLeast(1)
    }

    /**
     * 占用 / 上限的展示：单位仍是 MB / GB 两档，不引入新单位。
     *
     * 只有「不足 0.01 MB 的非零占用」这一种情况特殊处理 —— 按 0.01 MB 显示。
     * 原来直接整除成整数 MB，几百 KB 会写成「0 MB」，看起来像一份都没存下来。
     *
     * - 0：整数 0 MB
     * - 0 &lt; 值 &lt; 1 MB：0.01–0.99 MB，两位小数
     * - 1 MB – 1023 MB：整数 MB（与设置弹窗一致）
     * - ≥ 1024 MB：GB，最多两位小数（与设置弹窗一致）
     */
    private fun formatQuotaSize(context: Context, bytes: Long): String {
        val mb = AutoSnapshotQuota.displayMb(bytes)
        return when {
            mb <= 0.0 -> context.getString(R.string.setting_auto_snapshot_quota_value, 0)
            // 小数档固定两位：0.01 MB 要明确写成「0.01」，不能被 0.## 收成「0.01」以外的东西，
            // 也不能因为进位不足写成「0.00」。
            mb < 1.0 -> context.getString(
                R.string.snapshot_quota_size_mb_decimal, formatMbDecimal(mb),
            )
            mb < 1024.0 -> context.getString(
                R.string.setting_auto_snapshot_quota_value, mb.toLong(),
            )
            // GB 档与设置弹窗一致：最多两位小数，整数时不拖尾零。
            else -> context.getString(
                R.string.setting_auto_snapshot_quota_value_gb, formatQuotaDecimal(mb / 1024.0),
            )
        }
    }

    /** MB 小数档：固定两位小数，保证 0.01 一定显示成「0.01」。 */
    private fun formatMbDecimal(value: Double): String = DecimalFormat("0.00").format(value)

    /** GB 档：最多两位小数，整数不拖尾零。小数点跟默认 Locale 走。 */
    private fun formatQuotaDecimal(value: Double): String = DecimalFormat("0.##").format(value)

    private companion object {
        const val PROGRESS_MAX = 1000

        /** 进度轨道：同一个前景色的低透明版本，避免 fill / track 同色系看不出余量。 */
        const val TRACK_ALPHA = 0.30f

        /** 还没排版、量不到宽度时的退化值：常见手机上约等于一个条高。 */
        const val MIN_VISIBLE_RATIO_FALLBACK = 0.014

        /** 太窄的条上「一个条高」占比会很夸张，钳一下，避免最小值反而变成明显进度。 */
        const val MIN_VISIBLE_RATIO_MAX = 0.03

        /** 太宽的条上一个条高只有百分之几，钳住下限，保证还是一颗看得见的圆点。 */
        const val MIN_VISIBLE_RATIO_MIN = 0.004
    }
}