package ceui.pixiv.plaza.ui

import android.content.Context
import android.content.Intent
import android.view.View
import ceui.lisa.activities.ImageDetailActivity
import ceui.pixiv.plaza.PlazaImage
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.session.SessionManager
import com.google.gson.Gson

/** Navigation only: rendering and gestures belong to the shared illustration viewer. */
internal object PlazaImageViewer {
    const val DATA_TYPE = "plaza_images"
    const val EXTRA_IMAGES = "plaza_images"
    const val EXTRA_POST = "plaza_post"
    const val EXTRA_VIEWER = "plaza_viewer"

    fun intent(context: Context, post: PlazaPost, index: Int, thumbnail: View): Intent {
        val location = IntArray(2)
        thumbnail.getLocationOnScreen(location)
        return Intent(context, ImageDetailActivity::class.java).apply {
            putExtra("dataType", DATA_TYPE)
            putExtra("index", index)
            putExtra(EXTRA_POST, post.id)
            putExtra(EXTRA_VIEWER, SessionManager.loggedInUid)
            putExtra(EXTRA_IMAGES, Gson().toJson(post.images))
            putExtra(
                ImageDetailActivity.EXTRA_ENTER_BOUNDS,
                intArrayOf(
                    location[0],
                    location[1],
                    location[0] + thumbnail.width,
                    location[1] + thumbnail.height,
                ),
            )
        }
    }

    fun images(intent: Intent): List<PlazaImage> =
        Gson()
            .fromJson(intent.getStringExtra(EXTRA_IMAGES), Array<PlazaImage>::class.java)
            ?.toList()
            .orEmpty()
}
