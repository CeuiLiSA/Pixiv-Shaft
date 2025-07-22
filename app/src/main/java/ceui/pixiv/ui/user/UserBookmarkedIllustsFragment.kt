package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ObjectType
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.repo.RemoteRepository
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class UserBookmarkedIllustsFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val safeArgs by navArgs<UserBookmarkedIllustsFragmentArgs>()
    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val viewModel by pagingViewModel({ safeArgs }) { args ->
        UserBookmarkedIllustsRepository(args)
    }
    private val contentViewModel by pixivValueViewModel({ safeArgs }) { args ->
        RemoteRepository {
            val rest = if (args.restrictType == Params.TYPE_PRIVATE) {
                "hide"
            } else {
                "show"
            }
            Client.webApi.getBookmarkedIllust(SessionManager.loggedInUid, ObjectType.ILLUST, rest)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (safeArgs.userId == SessionManager.loggedInUid) {
            contentViewModel.result.observe(viewLifecycleOwner) { loadResult ->
                (parentFragment as? TitledViewPagerFragment)?.let {
                    val result = loadResult?.data ?: return@observe
                    if (safeArgs.restrictType == Params.TYPE_PRIVATE) {
                        it.getTitleLiveData(1).value =
                            "${getString(R.string.string_392)} (${result.body?.total ?: 0})"
                    } else {
                        it.getTitleLiveData(0).value =
                            "${getString(R.string.string_391)} (${result.body?.total ?: 0})"
                    }
                }
            }
        }
        setUpPagedList(binding, viewModel)
    }
}