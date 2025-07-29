package ceui.pixiv.ui.task

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.loxia.pushFragment
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.findCurrentFragmentOrNull
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import timber.log.Timber

class TaskListFragment : PixivFragment(R.layout.fragment_pixiv_list), TaskPreviewActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel({ AppDatabase.getAppDatabase(requireContext()) }) { database ->
        val maps = hashMapOf<String, List<Illust>>()
        DataSource(dataFetcher = {
            val entities = database.generalDao().getAllByRecordType(RecordType.USER_TASK)
            val humanReadableTasks = entities.map { entity ->
                entity.typedObject<HumanReadableTask>().also {
                    maps[it.taskUUID] = loadIllustsFromCache(it.taskUUID) ?: listOf()
                }
            }

            object : KListShow<HumanReadableTask> {
                override val displayList: List<HumanReadableTask>
                    get() = humanReadableTasks
                override val nextPageUrl: String?
                    get() = null
            }
        }, itemMapper = { task ->
            listOf(TaskPreviewHolder(task, maps.getOrDefault(task.taskUUID, listOf())))
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        binding.toolbarLayout.naviMore.setOnClick {
            requireActivity().findCurrentFragmentOrNull()
        }
    }

    override fun onClickTaskPreview(humanReadableTask: HumanReadableTask) {
        pushFragment(
            R.id.navigation_cache_list, CacheFileFragmentArgs(task = humanReadableTask).toBundle()
        )
    }
}
