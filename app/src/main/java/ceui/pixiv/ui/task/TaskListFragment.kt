package ceui.pixiv.ui.task

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.loxia.pushFragment
import ceui.pixiv.db.RecordType
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.findCurrentFragmentOrNull
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick

class TaskListFragment : PixivFragment(R.layout.fragment_paged_list), TaskPreviewActionReceiver {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val viewModel by pagingViewModel({ AppDatabase.getAppDatabase(requireContext()) }) { database ->
        val maps = hashMapOf<String, List<Illust>>()
        object : PagingAPIRepository<HumanReadableTask>() {
            override suspend fun loadFirst(): KListShow<HumanReadableTask> {
                val entities = database.generalDao().getAllByRecordType(RecordType.USER_TASK)
                val humanReadableTasks = entities.map { entity ->
                    entity.typedObject<HumanReadableTask>().also {
                        maps[it.taskUUID] = loadIllustsFromCache(it.taskUUID) ?: listOf()
                    }
                }

                return object : KListShow<HumanReadableTask> {
                    override val displayList: List<HumanReadableTask>
                        get() = humanReadableTasks
                    override val nextPageUrl: String?
                        get() = null
                }
            }

            override fun mapper(entity: HumanReadableTask): List<ListItemHolder> {
                return listOf(
                    TaskPreviewHolder(
                        entity,
                        maps.getOrDefault(entity.taskUUID, listOf())
                    )
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
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
