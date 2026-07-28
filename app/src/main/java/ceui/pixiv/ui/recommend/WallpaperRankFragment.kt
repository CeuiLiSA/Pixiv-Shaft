package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentYearRankBinding
import ceui.pixiv.ui.common.viewBinding

/**
 * 壁纸榜 — 打自建服务端 shaft-api-v2 的 discover/wallpapers。两个固定 tab:
 * 手机(竖图 h/w≥5/3 且 h≥1600)/ 桌面(横图 w/h≥1.5 且 w≥1200),按 pixiv 总收藏数排,
 * 手机在前(App 用户绝大多数要的是手机壁纸)。单 tab 是 [WallpaperIllustFeedFragment]。
 *
 * 复用 fragment_year_rank 布局(toolbar + tabs + pager 的通用形状);tab 是本地写死的
 * 两个,没有异步加载态 —— yearsLoading 直接 GONE。FSPA + RESUME_ONLY_CURRENT +
 * 子 fragment autoLoad=false 的组合同 [YearRankFragment](那边有完整论证)。
 */
class WallpaperRankFragment : Fragment(R.layout.fragment_year_rank) {

    private val binding by viewBinding(FragmentYearRankBinding::bind)

    private var currentScreenPos: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            currentScreenPos = it.getInt(KEY_SCREEN_POS, 0)
        }

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.wallpaper_rank_title)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.yearsLoading.visibility = View.GONE

        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) { currentScreenPos = position }
        })

        // screen 值是服务端 enum;标题是本地化文案。顺序即 tab 顺序:手机在前。
        val screens = listOf(SCREEN_PHONE, SCREEN_DESKTOP)
        val titles = listOf(
            getString(R.string.wallpaper_screen_phone),
            getString(R.string.wallpaper_screen_desktop),
        )

        binding.viewPager.adapter = object : FragmentStatePagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment =
                WallpaperIllustFeedFragment.newInstance(screens[position])
            override fun getCount(): Int = titles.size
            override fun getPageTitle(position: Int): CharSequence = titles[position]
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        val pos = currentScreenPos.coerceIn(0, titles.size - 1)
        currentScreenPos = pos
        binding.viewPager.setCurrentItem(pos, false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCREEN_POS, currentScreenPos)
    }

    companion object {
        private const val KEY_SCREEN_POS = "wallpaper_rank_screen_pos"
        const val SCREEN_PHONE = "phone"
        const val SCREEN_DESKTOP = "desktop"

        @JvmStatic
        fun newInstance(): WallpaperRankFragment = WallpaperRankFragment()
    }
}
