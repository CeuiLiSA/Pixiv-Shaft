package ceui.pixiv.ui.common

import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.dialogs.MuteDialog
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.Params
import ceui.pixiv.ui.bulk.NovelBulkSelectStorage
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.task.BatchDownloadNovelsTask
import com.hjq.toast.Toaster

/**
 * 小说卡长按菜单（issue #974）。对齐插画卡的 [showCardMenu]：长按 = 打开「这一列表级别」的动作。
 *
 * 写成菜单而不是长按直接跳，是为了跟插画卡的手势语义一致 —— 那边长按弹的也是菜单；
 * 长按把人直接扔进一个全屏页，既没有说明也没有反悔的机会。后续要加的小说卡动作
 *（屏蔽此作品、稍后再看…）都往这里加。
 *
 * 整表快照在 lambda 内部才取：只有真的点了那一项时才复制列表，展开菜单本身零成本
 *（同 [showCardMenu]）。
 */
internal fun NovelFeedFragment.showNovelCardMenu(item: NovelFeedItem) {
    val novel = item.novel
    showV3Menu("NovelFeedCardMenu") {
        // 屏蔽设定：与插画卡同一套屏蔽表（IllustNovelFilter 对 loxia Novel 有同款重载），
        // 只是 MuteDialog 换成喂 tag 列表的重载，不需要 legacy IllustsBean。
        item(getString(R.string.string_111), R.drawable.ic_not_interested_black_24dp) {
            val tags = ArrayList<TagsBean>().apply {
                novel.tags.orEmpty().forEach { add(TagsBean().apply { name = it.name }) }
            }
            if (tags.isEmpty()) return@item
            MuteDialog.newInstance(tags).show(childFragmentManager, "MuteDialog")
        }
        // 相关评论：与 NovelTextFragment.onClickNovelComments 同一条路，
        // TemplateActivity 按 NOVEL_ID 走 ObjectType.NOVEL 的 CommentsFragment。
        item(getString(R.string.string_112), R.drawable.ic_baseline_comment_24) {
            startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                putExtra(Params.NOVEL_ID, novel.id.toInt())
            })
        }
        item(getString(R.string.bulk_actions_entry), R.drawable.ic_select_all_24) {
            val novels = currentNovelItems().map { it.novel }
            if (novels.isEmpty()) return@item
            NovelBulkSelectStorage.put(novels)
            startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说批量选择") // route key, not UI text
            })
        }
        // 下载这一篇：复用批量/系列下载同一条落盘链路（BatchDownloadNovelsTask），
        // 不灌 download_queue——小说下载本来就不走那张表。
        item(getString(R.string.string_339), R.drawable.ic_file_download_black_24dp) {
            BatchDownloadNovelsTask(
                activity = requireActivity(),
                novels = listOf(novel),
                onFinished = { failures ->
                    if (isAdded) {
                        Toaster.show(
                            if (failures.isEmpty()) {
                                getString(R.string.batch_download_all_ok)
                            } else {
                                getString(R.string.batch_download_some_failed, failures.size)
                            }
                        )
                    }
                },
            )
        }
    }
}
