package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.User
import ceui.loxia.UserResponse
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpSizedList
import ceui.pixiv.ui.task.FetchAllTask
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding

class UserBookmarkedIllustsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel {
        DataSource(
            dataFetcher = {
                Client.appApi.getUserBookmarkedIllusts(
                    args.userId,
                    args.restrictType ?: Params.TYPE_PUBLIC
                )
            },
            itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
        )
    }
    private val contentViewModel by pixivValueViewModel {
        val rest = if (args.restrictType == Params.TYPE_PRIVATE) { "hide" } else { "show" }
        Client.webApi.getBookmarkedIllust(SessionManager.loggedInUid, ObjectType.ILLUST, rest)
    }
    private val args by navArgs<UserBookmarkedIllustsFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (args.userId == SessionManager.loggedInUid) {
            contentViewModel.result.observe(viewLifecycleOwner) { result ->
                (parentFragment as? TitledViewPagerFragment)?.let {
                    if (args.restrictType == Params.TYPE_PRIVATE) {
                        it.getTitleLiveData(1).value =
                            "${getString(R.string.string_392)} (${result.body?.total ?: 0})"
                    } else {
                        it.getTitleLiveData(0).value =
                            "${getString(R.string.string_391)} (${result.body?.total ?: 0})"
                    }
                }
            }
        }
        binding.toolbarLayout.naviMore.setOnClick {
            object : FetchAllTask<Illust, IllustResponse>(
                taskFullName = "${ObjectPool.get<User>(args.userId).value?.name}收藏的全部插画",
                initialLoader = {
                    Client.appApi.getUserBookmarkedIllusts(
                        args.userId,
                        args.restrictType ?: Params.TYPE_PUBLIC
                    )
                }) {
                override fun onEnd(results: List<Illust>) {
                    super.onEnd(results)
                    Common.showLog("FetchAllTask out onEnd ${results.size}")
                }
            }
        }
        setUpStaggerLayout(binding, viewModel)
    }
}