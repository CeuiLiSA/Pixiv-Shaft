package ceui.pixiv.ui.task

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.loxia.ObjectType
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.getImageIdInGallery
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.works.buildPixivWorksFileName
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

class CacheFileFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<CacheFileFragmentArgs>()
    private val prefStore by lazy { MMKV.mmkvWithID("user-tasks") }
    private val viewModel by pixivListViewModel({ Pair(requireActivity(), args.taskUuid) }) { (activity, taskUuid) ->
        QueuedTaskDataSource(taskUuid, activity)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val humanReadableTask = Gson().fromJson(prefStore.getString(args.taskUuid, ""), HumanReadableTask::class.java)
        binding.toolbarLayout.naviTitle.text = humanReadableTask.taskFullName
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
    }

}