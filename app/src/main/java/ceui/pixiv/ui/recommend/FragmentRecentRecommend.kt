package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRecentRecommendBinding
import ceui.pixiv.witstudio.popup.WitMenuPopup
import ceui.pixiv.ui.common.viewBinding

/**
 * 当前最热 — 打自建服务端 shaft-api-v2 的 recent/works。三个类型 tab:
 *   插画 (type=illust) / 漫画 (type=manga) / 小说 (type=novel)
 *
 * 工具栏右侧的窗口下拉切换数据口径(透传给 recent/works 的 ?window):
 *   实时(null) → 「现在正在被收藏」的实时流(按最近 bookmark 倒序,原行为)
 *   日/周/月榜  → 实时日/周/月榜(只统计窗口内 bookmark,按窗口内热度降序)
 *
 * 切换窗口靠重建 ViewPager 的子 fragment。这里用 [FragmentStatePagerAdapter] 而非
 * FragmentPagerAdapter:后者 destroyItem 只 detach、且 instantiateItem 按 tag 复用,
 * 换新 adapter 会把带旧 window 的旧 fragment 又 attach 回来;State 版是真 remove + 按
 * 内部列表管理,setAdapter 新实例即可干净重建。
 */
class FragmentRecentRecommend : Fragment(R.layout.fragment_recent_recommend) {

    private val binding by viewBinding(FragmentRecentRecommendBinding::bind)

    // (window 值给 server / 透传 null=实时, label 资源)。顺序即下拉顺序。
    private val windowOptions: List<Pair<String?, Int>> = listOf(
        null to R.string.recent_window_live,
        "day" to R.string.recent_window_day,
        "week" to R.string.recent_window_week,
        "month" to R.string.recent_window_month,
    )
    // 这两个是「真相」,跨配置变更/进程死亡要存。不能在 onSaveInstanceState 里读
    // binding(view 那时可能已销毁),所以类型 tab 位置用 listener 同步进字段。
    private var currentWindow: String? = null
    private var currentTypePos: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            currentWindow = it.getString(KEY_WINDOW)
            currentTypePos = it.getInt(KEY_TYPE_POS, 0)
        }

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.current_hot)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }

        // 一次性挂在 ViewPager(view 本身不随窗口切换重建,只换 adapter),记录类型 tab。
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) { currentTypePos = position }
        })

        binding.windowSelector.setOnClickListener { showWindowMenu() }
        applyWindow(currentWindow, restorePos = currentTypePos)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_WINDOW, currentWindow)
        outState.putInt(KEY_TYPE_POS, currentTypePos)
    }

    /** 弹窗口选择菜单(WitPopups.listPopup,跟项目里其它下拉视觉统一),选中即切换。 */
    private fun showWindowMenu() {
        val titles: Array<CharSequence> =
            windowOptions.map { getString(it.second) as CharSequence }.toTypedArray()
        WitMenuPopup.show(requireContext(), binding.windowSelector, titles) { index, _ ->
            val window = windowOptions[index].first
            if (window != currentWindow) {
                applyWindow(window, restorePos = binding.viewPager.currentItem)
            }
        }
    }

    /**
     * 重建三个类型 tab,带上 [window]。[restorePos] 用来在切换窗口后保持用户停留的
     * 类型 tab(插画/漫画/小说)。
     */
    private fun applyWindow(window: String?, restorePos: Int) {
        currentWindow = window
        val labelRes = windowOptions.firstOrNull { it.first == window }?.second
            ?: R.string.recent_window_live
        binding.windowSelector.text = getString(labelRes) + " ▾"

        val titles = listOf(
            getString(R.string.type_illust),
            getString(R.string.type_manga),
            getString(R.string.type_novel),
        )
        // dataType 传 server 端稳定 enum,不传 localized(系统语言切换 + 状态恢复会对不上)。
        val fragments: List<Fragment> = listOf(
            HotWorksIllustFeedFragment.newInstance(
                HotWorksSource.RECENT, HotWorksIllustFeedFragment.TYPE_ILLUST, window
            ),
            HotWorksIllustFeedFragment.newInstance(
                HotWorksSource.RECENT, HotWorksIllustFeedFragment.TYPE_MANGA, window
            ),
            HotWorksNovelFeedFragment.newInstance(HotWorksSource.RECENT, window),
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

    companion object {
        private const val KEY_WINDOW = "recent_recommend_window"
        private const val KEY_TYPE_POS = "recent_recommend_type_pos"
    }
}
