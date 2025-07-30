package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.NovelCardHolder

class QueuedNovelTaskDataSource(
    private val humanReadableTask: HumanReadableTask,
    private val activity: FragmentActivity
) : PagingAPIRepository<Novel>() {
    override suspend fun loadFirst(): KListShow<Novel> {
        return object : KListShow<Novel> {
            override val displayList: List<Novel>
                get() = loadNovelsFromCache(humanReadableTask.taskUUID) ?: listOf()
            override val nextPageUrl: String?
                get() = null
        }
    }

    override fun mapper(entity: Novel): List<ListItemHolder> {
        TaskQueueManager.addTask(DownloadNovelTask(activity.lifecycleScope, entity))
        return listOf(NovelCardHolder(entity))
    }
}