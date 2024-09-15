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
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.getImageIdInGallery
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.works.buildPixivWorksFileName
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.delay

class CacheFileFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<CacheFileFragmentArgs>()
    private val prefStore by lazy { MMKV.mmkvWithID("user-tasks") }
    private val viewModel by pixivListViewModel {
        DataSource(
            dataFetcher = {
                delay(600L)
                object : KListShow<Illust> {
                    override val displayList: List<Illust>
                        get() = loadIllustsFromCache(args.taskUuid) ?: listOf()
                    override val nextPageUrl: String?
                        get() = null
                }
            },
            itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val humanReadableTask = Gson().fromJson(prefStore.getString(args.taskUuid, ""), HumanReadableTask::class.java)
        binding.toolbarLayout.naviTitle.text = humanReadableTask.taskFullName
        setUpStaggerLayout(binding, viewModel)
        binding.toolbarLayout.naviMore.setOnClick {
            if (humanReadableTask.taskType == PixivTaskType.DownloadAll) {
                val task = DownloadAllTask(requireActivity()) {
                    val items = mutableListOf<NamedUrl>()
                    loadIllustsFromCache(args.taskUuid)?.forEach { illust ->
                        if (illust.page_count == 1) {
                            illust.meta_single_page?.original_image_url?.let {
                                items.add(NamedUrl(buildPixivWorksFileName(illust.id), it))
                            }
                        } else {
                            illust.meta_pages?.forEachIndexed { index, page ->
                                page.image_urls?.original?.let {
                                    items.add(NamedUrl(buildPixivWorksFileName(illust.id, index), it))
                                }
                            }
                        }
                    }
                    items
                }
                task.pendingTasks.forEach {
                    val isExist = getImageIdInGallery(requireContext(), it.content.name)
                    Common.showLog("sdaadsads2 ${it.content.name}, ${isExist}")
                }
            } else if (humanReadableTask.taskType == PixivTaskType.BookmarkAll) {
                val task = BookmarkAllTask {
                    val items = mutableListOf<BookmarkTask>()
                    loadIllustsFromCache(args.taskUuid)?.forEach { illust ->
                        BookmarkTask(illust.id, ObjectType.ILLUST)
                    }
                    items
                }
            }
        }
    }

}