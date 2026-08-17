package ceui.lisa.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.pixiv.ui.collection.LikeNovelFeedFragment
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.ui.collection.LikeIllustFeedFragment

private const val ARG_USER_ID = "collection_user_id"
private const val STATE_SEGMENT = "collection_segment"
private const val SEG_ILLUST = 0
private const val SEG_NOVEL = 1

/**
 * 画师主页「收藏」Tab —— 顶部分段控件在「插画/漫画收藏」和「小说收藏」间切换,
 * 各自复用现有的 LikeIllustFeedFragment / FragmentLikeNovel(showToolbar=false)。
 *
 * 把旧版散在折叠头「导航」药丸里的两个收藏入口收敛进一个 Tab。
 */
class UserV3CollectionFragment : Fragment() {

    private var userId = 0
    private var selected = SEG_ILLUST
    private var contentLoaded = false
    private lateinit var palette: V3Palette
    private lateinit var segIllust: TextView
    private lateinit var segNovel: TextView

    companion object {
        fun newInstance(userId: Int): UserV3CollectionFragment =
            UserV3CollectionFragment().apply {
                arguments = Bundle().apply { putInt(ARG_USER_ID, userId) }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        userId = arguments?.getInt(ARG_USER_ID) ?: 0
        return inflater.inflate(R.layout.fragment_user_v3_collection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        palette = V3Palette.from(requireContext())
        segIllust = view.findViewById(R.id.seg_illust)
        segNovel = view.findViewById(R.id.seg_novel)
        segIllust.text = getString(R.string.string_164) // 插画/漫画收藏
        segNovel.text = getString(R.string.string_192)  // 小说收藏
        segIllust.setOnClickListener { select(SEG_ILLUST) }
        segNovel.setOnClickListener { select(SEG_NOVEL) }

        selected = savedInstanceState?.getInt(STATE_SEGMENT, SEG_ILLUST) ?: SEG_ILLUST
        // 旋转/重建时 childFragmentManager 会自己恢复内嵌 fragment,标记为已加载,别再 replace 丢滚动位置。
        if (savedInstanceState != null) {
            contentLoaded = childFragmentManager.findFragmentById(R.id.collection_container) != null
        }
        updateSegmentStyle()
    }

    // 懒加载:收藏 Tab 是插画 Tab 的相邻页,会被 ViewPager2 预取(onViewCreated 先跑)。此时不加载列表,
    // 只有真正切到收藏 Tab(fragment RESUMED,onResume 触发)才创建子 fragment 发请求 —— 进主页不打 bookmarks。
    override fun onResume() {
        super.onResume()
        if (!contentLoaded) {
            contentLoaded = true
            showSegment(selected)
        }
    }

    private fun select(seg: Int) {
        if (seg == selected &&
            childFragmentManager.findFragmentById(R.id.collection_container) != null
        ) return
        selected = seg
        showSegment(seg)
    }

    private fun showSegment(seg: Int) {
        val frag = when (seg) {
            SEG_NOVEL -> LikeNovelFeedFragment.newInstance(userId, Params.TYPE_PUBLIC, false)
            else -> LikeIllustFeedFragment.newInstance(userId, Params.TYPE_PUBLIC, false)
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.collection_container, frag)
            .commit()
        updateSegmentStyle()
    }

    private fun updateSegmentStyle() {
        applyStyle(segIllust, selected == SEG_ILLUST)
        applyStyle(segNovel, selected == SEG_NOVEL)
    }

    private fun applyStyle(tv: TextView, active: Boolean) {
        val dp = resources.displayMetrics.density
        if (active) {
            tv.background = palette.pillPrimary(999f * dp)
            tv.setTextColor(resources.getColor(R.color.v3_text_1, null))
        } else {
            tv.background = palette.pillSecondary(999f * dp, (1 * dp).toInt())
            tv.setTextColor(palette.textSecondary)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SEGMENT, selected)
    }
}
