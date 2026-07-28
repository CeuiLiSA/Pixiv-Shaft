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
 * 标签专区 — 打自建服务端 shaft-api-v2 的 discover/most-bookmarked?tag=。
 * 一次只看一个标签:toolbar 下方的选择条显示当前标签(翻译优先 + 作品数),点它弹
 * [RankPickerSheet](bottom sheet 列表,来自 /discover/tags 的 top-N),选完 child
 * fragment([TagRankIllustFeedFragment])整页 replace 展示该标签的收藏榜。
 *
 * 为什么不是横向 ViewPager tab:标签是 21 万个里挑出来的 top-50,横滑找不到心智锚点,
 * 列表 + 计数一屏看全才挑得动(用户反馈,年代榜同期一起改了)。没有 ViewPager 也就没有
 * FSPA 那堆契约;feed 子页保持 `autoLoad=false`,replace 进来即 RESUMED 即拉首屏
 * (见 FeedFragment.onResume),一次只有一个 feed 在场,天然不会齐射限流。
 */
class TagRankFragment : Fragment(R.layout.fragment_rank_picker), RankPickerSheet.Host {

    private val binding by viewBinding(FragmentRankPickerBinding::bind)

    /** 热门标签列表(本次进入页面拉一次,sheet 每次打开复用)。 */
    private var buckets: List<ShaftApiV2.TagBucket> = emptyList()
    /** 当前展示的标签原文。重建后靠它恢复选择条文案与 sheet 选中态;feed 由 FM 自己带回来。 */
    private var currentTag: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentTag = savedInstanceState?.getString(KEY_CURRENT_TAG)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.tag_rank_title)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.rankSelector.setOnClickListener { openPicker() }

        loadTags()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_TAG, currentTag)
    }

    /** 拉热门标签。失败提示 + 关页面;CancellationException 原样 rethrow(约定见本仓 FeedViewModel)。 */
    private fun loadTags() {
        // 重建路径:feed 子页已被 FM 带回来,列表只是给 sheet 用 —— 不挡内容,loading 只在首装转。
        val firstBuild = childFragmentManager.findFragmentById(R.id.feed_container) == null
        binding.rankLoading.visibility = if (firstBuild) View.VISIBLE else View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = try {
                ShaftApiV2Client.service.discoverTags(type = TYPE_ILLUST, limit = TAG_LIST_COUNT).tags
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag("TagRank").w(e, "discoverTags failed")
                binding.rankLoading.visibility = View.GONE
                if (firstBuild) {
                    // 首装拿不到标签就什么都渲染不了;重建路径 feed 还活着,别把页面关了。
                    Common.showToast(getString(R.string.tag_rank_load_failed))
                    activity?.finish()
                }
                return@launch
            }
            binding.rankLoading.visibility = View.GONE
            if (tags.isEmpty()) {
                if (firstBuild) {
                    Common.showToast(getString(R.string.tag_rank_load_failed))
                    activity?.finish()
                }
                return@launch
            }
            buckets = tags
            // 首装默认第一个(最热)标签;重建保持原选择。currentTag 不在新列表里(榜单漂移)
            // 也没关系 —— feed 与选择条仍按它展示,sheet 里只是没有高亮行。
            val tag = currentTag ?: tags.first().tag
            if (firstBuild) {
                showTag(tag)
            } else {
                currentTag = tag
                bindSelector(tag)
            }
        }
    }

    /** 切到 [tag]:换 feed 子页 + 刷选择条。选同一个标签由 [onRankOptionPicked] 挡掉,不白重载。 */
    private fun showTag(tag: String) {
        currentTag = tag
        bindSelector(tag)
        // allowingStateLoss:首装的 showXxx 由网络回调驱动,响应落在「切后台之后、视图
        // 销毁之前」的窗口时普通 commit 会抛 IllegalStateException。丢状态无害 —— 重建路径
        // 本来就靠 FM 恢复 child + savedInstanceState 恢复选择,少这一笔照样对。
        childFragmentManager.commit(allowStateLoss = true) {
            replace(R.id.feed_container, TagRankIllustFeedFragment.newInstance(tag))
        }
    }

    /** 选择条文案:「碧蓝档案 · 20.0k ▾」,翻译优先、原文兜底;列表还没回来时先裸显原文。 */
    private fun bindSelector(tag: String) {
        val bucket = buckets.find { it.tag == tag }
        binding.rankSelector.text = if (bucket != null) {
            "${bucket.translated ?: bucket.tag} · ${formatCount(bucket.count)}"
        } else {
            tag
        }
    }

    private fun openPicker() {
        if (buckets.isEmpty()) return  // 列表还没回来/拉挂了:点了先没反应,loadTags 的 toast 兜底
        RankPickerSheet.show(
            childFragmentManager,
            title = getString(R.string.tag_rank_pick),
            keys = buckets.map { it.tag }.toTypedArray(),
            labels = buckets.map { it.translated ?: it.tag }.toTypedArray(),
            // 有翻译时副行显原文(在 pixiv 站内搜索还得用原文);没翻译主行就是原文,副行藏。
            sublabels = buckets.map { if (it.translated != null) it.tag else "" }.toTypedArray(),
            counts = buckets.map { it.count }.toIntArray(),
            // -1 = 当前标签已掉出最新列表(榜单漂移):sheet 不高亮任何行,而不是错误地亮第一行。
            selected = buckets.indexOfFirst { it.tag == currentTag },
        )
    }

    override fun onRankOptionPicked(key: String) {
        if (key == currentTag) return
        showTag(key)
    }

    companion object {
        private const val KEY_CURRENT_TAG = "tag_rank_current_tag"
        /** 服务端 enum,不是展示文案。标签专区目前只做插画(manga/novel 标签样本量小)。 */
        private const val TYPE_ILLUST = "illust"
        /** sheet 列表条数。列表比 tab 装得下更多长尾;再多靠服务端 ?q= 搜索,以后要做再加。 */
        private const val TAG_LIST_COUNT = 50

        /** 12345 → 「12.3k」,988 → 「988」。 */
        private fun formatCount(n: Int): String =
            if (n >= 1000) String.format("%.1fk", n / 1000f) else n.toString()

        @JvmStatic
        fun newInstance(): TagRankFragment = TagRankFragment()
    }
}
