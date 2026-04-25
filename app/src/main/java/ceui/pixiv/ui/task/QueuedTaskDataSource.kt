package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.pixiv.ui.common.DataSource
class QueuedTaskDataSource(
    private val humanReadableTask: HumanReadableTask,
    private val activity: FragmentActivity
) : DataSource<Illust, KListShow<Illust>>(dataFetcher = {
    object : KListShow<Illust> {
        override val displayList: List<Illust>
            get() = loadIllustsFromCache(humanReadableTask.taskUUID) ?: listOf()
        override val nextPageUrl: String?
            get() = null
    }
}, responseStore = null, itemMapper = { illust ->
    val items = mutableListOf<NamedUrl>()
    if (illust.page_count == 1) {
        illust.meta_single_page?.original_image_url?.let { url ->
            items.add(NamedUrl(url.substringAfterLast('/'), url))
        }
    } else {
        illust.meta_pages?.forEachIndexed { index, page ->
            page.image_urls?.original?.let { url ->
                items.add(NamedUrl(url.substringAfterLast('/'), url))
            }
        }
    }
    items.map { namedUrl ->
        val task = TaskPool.getDownloadTask(namedUrl).also {
            TaskQueueManager.addTask(it)
        }
        QueuedTaskHolder(task, illust)
    }
})