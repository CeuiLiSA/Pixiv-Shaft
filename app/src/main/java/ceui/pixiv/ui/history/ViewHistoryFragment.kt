package ceui.pixiv.ui.history

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.RefreshHint
import ceui.loxia.threadSafeArgs
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding

class ViewHistoryFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<ViewHistoryFragmentArgs>()
    private val viewModel by constructVM({
        AppDatabase.getAppDatabase(requireContext()) to safeArgs.recordType
    }) { (database, recordType) ->
        HistoryViewModel(database, recordType)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(
            binding, viewModel,
            if (safeArgs.recordType == RecordType.VIEW_ILLUST_HISTORY) {
                ListMode.STAGGERED_GRID
            } else {
                ListMode.VERTICAL
            }
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh(RefreshHint.PullToRefresh)
    }
}