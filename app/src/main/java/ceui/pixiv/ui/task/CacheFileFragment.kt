package ceui.pixiv.ui.task

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.findCurrentFragmentOrNull
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.animateWiggle
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import timber.log.Timber

class CacheFileFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<CacheFileFragmentArgs>()
    private val prefStore by lazy { MMKV.mmkvWithID("user-tasks") }
    private val viewModel by pixivListViewModel({ Pair(requireActivity(), args.task) }) { (activity, task) ->
        Timber.d("task: ${task}")
        if (task.taskType == PixivTaskType.DownloadSeriesNovels) {
            QueuedNovelTaskDataSource(task, activity)
        } else {
            QueuedTaskDataSource(task, activity)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        val humanReadableTask = Gson().fromJson(prefStore.getString(args.task.taskUUID, ""), HumanReadableTask::class.java)
        binding.toolbarLayout.naviTitle.text = humanReadableTask.taskFullName
        binding.toolbarLayout.naviMore.setOnClick {
            TaskQueueManager.startProcessing()
        }
    }

}