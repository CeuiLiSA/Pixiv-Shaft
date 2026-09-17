package ceui.pixiv.ui.prime

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「热门搜索」里一个标签的作品 —— 二级页。
 *
 * 数据来自 pixshaft-api 作品库的 `/v1/corpus/works?tag=…`，按收藏数从高到低，所以它天然是
 * 这个标签的一面高收藏墙；库越大，墙越好。
 *
 * 和 [PrimeTagDetailFragment] 的两条规矩这里同样成立：
 * - **游标分页**。库是六位数条目且每天在长，offset 翻到深处要先走完前面所有条目，游标不用；
 *   服务端实测第 200 页和第 1 页一样快。游标不透明，原样回传即可。
 * - **不合池**（见 [poolableBeansOf]）。库里是入库那一刻的快照，`is_bookmarked` 更是在服务端
 *   入库时就被删掉的（那是取页那个账号的状态，不是读者的），喂进 ObjectPool 会拿旧值盖掉
 *   用户刚点出来的收藏/关注态。
 */
class CorpusTagDetailFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels<String> {
        val tag = requireArguments().getString(CorpusTagsFragment.ARG_TAG).orEmpty()
        FeedSource { cursor ->
            // 服务端的分级上限只为省流量（库里一半以上是 R-18，用户关着的时候没必要下行再
            // 丢掉）。真正说了算的还是下面 IllustFeedItem.of 里那套全局过滤，两边都过一遍。
            val maxRestrict = if (Shaft.sSettings.isR18FilterTempEnable) ALL_AGES else INCLUDE_R18G
            val page = Client.pixshaft.corpusWorks(
                limit = PAGE_SIZE,
                cursor = cursor,
                r18 = maxRestrict,
                tag = tag,
            )
            // IllustFeedItem.of 走全局过滤，而 judgeTag/judgeUserID 各是**每个作品一次**
            // 同步 Room 查询 + 把每条屏蔽规则 Gson 反序列化一遍。FeedViewModel 在
            // viewModelScope（主线程）上调 load，Retrofit 的 suspend 恢复后仍在主线程——
            // 一页 30 条就是 60 次主线程磁盘读，翻页时直接掉帧。FeedSource 的契约写的是
            // 「自己做重 IO / 解析要切 Dispatchers」，PixivFeedSource 也是这么做的。
            val items = withContext(Dispatchers.Default) {
                page.illusts.mapNotNull { illust -> IllustFeedItem.of(illust) }
            }
            FeedPage(items, page.next_cursor)
        }
    }

    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = requireArguments().getString(CorpusTagsFragment.ARG_TAG)
    }

    companion object {
        /** 服务端同样把 limit 卡在 30，两边一起改才有意义。 */
        private const val PAGE_SIZE = 30
        private const val ALL_AGES = 0
        private const val INCLUDE_R18G = 2

        fun newInstance(tag: String): CorpusTagDetailFragment {
            return CorpusTagDetailFragment().apply {
                arguments = bundleOf(CorpusTagsFragment.ARG_TAG to tag)
            }
        }
    }
}
