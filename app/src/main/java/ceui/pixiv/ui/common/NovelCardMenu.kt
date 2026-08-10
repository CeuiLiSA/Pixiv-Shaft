package ceui.pixiv.ui.common

import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.bulk.NovelBulkSelectStorage
import ceui.pixiv.ui.detail.showV3Menu

/**
 * 小说卡长按菜单（issue #974）。对齐插画卡的 [showCardMenu]：长按 = 打开「这一列表级别」的动作。
 *
 * 写成菜单而不是长按直接跳，是为了跟插画卡的手势语义一致 —— 那边长按弹的也是菜单；
 * 长按把人直接扔进一个全屏页，既没有说明也没有反悔的机会。后续要加的小说卡动作
 *（屏蔽、下载这一篇…）都往这里加。
 *
 * 整表快照在 lambda 内部才取：只有真的点了那一项时才复制列表，展开菜单本身零成本
 *（同 [showCardMenu]）。
 */
internal fun NovelFeedFragment.showNovelCardMenu() {
    showV3Menu("NovelFeedCardMenu") {
        item(getString(R.string.bulk_select_novel_entry), R.drawable.ic_select_all_24) {
            val novels = currentNovelItems().map { it.novel }
            if (novels.isEmpty()) return@item
            NovelBulkSelectStorage.put(novels)
            startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说批量选择") // route key, not UI text
            })
        }
    }
}
