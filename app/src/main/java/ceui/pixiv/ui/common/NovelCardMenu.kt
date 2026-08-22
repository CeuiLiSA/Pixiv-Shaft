package ceui.pixiv.ui.common

import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.muted.MuteTagSheet
import ceui.lisa.models.ProfileImageUrlsBean
import ceui.lisa.models.TagsBean
import ceui.lisa.models.UserBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.loxia.User
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.ui.bulk.NovelBulkSelectStorage
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.task.BatchDownloadNovelsTask

/**
 * 小说卡长按菜单（issue #974）。对齐插画卡的 [showCardMenu]：长按 = 打开「这一列表级别」的动作。
 *
 * 写成菜单而不是长按直接跳，是为了跟插画卡的手势语义一致 —— 那边长按弹的也是菜单；
 * 长按把人直接扔进一个全屏页，既没有说明也没有反悔的机会。后续要加的小说卡动作
 *（屏蔽、下载这一篇…）都往这里加。
 *
 * 已落地：屏蔽此作品（本地遮罩 + 整卡粒子）、屏蔽设定、相关评论、批量操作、单篇下载、稍后再看。
 *
 * 整表快照在 lambda 内部才取：只有真的点了那一项时才复制列表，展开菜单本身零成本
 *（同 [showCardMenu]）。
 *
 * @param scopedNovels 「批量操作」作用的小说集合。默认本页整张列表；页内的子列表
 *   （首页顶部的横向排行榜预览条）传自己那一份，语义同 [showCardMenu] 的 scopedBeans。
 * @param onToggleSpoiler 「屏蔽 / 取消屏蔽此作品」怎么落地。默认走本页卡片的遮罩重绑；
 *   画不出遮罩的列表（横向排行榜预览条）自行覆盖。
 */
internal fun NovelFeedFragment.showNovelCardMenu(
    item: NovelFeedItem,
    scopedNovels: () -> List<Novel> = { currentNovelItems().map { it.novel } },
    onToggleSpoiler: (Boolean) -> Unit = { setNovelMuted(item.novel, it) },
) {
    val novel = item.novel
    val entityWrapper = requireEntityWrapper()
    val inWatchLater = entityWrapper.isNovelInWatchLater(novel.id)
    val spoilered = NovelMuteStore.isMuted(novel.id)
    // 顺手把 translated_name 也带上：屏蔽 sheet 的胶囊和「标签屏蔽记录」页都要显示译名。
    val tagsToMute = novel.tags.orEmpty()
        .filter { !it.name.isNullOrBlank() }
        .map { tag ->
            TagsBean().apply {
                name = tag.name
                translated_name = tag.translated_name
            }
        }
    val author = novel.user?.takeIf { it.id != 0L }
    showV3Menu("NovelFeedCardMenu") {
        // 屏蔽此作品：往本地屏蔽记录（tag_mute_table）写一行 + 遮罩（封面模糊 + 粒子），
        // 条目留在原位置，点卡片或本项可取消，「屏蔽记录」页也能看到并删除。
        // 排在最前面——它是长按这张卡最直接的诉求（对齐插画菜单）。
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
        // 屏蔽设定：与插画卡同一套屏蔽表（IllustNovelFilter 对 loxia Novel 有同款重载）。
        // 标签和作者两个 section 都没东西可勾才不挂这一项——挂了点下去也只能静默无反应。
        if (tagsToMute.isNotEmpty() || author != null) {
            item(getString(R.string.string_111), R.drawable.ic_not_interested_black_24dp) {
                MuteTagSheet.show(childFragmentManager, tagsToMute, author?.toMuteUserBean())
            }
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
            val novels = scopedNovels()
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
                        Common.showToast(
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
        val watchLaterLabel = getString(
            if (inWatchLater) R.string.watch_later_remove else R.string.watch_later_add
        )
        item(watchLaterLabel, R.drawable.ic_watch_later_24) {
            val appContext = requireContext().applicationContext
            if (inWatchLater) {
                entityWrapper.removeNovelFromWatchLater(appContext, novel.id)
                Common.showToast(R.string.watch_later_removed)
            } else {
                entityWrapper.addNovelToWatchLater(appContext, novel)
                Common.showToast(R.string.watch_later_added)
            }
        }
    }
}

/**
 * loxia [User] → legacy [UserBean]，只给「屏蔽设定」sheet 的「按作者屏蔽」用。
 *
 * 屏蔽记录存的是 [UserBean] 的 JSON（`tag_mute_table.tagJson`），「屏蔽画师」列表页读回来直接
 * 渲染，所以头像和关注态得一并折过去——只带 id 和名字的话，那一页会是一行没有头像的空壳。
 *
 * 插画卡那边不需要这一步：legacy `Illust.getUser()` 本来就是 [UserBean]。
 */
private fun User.toMuteUserBean(): UserBean {
    // 显式起个名再进 apply：两边都有 profile_image_urls / name / account 这些同名成员，
    // 在 apply 块里裸写会静默解析到 UserBean 自己那个还是空的字段，头像就永远传不过去。
    val source = this
    return UserBean().apply {
        id = source.id.toInt()
        name = source.name
        account = source.account
        isIs_followed = source.is_followed == true
        source.profile_image_urls?.let { urls ->
            profile_image_urls = ProfileImageUrlsBean().apply {
                medium = urls.medium ?: urls.px_170x170
                px_170x170 = urls.px_170x170 ?: urls.medium
            }
        }
    }
}
