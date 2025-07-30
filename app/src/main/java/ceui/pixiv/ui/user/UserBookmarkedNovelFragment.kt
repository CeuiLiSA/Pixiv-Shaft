package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class UserBookmarkedNovelFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by navArgs<UserBookmarkedNovelFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs }) { args ->
        object : PagingAPIRepository<Novel>() {
            override suspend fun loadFirst(): KListShow<Novel> {
                return Client.appApi.getUserBookmarkedNovels(
                    args.userId,
                    args.restrictType ?: Params.TYPE_PUBLIC
                )
            }

            override fun mapper(entity: Novel): List<ListItemHolder> {
                return listOf(NovelCardHolder(entity))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
    }
}