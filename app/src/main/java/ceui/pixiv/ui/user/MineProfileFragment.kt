package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentMineProfileBinding
import ceui.lisa.utils.Common
import ceui.loxia.Illust
import ceui.loxia.pushFragment
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.task.DownloadAllTask
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.loadIllustsFromCache
import ceui.pixiv.ui.common.PixivFragment
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding

class MineProfileFragment : PixivFragment(R.layout.fragment_mine_profile) {

    private val binding by viewBinding(FragmentMineProfileBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        SessionManager.loggedInAccount.observe(viewLifecycleOwner) { account ->
            account.user?.name
            binding.myBookmark.setOnClick {
                pushFragment(R.id.navigation_user_bookmarked_illust, UserBookmarkedIllustsFragmentArgs(account.user?.id ?: 0L).toBundle())
            }
        }



        binding.cache.setOnClick {
            pushFragment(R.id.navigation_cache_list)
        }

        binding.tasks.setOnClick {
            pushFragment(R.id.navigation_task_status)
        }
    }
}