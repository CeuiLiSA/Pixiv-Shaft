package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.NovelCardHolder

class QueuedNovelTaskDataSource(
    private val humanReadableTask: HumanReadableTask,
    private val activity: FragmentActivity
) : DataSource<Novel, KListShow<Novel>>(dataFetcher = {
    object : KListShow<Novel> {
        override val displayList: List<Novel>
            get() = loadNovelsFromCache(humanReadableTask.taskUUID) ?: listOf()
        override val nextPageUrl: String?
            get() = null
    }
}, responseStore = null, itemMapper = { novel ->
    TaskQueueManager.addTask(DownloadNovelTask(activity.lifecycleScope, novel))
    listOf(NovelCardHolder(novel))
})