package ceui.pixiv.ui.common

import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.muted.MuteTagSheet
import ceui.lisa.download.IllustDownload
import ceui.loxia.Illust
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.loxia.requireEntityWrapper
import ceui.loxia.toTagsBeans
import ceui.pixiv.ui.bulk.BulkSelectHandoff
import ceui.pixiv.ui.bulk.IllustBulkSelectHandoff
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.slideshow.SlideshowLauncher
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 插画卡长按菜单：屏蔽此作品 / 屏蔽设定 / 相关评论 / 批量下载 / 单作品下载 / 幻灯片 / 稍后再看。
 *
 * 从 [IllustFeedFragment] 搬出来单独放一个文件：菜单是一组独立的动作编排，跟「列表怎么加载」
 * 和「卡片怎么画」都无关，挤在基类里只是让那个类更长。
 *
 * 各动作里的整表快照（[scopedBeans]）都在 lambda 内部取 —— 只在真的点了那一项时才
 * 复制列表，展开菜单本身零成本。
 *
 * @param scopedBeans 「批量操作」「幻灯片」作用的作品集合。默认是本页整张插画列表；页内的
 *   子列表（首页顶部的横向排行榜预览条）传自己那一份 —— 从榜单卡长按点「幻灯片」却放起
 *   底下推荐流，是两份互不相干的数据混在一个菜单里。
 * @param onToggleSpoiler 「屏蔽 / 取消屏蔽此作品」怎么落地。默认走本页瀑布流卡的遮罩重绑
 *   （[IllustFeedFragment.setIllustMuted]）；画不出遮罩的列表（横向排行榜预览条）自行覆盖。
 */
internal fun IllustFeedFragment.showCardMenu(
    item: IllustFeedItem,
    scopedBeans: () -> List<Illust> = { currentIllustItems().map { it.illust } },
    onToggleSpoiler: (Boolean) -> Unit = { setIllustMuted(item, it) },
) {
    val bean = item.illust
    // 收藏夹关闭「过滤无效收藏」后，已删除/不公开的失效插画仍会显示（灰色封面）。这类作品
    // 打不开详情，下载/屏蔽/评论等动作也基本无意义，长按只保留「复制作品ID」以及能拿到的
    // 「复制作品标题」，方便对照本地下载文件确认是哪张作品被删了。
    if (!Shaft.sSettings.isFilterInvalidBookmarks && bean.visible != true) {
        showV3Menu("IllustFeedCardMenu") {
            item(getString(R.string.copy_work_id), R.drawable.baseline_content_copy_24) {
                Common.copy(requireContext(), bean.id.toString())
            }
            if (!bean.title.isNullOrBlank()) {
                item(getString(R.string.copy_work_title), R.drawable.baseline_content_copy_24) {
                    Common.copy(requireContext(), bean.title)
                }
            }
        }
        return
    }
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
            onToggleSpoiler(!spoilered)
        }
        item(getString(R.string.string_111), R.drawable.ic_not_interested_black_24dp) {
            MuteTagSheet.show(childFragmentManager, bean.tags?.toTagsBeans(), bean.user)
        }
        item(getString(R.string.string_112), R.drawable.ic_baseline_comment_24) {
            startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMMENTS.key)
                // TemplateActivity 侧仍按 getIntExtra 读
                putExtra(Params.ILLUST_ID, bean.id.toInt())
                putExtra(Params.ILLUST_TITLE, bean.title)
            })
        }
        // 标签和图标都不再说「下载」：这个入口通向的多选页现在除了下载还能批量收藏 /
        // 取消收藏（issue #974），继续挂个下载箭头会让用户以为点进去只能下载。
        // 与小说卡长按菜单共用同一个 bulk_actions_entry —— 两处是同一件事，不该有两套措辞。
        item(getString(R.string.bulk_actions_entry), R.drawable.ic_select_all_24) {
            // 整个列表交给 V3 多选页勾选（对齐 legacy IAdapter popup / MultiDownload）
            val beans = scopedBeans()
            if (beans.isNotEmpty()) {
                val key = IllustBulkSelectHandoff.put(beans)
                startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.BULK_SELECT.key)
                    putExtra(BulkSelectHandoff.ARG_HANDOFF_KEY, key)
                })
            }
        }
        item(getString(R.string.string_339), R.drawable.ic_file_download_black_24dp) {
            IllustDownload.downloadIllustAllPages(bean)
            if (Shaft.sSettings.isAutoPostLikeWhenDownload() && !bean.isBookmarked) {
                PixivOperate.postLikeDefaultStarType(bean)
            }
        }
        item(getString(R.string.slideshow_play), R.drawable.ic_baseline_play_arrow_24) {
            val beans = scopedBeans()
            val position = beans.indexOfFirst { it.id == bean.id }.coerceAtLeast(0)
            SlideshowLauncher.launchFromIllusts(
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
