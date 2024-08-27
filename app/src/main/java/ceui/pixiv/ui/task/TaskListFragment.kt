package ceui.pixiv.ui.task

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellTaskPreviewBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.refactor.viewBinding
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.delay

class TaskListFragment : PixivFragment(R.layout.fragment_pixiv_list), TaskPreviewActionReceiver {

    private val prefStore by lazy { MMKV.mmkvWithID("user-tasks") }
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel {
        val maps = hashMapOf<String, List<Illust>>()
        DataSource(
            dataFetcher = {
                val humanReadableTasks = mutableListOf<HumanReadableTask>()
                val gson = Gson()
                prefStore.allKeys()?.forEach { uuid ->
                    maps[uuid] = loadIllustsFromCache(uuid) ?: listOf()
                    humanReadableTasks.add(
                        gson.fromJson(prefStore.getString(uuid, ""), HumanReadableTask::class.java)
                    )
                }

                object : KListShow<HumanReadableTask> {
                    override val displayList: List<HumanReadableTask>
                        get() = humanReadableTasks
                    override val nextPageUrl: String?
                        get() = null
                }
            },
            itemMapper = { task ->
                listOf(TaskPreviewHolder(task, maps.getOrDefault(task.taskUUID, listOf())))
            }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        binding.toolbarLayout.naviTitle.text = getString(R.string.created_tasks)
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
    }

    override fun onClickTaskPreview(humanReadableTask: HumanReadableTask) {
        pushFragment(R.id.navigation_cache_list, CacheFileFragmentArgs(humanReadableTask.taskUUID).toBundle())
    }
}

class TaskPreviewHolder(val humanReadableTask: HumanReadableTask, val illusts: List<Illust>) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return humanReadableTask.taskUUID == (other as? TaskPreviewHolder)?.humanReadableTask?.taskUUID
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return humanReadableTask == (other as? TaskPreviewHolder)?.humanReadableTask
    }

    fun getIllustOrNull(index: Int): Illust? {
        return illusts.getOrNull(index)
    }
}

@ItemHolder(TaskPreviewHolder::class)
class TaskPreviewViewHolder(bd: CellTaskPreviewBinding) : ListItemViewHolder<CellTaskPreviewBinding, TaskPreviewHolder>(bd) {

    override fun onBindViewHolder(holder: TaskPreviewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.root.setOnClickListener {
            it.findActionReceiverOrNull<TaskPreviewActionReceiver>()?.onClickTaskPreview(holder.humanReadableTask)
        }
        binding.taskSize.text = "共${holder.illusts.size}个作品"
    }
}

interface TaskPreviewActionReceiver {
    fun onClickTaskPreview(humanReadableTask: HumanReadableTask)
}