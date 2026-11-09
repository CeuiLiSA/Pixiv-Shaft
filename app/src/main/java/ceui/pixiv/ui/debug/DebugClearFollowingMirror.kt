package ceui.pixiv.ui.debug

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.db.mirror.BookmarkMirrorReadyBanner
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.services.appServices
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction

/** Debug 专用：选择公开或私人关注书架，清除镜像数据及同步/引导标志。 */
object DebugClearFollowingMirror {
    @JvmStatic
    fun show(fragment: Fragment) {
        if (!BuildConfig.DEBUG) return
        val context = fragment.requireContext()
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) {
            Common.showToast(context.getString(R.string.debug_clear_follow_mirror_no_account))
            return
        }
        val restricts = arrayOf(MirrorRestrict.PUBLIC, MirrorRestrict.PRIVATE)
        val labels: Array<CharSequence> = arrayOf(
            context.getString(R.string.public_like_user),
            context.getString(R.string.private_like_user),
        )
        WitDialog.MenuDialogBuilder(context)
            .setTitle(context.getString(R.string.debug_clear_follow_mirror_title))
            .addItems(labels) { dialog, which ->
                dialog.dismiss()
                val restrict = restricts[which]
                val label = labels[which].toString()
                WitDialog.MessageDialogBuilder(context)
                    .setTitle(context.getString(R.string.debug_clear_follow_mirror_title))
                    .setMessage(context.getString(R.string.debug_clear_follow_mirror_confirm, label))
                    .addAction(android.R.string.cancel) { d, _ -> d.dismiss() }
                    .addAction(0, context.getString(R.string.debug_clear_follow_mirror_action),
                        WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                        d.dismiss()
                        clear(fragment, BookmarkShelf(uid, MirrorContentType.USER, restrict), label)
                    }
                    .show()
            }
            .show()
    }

    private fun clear(fragment: Fragment, shelf: BookmarkShelf, label: String) {
        val context = fragment.requireContext()
        val success = context.getString(R.string.debug_clear_follow_mirror_done, label)
        val failure = context.getString(R.string.debug_clear_follow_mirror_failed, label)
        context.appServices().bookmarkMirror.dropShelf(shelf).invokeOnCompletion { cause ->
            if (cause == null) BookmarkMirrorReadyBanner.forget(shelf)
            Handler(Looper.getMainLooper()).post {
                Common.showToast(if (cause == null) success else failure)
            }
        }
    }
}