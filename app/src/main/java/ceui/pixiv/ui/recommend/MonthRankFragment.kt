package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentMonthRankBinding
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 本月新作榜 — 打自建服务端 shaft-api-v2 的 discover/most-bookmarked?month=YYYY-MM。
 * 交互照 [YearRankFragment]:toolbar 下方选择条显当前月份 + 作品数,点它弹 [RankPickerSheet]
 * 选月份(列表来自 /discover/months,服务端降序、最多 36 个月,默认最新一月);再往下是
 * 插画 / 漫画 / 小说三个类型 tab(照 [FragmentRecentRecommend]),换月份靠重建 ViewPager 的
 * 子 fragment(FSPA 真 remove,setAdapter 新实例即可干净重建)。
 *
 * 月份列表只按 illust 拉一次(三类型里样本最大,月份覆盖也最全);漫画 / 小说 tab 下的
 * 选择条计数因此是插画的数字 —— 月份本身三类共用,计数只是给用户一个「这月有多少东西」
 * 的量级感,不做三份请求。
 *
 * 子 tab 的 autoLoad=false + BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT:只有可见 tab 拉首屏
 * (读端点 120 req/min/IP,三 tab 齐射白吃配额)。
 */
class MonthRankFragment : Fragment(R.layout.fragment_month_rank), RankPickerSheet.Host {

    private val binding by viewBinding(FragmentMonthRankBinding::bind)

    /** 月份列表(本次进入页面拉一次,sheet 每次打开复用)。 */
    private var buckets: List<ShaftApiV2.MonthBucket> = emptyList()
    /** 当前展示的月份 `YYYY-MM`。重建后靠它恢复选择条文案与 sheet 选中态。 */
    private var currentMonth: String? = null
    private var currentTypePos: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            currentMonth = it.getString(KEY_CURRENT_MONTH)
            currentTypePos = it.getInt(KEY_TYPE_POS, 0)
        }

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.month_rank_title)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.rankSelector.setOnClickListener { openPicker() }

        // 一次性挂在 ViewPager(view 本身不随月份切换重建,只换 adapter),记录类型 tab。
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) { currentTypePos = position }
        })

        // 重建路径:先用恢复的 currentMonth 裸显月份并装回 pager,不等 discoverMonths 的网络往返
        // (buckets 回来后 loadMonths 会再 bind 一次补上计数)。
        currentMonth?.let {
            bindSelector(it)
            applyMonth(it, restorePos = currentTypePos)
        }

        loadMonths()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_MONTH, currentMonth)
        outState.putInt(KEY_TYPE_POS, currentTypePos)
    }

    /** 拉月份列表。失败提示 + 关页面(首装);CancellationException 原样 rethrow(约定见本仓 FeedViewModel)。 */
    private fun loadMonths() {
        val firstBuild = currentMonth == null
        binding.rankLoading.visibility = if (firstBuild) View.VISIBLE else View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val months = try {
                ShaftApiV2Client.service.discoverMonths(type = RankType.ILLUST).months.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag("MonthRank").w(e, "discoverMonths failed")
                binding.rankLoading.visibility = View.GONE
                // 重建路径 pager 还活着但 picker 点不开,总得有个说法;只有首装(什么都渲染不了)
                // 才连页面一起关。服务端未部署 /months 时(404)也走这里,不崩。
                Common.showToast(getString(R.string.month_rank_load_failed))
                if (firstBuild) {
                    activity?.finish()
                }
                return@launch
            }
            binding.rankLoading.visibility = View.GONE
            if (months.isEmpty()) {
                Common.showToast(getString(R.string.month_rank_load_failed))
                if (firstBuild) {
                    activity?.finish()
                }
                return@launch
            }
            buckets = months
            // 首装默认最新一月(服务端降序的第一项);重建保持原选择。
            val month = currentMonth ?: months.first().month
            if (firstBuild) {
                applyMonth(month, restorePos = currentTypePos)
            }
            bindSelector(month)
        }
    }

    /**
     * 切到 [month]:重建三个类型 tab + 刷选择条。[restorePos] 保持用户停留的类型 tab。
     * 选同一月由 [onRankOptionPicked] 挡掉,不白重载。
     */
    private fun applyMonth(month: String, restorePos: Int) {
        currentMonth = month
        bindSelector(month)

        val titles = listOf(
            getString(R.string.type_illust),
            getString(R.string.type_manga),
            getString(R.string.type_novel),
        )
        // type 传 server 端稳定 enum,不传 localized(系统语言切换 + 状态恢复会对不上)。
        val fragments: List<Fragment> = listOf(
            BookmarkRankIllustFeedFragment.newInstance(type = RankType.ILLUST, month = month),
            BookmarkRankIllustFeedFragment.newInstance(type = RankType.MANGA, month = month),
            BookmarkRankNovelFeedFragment.newInstance(month = month),
        )

        binding.viewPager.adapter = object : FragmentStatePagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment = fragments[position]
            override fun getCount(): Int = titles.size
            override fun getPageTitle(position: Int): CharSequence = titles[position]
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        val pos = restorePos.coerceIn(0, titles.size - 1)
        currentTypePos = pos
        binding.viewPager.setCurrentItem(pos, false)
    }

    /** 选择条文案:「2026-08 · 12.3k ▾」;列表还没回来时先裸显月份。 */
    private fun bindSelector(month: String) {
        val bucket = buckets.find { it.month == month }
        binding.rankSelector.text = if (bucket != null) {
            "${bucket.month} · ${formatRankCount(bucket.count)}"
        } else {
            month
        }
    }

    private fun openPicker() {
        if (buckets.isEmpty()) return
        RankPickerSheet.show(
            childFragmentManager,
            title = getString(R.string.month_rank_pick),
            keys = buckets.map { it.month }.toTypedArray(),
            labels = buckets.map { it.month }.toTypedArray(),
            sublabels = buckets.map { "" }.toTypedArray(),   // 月份没有副行
            counts = buckets.map { it.count }.toIntArray(),
            // -1 = 当前月份不在列表里(服务端只回最近 36 个月,理论上稳定;防御性对齐 YearRankFragment)。
            selected = buckets.indexOfFirst { it.month == currentMonth },
        )
    }

    override fun onRankOptionPicked(key: String) {
        if (key == currentMonth) return
        applyMonth(key, restorePos = currentTypePos)
    }

    companion object {
        private const val KEY_CURRENT_MONTH = "month_rank_current_month"
        private const val KEY_TYPE_POS = "month_rank_type_pos"

        @JvmStatic
        fun newInstance(): MonthRankFragment = MonthRankFragment()
    }
}
