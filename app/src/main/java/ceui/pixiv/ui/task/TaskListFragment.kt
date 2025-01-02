package ceui.pixiv.ui.task

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.viewBinding
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import timber.log.Timber

class TaskListFragment : PixivFragment(R.layout.fragment_pixiv_list), TaskPreviewActionReceiver {

    private val prefStore by lazy { MMKV.mmkvWithID("user-tasks") }
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel {
        val maps = hashMapOf<String, List<Illust>>()
        DataSource(
            dataFetcher = {
                val gson = Gson()
                val humanReadableTasks = prefStore.allKeys()
                    ?.mapNotNull { uuid ->
                        val illusts = loadIllustsFromCache(uuid) ?: listOf()
                        maps[uuid] = illusts
                        prefStore.getString(uuid, "")?.let {
                            try {
                                gson.fromJson(it, HumanReadableTask::class.java)
                            } catch (ex: Exception) {
                                Timber.e(ex)
                                null
                            }
                        }
                    }
                    ?: emptyList()

                object : KListShow<HumanReadableTask> {
                    override val displayList: List<HumanReadableTask>
                        get() = humanReadableTasks.sortedByDescending { it.createdTime }
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
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
    }

    override fun onClickTaskPreview(humanReadableTask: HumanReadableTask) {
        pushFragment(R.id.navigation_cache_list, CacheFileFragmentArgs(task = humanReadableTask).toBundle())
    }
}
