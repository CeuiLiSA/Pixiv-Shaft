package ceui.pixiv.ui.task

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
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
import ceui.pixiv.ui.works.buildPixivWorksFileName
import ceui.pixiv.utils.animateWiggle
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import timber.log.Timber
import java.io.File

class CacheFileFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<CacheFileFragmentArgs>()
    private val viewModel by pixivListViewModel({
        Pair(
            requireActivity(),
            args.task
        )
    }) { (activity, task) ->
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
        binding.toolbarLayout.naviTitle.text = args.task.taskFullName
        binding.toolbarLayout.naviMore.setOnClick {
            showActionMenu {
                add(MenuItem("开始任务") {
                    TaskQueueManager.startProcessing()
                })
                add(MenuItem("导出下载链接") {
                    val illustList = loadIllustsFromCache(args.task.taskUUID)
                    val items = mutableListOf<NamedUrl>()
                    illustList?.forEach { illust ->
                        if (illust.page_count == 1) {
                            illust.meta_single_page?.original_image_url?.let {
                                items.add(NamedUrl(buildPixivWorksFileName(illust.id), it))
                            }
                        } else {
                            illust.meta_pages?.forEachIndexed { index, page ->
                                page.image_urls?.original?.let {
                                    items.add(
                                        NamedUrl(
                                            buildPixivWorksFileName(illust.id, index),
                                            it
                                        )
                                    )
                                }
                            }
                        }
                    }

                    val text = buildString {
                        items.forEach {
                            appendLine("${it.name} => ${it.url}")
                        }
                    }

                    val cacheDir = File(Utils.getApp().externalCacheDir, "images").apply { mkdirs() }
                    val txtFile = File(cacheDir, "pixiv_download_links_${args.task.taskUUID}.txt")
                    txtFile.writeText(text)

                    Toast.makeText(requireContext(), "链接文件已生成", Toast.LENGTH_SHORT).show()

                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "ceui.lisa.pixiv.provider", // 你自己的 FileProvider authority
                        txtFile
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TITLE, "Pixiv 原图下载链接")
                        putExtra(Intent.EXTRA_TEXT, "Pixiv 原图链接列表")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    startActivity(Intent.createChooser(shareIntent, "分享 Pixiv 下载链接"))
                })
            }
        }
    }

}