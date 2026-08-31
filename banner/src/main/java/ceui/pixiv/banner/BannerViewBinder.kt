package ceui.pixiv.banner

import android.view.View
import android.view.ViewGroup

interface BannerViewBinder {

    val key: String

    fun create(parent: ViewGroup): View

    fun bind(view: View, request: BannerRequest, callbacks: BannerCallbacks)

    companion object {
        const val DEFAULT_KEY: String = "default"
    }
}
