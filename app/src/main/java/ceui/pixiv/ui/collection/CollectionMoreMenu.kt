package ceui.pixiv.ui.collection

import android.content.Intent
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.lisa.fragments.FragmentCollection
import ceui.pixiv.ui.bulk.startBookmarkIllustBulkDownload
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.widget.WitLiquidGlassMenu

fun showCollectionMoreMenu(
    fragment: FragmentCollection,
    anchor: View,
    restrict: String,
    type: Int,
    libraryContentType: MirrorContentType,
) {
    val context = fragment.context ?: return
    val primary = Common.resolveThemeAttribute(context, androidx.appcompat.R.attr.colorPrimary)
    WitLiquidGlassMenu.show(anchor) {
        borderless = true
        startPlatterColor = primary
        anchorIconRes = ceui.pixiv.witstudio.R.drawable.wit_ic_more_vert

        if (type == 0) {
            item(context.getString(R.string.bookmark_library_menu_entry), R.drawable.ic_baseline_filter_24) {
                val intent = Intent(context, TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.BOOKMARK_LIBRARY.key)
                    putExtra(Params.STAR_TYPE, restrict)
                    putExtra(ceui.pixiv.ui.library.BookmarkLibraryUi.ARG_CONTENT_TYPE, libraryContentType.code)
                }
                fragment.startActivity(intent)
            }
            item(context.getString(R.string.bulk_collection_menu_download_all), R.drawable.ic_file_download_black_24dp) {
                val uid = SessionManager.loggedInUid
                val restrictLabel = context.getString(
                    if (restrict == Params.TYPE_PUBLIC) R.string.public_like_illust else R.string.private_like_illust
                )
                val taskName = context.getString(R.string.bulk_collection_task_name, restrictLabel)
                startBookmarkIllustBulkDownload(fragment.requireActivity(), uid, restrict, taskName)
            }
            item(context.getString(R.string.artlist_mine_title), R.drawable.ic_collections_black_24dp) {
                ceui.pixiv.ui.artlist.ArtlistNavigation.mine(context)
            }
        } else {
            val titleRes = if (type == 2) R.string.following_library_menu_entry else R.string.bookmark_library_menu_entry
            item(context.getString(titleRes), R.drawable.ic_baseline_filter_24) {
                val intent = Intent(context, TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.BOOKMARK_LIBRARY.key)
                    putExtra(Params.STAR_TYPE, restrict)
                    putExtra(ceui.pixiv.ui.library.BookmarkLibraryUi.ARG_CONTENT_TYPE, libraryContentType.code)
                }
                fragment.startActivity(intent)
            }
        }
    }
}