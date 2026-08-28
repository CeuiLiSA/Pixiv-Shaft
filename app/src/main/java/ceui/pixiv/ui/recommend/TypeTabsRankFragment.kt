package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentYearRankBinding
import ceui.pixiv.ui.common.viewBinding

/**
 * 「toolbar + 类型 tab(插画 / 漫画 / 小说)+ ViewPager」的榜单宿主基类。收藏榜 / AI 榜 /
 * 全年龄榜([BookmarkRankFragment])、浏览量榜([ViewRankFragment])、标签专区
 * ([TagRankFragment])、年代榜([YearRankFragment])共用:子类只给标题、tab 列表和
 * 「某个 type 对应哪个子页」。
 *
 * 做法照抄 [FragmentRecentRecommend]:tab 标题用 type_illust/manga/novel 本地化文案,传给
 * 子页的 type 用服务端稳定 enum([RankType]),不传 localized(系统语言切换 + 状态恢复会
 * 对不上);FSPA + BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT + 子页 autoLoad=false,只有可见
 * tab 才拉首屏(读端点 120 req/min/IP,三个 tab 齐射会白白吃配额)。tab 位置存进
 * savedInstanceState 跨重建恢复。
 *
 * 复用 fragment_year_rank 布局(同 [WallpaperRankFragment]);tab 本地写死,yearsLoading 直接 GONE。
 */
abstract class TypeTabsRankFragment : Fragment(R.layout.fragment_year_rank) {

    private val binding by viewBinding(FragmentYearRankBinding::bind)

    private var currentTypePos: Int = 0

    /** 页面标题。 */
    @get:StringRes
    protected abstract val titleRes: Int

    /** tab 顺序,元素是 [RankType] 的服务端 enum。 */
    protected open val types: List<String> get() = RankType.ALL

    /** [type] tab 的子页。子页自己不带 toolbar,且 feed 用 autoLoad=false。 */
    protected abstract fun createPage(type: String): Fragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            currentTypePos = it.getInt(KEY_TYPE_POS, 0)
        }

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(titleRes)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.yearsLoading.visibility = View.GONE

        // 不能在 onSaveInstanceState 里读 binding(view 那时可能已销毁),用 listener 同步进字段。
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) { currentTypePos = position }
        })

        val types = types
        val titles = types.map { getString(RankType.titleRes(it)) }
        binding.viewPager.adapter = object : FragmentStatePagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment = createPage(types[position])
            override fun getCount(): Int = titles.size
            override fun getPageTitle(position: Int): CharSequence = titles[position]
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        val pos = currentTypePos.coerceIn(0, titles.size - 1)
        currentTypePos = pos
        binding.viewPager.setCurrentItem(pos, false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TYPE_POS, currentTypePos)
    }

    companion object {
        private const val KEY_TYPE_POS = "type_tabs_rank_type_pos"
    }
}
