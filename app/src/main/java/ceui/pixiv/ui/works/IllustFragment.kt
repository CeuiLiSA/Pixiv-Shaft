package ceui.pixiv.ui.works

import ceui.loxia.Illust
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


fun getGalleryHolders(illust: Illust, coroutineScope: CoroutineScope): List<GalleryHolder>? {
    // Helper function to create a GalleryHolder
    fun createGalleryHolder(index: Int, imageUrl: String?): GalleryHolder {
        val task = TaskPool.getLoadTask(
            NamedUrl(
                buildPixivWorksFileName(illust.id, index),
                imageUrl.orEmpty() // Handle null gracefully
            ),
            coroutineScope,
            autoStart = false
        )
        return GalleryHolder(illust, index, task) {
            coroutineScope.launch { task.execute() }
        }
    }

    return when {
        illust.page_count == 1 -> {
            // Single page handling
            val imageUrl = illust.meta_single_page?.original_image_url
            listOf(createGalleryHolder(0, imageUrl))
        }

        !illust.meta_pages.isNullOrEmpty() -> {
            // Multiple pages handling
            illust.meta_pages.mapIndexed { index, metaPage ->
                createGalleryHolder(index, metaPage.image_urls?.original)
            }
        }

        else -> null
    }
}

