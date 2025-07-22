package ceui.pixiv.ui.trending

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.ObjectPool
import ceui.loxia.TrendingTag
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class TrendingTagsFragment : PixivFragment(R.layout.fragment_paged_list),
    TrendingTagActionReceiver {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by navArgs<TrendingTagsFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs.objectType }) { objectType ->
        TrendingTagsRepository(objectType)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.GRID)
    }

    override fun onClickTrendingTag(trendingTag: TrendingTag) {
        onClickTag(trendingTag.buildTag(), safeArgs.objectType)
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