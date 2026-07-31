package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRankPickerBinding
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 年代榜 — 打自建服务端 shaft-api-v2 的 discover/most-bookmarked?year=YYYY。
 * 交互同 [TagRankFragment](一起改的):toolbar 下方选择条显当前年份 + 作品数,点它弹
 * [RankPickerSheet] 选年份,选完 child fragment([YearRankIllustFeedFragment])整页
 * replace。年份列表拉 /discover/years(服务端按年份降序,带每年作品数)。
 *
 * 此前是横向 ViewPager,每年一个 tab(FSPA + RESUME_ONLY_CURRENT + autoLoad=false,
 * 契约论证见 git history)——20 个年份 tab 横滑挑不动,与标签专区一并改成 sheet 选择。
 * feed 子页的 `autoLoad=false` 在新结构下依旧成立:replace 进来即 RESUMED 即拉首屏,
 * 一次只有一个 feed 在场。
 *
 * ⚠️ 分布极度倾斜:2026 年占 56%,2007 年整年只有几十个作品。sheet 行里带上作品数,
 * 否则用户点进 2007 看到一屏就到底,会以为是 bug。
 */
class YearRankFragment : Fragment(R.layout.fragment_rank_picker), RankPickerSheet.Host {

    private val binding by viewBinding(FragmentRankPickerBinding::bind)

    /** 年份列表(本次进入页面拉一次,sheet 每次打开复用)。 */
    private var buckets: List<ShaftApiV2.YearBucket> = emptyList()
    /** 当前展示的年份。重建后靠它恢复选择条文案与 sheet 选中态;feed 由 FM 自己带回来。 */
    private var currentYear: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentYear = savedInstanceState?.getString(KEY_CURRENT_YEAR)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.year_rank_title)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.rankSelector.setOnClickListener { openPicker() }

        // 重建路径:先用恢复的 currentYear 裸显年份,不等 discoverYears 的网络往返
        // (buckets 回来后 loadYears 会再 bind 一次补上计数)。
        currentYear?.let { bindSelector(it) }

        loadYears()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_YEAR, currentYear)
    }

    /** 拉年份列表。失败提示 + 关页面;CancellationException 原样 rethrow(约定见本仓 FeedViewModel)。 */
    private fun loadYears() {
        val firstBuild = childFragmentManager.findFragmentById(R.id.feed_container) == null
        binding.rankLoading.visibility = if (firstBuild) View.VISIBLE else View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val years = try {
                ShaftApiV2Client.service.discoverYears(type = TYPE_ILLUST).years
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag("YearRank").w(e, "discoverYears failed")
                binding.rankLoading.visibility = View.GONE
                // toast 两条路径都给:重建路径 feed 还活着但 picker 点不开,总得有个说法;
                // 只有首装(什么都渲染不了)才连页面一起关。
                Common.showToast(getString(R.string.year_rank_load_failed))
                if (firstBuild) {
                    activity?.finish()
                }
                return@launch
            }
            binding.rankLoading.visibility = View.GONE
            if (years.isEmpty()) {
                Common.showToast(getString(R.string.year_rank_load_failed))
                if (firstBuild) {
                    activity?.finish()
                }
                return@launch
            }
            buckets = years
            // 首装默认最新一年(服务端降序的第一项);重建保持原选择。
            val year = currentYear ?: years.first().year
            if (firstBuild) {
                showYear(year)
            } else {
                currentYear = year
                bindSelector(year)
            }
        }
    }

    /** 切到 [year]:换 feed 子页 + 刷选择条。选同一年由 [onRankOptionPicked] 挡掉,不白重载。 */
    private fun showYear(year: String) {
        currentYear = year
        bindSelector(year)
        // allowingStateLoss:首装的 showXxx 由网络回调驱动,响应落在「切后台之后、视图
        // 销毁之前」的窗口时普通 commit 会抛 IllegalStateException。丢状态无害 —— 重建路径
        // 本来就靠 FM 恢复 child + savedInstanceState 恢复选择,少这一笔照样对。
        childFragmentManager.commit(allowStateLoss = true) {
            replace(R.id.feed_container, YearRankIllustFeedFragment.newInstance(year))
        }
    }

    /** 选择条文案:「2026 · 600.0k ▾」;列表还没回来时先裸显年份。 */
    private fun bindSelector(year: String) {
        val bucket = buckets.find { it.year == year }
        binding.rankSelector.text = if (bucket != null) {
            "${bucket.year} · ${formatRankCount(bucket.count)}"
        } else {
            year
        }
    }

    private fun openPicker() {
        if (buckets.isEmpty()) return
        RankPickerSheet.show(
            childFragmentManager,
            title = getString(R.string.year_rank_pick),
            keys = buckets.map { it.year }.toTypedArray(),
            labels = buckets.map { it.year }.toTypedArray(),
            sublabels = buckets.map { "" }.toTypedArray(),   // 年份没有副行
            counts = buckets.map { it.count }.toIntArray(),
            // -1 = 当前年份不在列表里(理论上年份列表稳定,防御性对齐 TagRankFragment 的语义)。
            selected = buckets.indexOfFirst { it.year == currentYear },
        )
    }

    override fun onRankOptionPicked(key: String) {
        if (key == currentYear) return
        showYear(key)
    }

    companion object {
        private const val KEY_CURRENT_YEAR = "year_rank_current_year"
        /** 服务端 enum,不是展示文案。年代榜目前只做插画(漫画/小说样本量太小)。 */
        private const val TYPE_ILLUST = "illust"

        @JvmStatic
        fun newInstance(): YearRankFragment = YearRankFragment()
    }
}
