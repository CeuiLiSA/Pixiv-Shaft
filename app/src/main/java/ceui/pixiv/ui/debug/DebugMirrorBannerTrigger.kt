package ceui.pixiv.ui.debug

import android.content.Context
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.mirror.BookmarkMirrorReadyBanner
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.dialog.WitDialog

/**
 * 【临时·调试】手动触发「收藏库已就绪」引导 banner。
 *
 * 它存在的唯一理由：那条引导只在整份回填**翻到最后一页**那一刻弹一次（而且一辈子只弹一次），
 * 想验它的「去看看」按钮，正常路径要先等几十分钟回填、再赌各种时机。这里直接重放一次，
 * 把这段等待从验证循环里删掉 —— 一次性标记由 [BookmarkMirrorReadyBanner.debugReplay] 清掉，
 * 所以可以反复点，验砸了再点一次就行。
 *
 * 公开 / 私人两个书架各有一条独立的引导（各自独立的 `firstCompletedAt` 与一次性标记），
 * 所以点入口先弹一个二选一，验哪条点哪条。
 *
 * 弹出来的就是线上那条：同样的文案、同样的 `shaft://bookmark-library` deepLink，
 * 走的是同一个 [BookmarkMirrorReadyBanner.announce]，不是另写一份仿制品。
 *
 * ⚠️ **用完即删**，四处一起删：
 * 1. 本文件；
 * 2. `fragment_settings_experimental.xml` 里的 `debug_mirror_banner_rela` 那一行；
 * 3. `FragmentSettingsExperimental` 里的 `bindDebugMirrorBannerRow()` 及其调用；
 * 4. `BookmarkMirrorReadyBanner.debugReplay()`。
 * 另外记得把 `debug_mirror_banner_title` / `debug_mirror_banner_desc` 两条文案连同 7 套 locale 一起删。
 *
 * 只在 debug 包挂入口（可见性按 `BuildConfig.DEBUG` 控制，与弹窗画廊同一套做法）。
 */
object DebugMirrorBannerTrigger {

    /** 弹「公开 / 私人」二选一，点哪一项就重放哪个书架的引导。 */
    @JvmStatic
    fun show(context: Context) {
        val restricts = arrayOf(MirrorRestrict.PUBLIC, MirrorRestrict.PRIVATE)
        val items: Array<CharSequence> = arrayOf(
            context.getString(R.string.public_like_illust),
            context.getString(R.string.private_like_illust),
        )
        WitDialog.MenuDialogBuilder(context)
            .setTitle(context.getString(R.string.debug_mirror_banner_title))
            .addItems(items) { dialog, which ->
                dialog.dismiss()
                replay(context, restricts[which])
            }
            .show()
    }

    /**
     * 重放一个书架的引导。件数取库里真实行数，弹出来那句「已在本地攒好 N 件」就和真跑完
     * 回填时一样；那个书架还没开始镜像时是 0，也不影响验按钮（按钮验的是 deepLink）。
     */
    private fun replay(context: Context, restrict: MirrorRestrict) {
        val shelf = BookmarkShelf(
            ownerUid = SessionManager.loggedInUid,
            contentType = MirrorContentType.ILLUST,
            restrict = restrict,
        )
        // 主线程同步查一次计数：AppDatabase 开了 allowMainThreadQueries，与导航路径上的
        // isShelfReady 同款点查，表里最多四行，代价可以忽略。
        val rows = AppDatabase.getAppDatabase(context).bookmarkMirrorDao().countOf(shelf.key)
        BookmarkMirrorReadyBanner.debugReplay(context, shelf, rows)
    }
}
