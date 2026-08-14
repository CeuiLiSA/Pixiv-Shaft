package ceui.pixiv.ui.common

import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.muted.MuteTagSheet
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.ui.bulk.BulkSelectStorage
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.slideshow.SlideshowLauncher

/**
 * 插画卡长按菜单：屏蔽此作品 / 屏蔽设定 / 相关评论 / 批量下载 / 单作品下载 / 幻灯片 / 稍后再看。
 *
 * 从 [IllustFeedFragment] 搬出来单独放一个文件：菜单是一组独立的动作编排，跟「列表怎么加载」
 * 和「卡片怎么画」都无关，挤在基类里只是让那个类更长。
 *
 * 各动作里的整表快照（`currentIllustItems()`）都在 lambda 内部取 —— 只在真的点了那一项时才
 * 复制列表，展开菜单本身零成本。
 */
internal fun IllustFeedFragment.showCardMenu(item: IllustFeedItem) {
    val bean = item.bean
    val entityWrapper = requireEntityWrapper()
    val inWatchLater = entityWrapper.isInWatchLater(item.illust.id)
    val spoilered = IllustMuteStore.isMuted(item.illust.id)
    showV3Menu("IllustFeedCardMenu") {
        // 屏蔽这一件作品：不动服务端，往本地屏蔽记录（tag_mute_table）写一行，卡片留在原位
        // 糊掉 + 盖粒子；「屏蔽记录」页能看到并删除。下面那条「屏蔽设定」是按标签/画师的全局设定。
        // 排在最前面——它是长按这张卡最直接的诉求。
        val spoilerLabel = getString(
            if (spoilered) R.string.spoiler_reveal_illust else R.string.spoiler_hide_illust
        )
        val spoilerIcon = if (spoilered) {
            R.drawable.ic_baseline_remove_red_eye_24
        } else {
            R.drawable.ic_visibility_off_black_24dp
        }
        item(spoilerLabel, spoilerIcon) {
            setIllustMuted(item, !spoilered)
        }
        item(getString(R.string.string_111), R.drawable.ic_not_interested_black_24dp) {
            MuteTagSheet.show(childFragmentManager, bean.tags, bean.user)
        }
        item(getString(R.string.string_112), R.drawable.ic_baseline_comment_24) {
            startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                putExtra(Params.ILLUST_ID, bean.id)
                putExtra(Params.ILLUST_TITLE, bean.title)
            })
        }
        // 标签和图标都不再说「下载」：这个入口通向的多选页现在除了下载还能批量收藏 /
        // 取消收藏（issue #974），继续挂个下载箭头会让用户以为点进去只能下载。
        // 与小说卡长按菜单共用同一个 bulk_actions_entry —— 两处是同一件事，不该有两套措辞。
        item(getString(R.string.bulk_actions_entry), R.drawable.ic_select_all_24) {
            // 整个列表交给 V3 多选页勾选（对齐 legacy IAdapter popup / MultiDownload）
            val beans = currentIllustItems().map { it.bean }
            if (beans.isNotEmpty()) {
                BulkSelectStorage.put(beans)
                startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "批量选择")
                })
            }
        }
        item(getString(R.string.string_339), R.drawable.ic_file_download_black_24dp) {
            IllustDownload.downloadIllustAllPages(bean)
            if (Shaft.sSettings.isAutoPostLikeWhenDownload() && !bean.isIs_bookmarked) {
                PixivOperate.postLikeDefaultStarType(bean)
            }
        }
        item(getString(R.string.slideshow_play), R.drawable.ic_baseline_play_arrow_24) {
            val beans = currentIllustItems().map { it.bean }
            val position = beans.indexOfFirst { it.id == bean.id }.coerceAtLeast(0)
            SlideshowLauncher.launchFromIllustsBeans(
                requireContext(), ArrayList(beans), position, true,
            )
        }
        val watchLaterLabel = getString(
            if (inWatchLater) R.string.watch_later_remove else R.string.watch_later_add
        )
        item(watchLaterLabel, R.drawable.ic_watch_later_24) {
            val appContext = requireContext().applicationContext
            if (inWatchLater) {
                entityWrapper.removeFromWatchLater(appContext, item.illust.id)
                Common.showToast(R.string.watch_later_removed)
            } else {
                entityWrapper.addToWatchLater(appContext, item.illust)
                Common.showToast(R.string.watch_later_added)
            }
        }
    }
}
