package ceui.pixiv.ui.trending

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.ObjectPool
import ceui.loxia.TrendingTag
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel

class TrendingTagsFragment : PixivFragment(R.layout.fragment_pixiv_list),
    TrendingTagActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<TrendingTagsFragmentArgs>()
    private val viewModel by pixivListViewModel {
        TrendingTagsDataSource(args.objectType)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.GRID)
    }

    override fun onClickTrendingTag(trendingTag: TrendingTag) {
        onClickTag(trendingTag.buildTag(), args.objectType)
    }

    override fun onLongClickTrendingTag(trendingTag: TrendingTag) {
        trendingTag.illust?.let {
            ObjectPool.update(it)
            it.user?.let { user ->
                ObjectPool.update(user)
            }
            onClickIllustCard(it)
        }
    }
}