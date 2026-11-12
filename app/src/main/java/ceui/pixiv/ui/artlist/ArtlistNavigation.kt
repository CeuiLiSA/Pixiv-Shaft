package ceui.pixiv.ui.artlist

import android.content.Context
import android.content.Intent
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.navigation.TemplateRoute
import com.google.gson.Gson

object ArtlistNavigation {
    const val ID = "artlist_id"
    const val SCOPE = "artlist_scope"
    const val WORK = "artlist_work"

    @JvmStatic fun open(context: Context, id: Long) {
        if (id <= 0) return
        context.startActivity(intent(context, TemplateRoute.ARTLIST_DETAIL).putExtra(ID, id))
    }

    fun add(context: Context, illust: Illust) {
        // A small card projection survives process recreation without Binder-sized multi-page data.
        val preview = illust.copy(caption = null, meta_pages = null, meta_single_page = null, tools = null,
            tags = illust.tags?.take(40), title = illust.title?.take(256))
        context.startActivity(intent(context, TemplateRoute.ARTLISTS)
            .putExtra(SCOPE, "mine").putExtra(WORK, Gson().toJson(preview)))
    }

    fun edit(context: Context, id: Long = 0, work: Illust? = null) {
        context.startActivity(intent(context, TemplateRoute.ARTLIST_EDIT).putExtra(ID, id)
            .apply { if (work != null) putExtra(WORK, Gson().toJson(work)) })
    }

    private fun intent(context: Context, route: TemplateRoute) =
        Intent(context, TemplateActivity::class.java).putExtra(TemplateActivity.EXTRA_FRAGMENT, route.key)
}
