package ceui.pixiv.ui.web

import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import ceui.lisa.R
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.detail.ArtworkFragmentArgs
import timber.log.Timber

class LinkHandler(private val navController: NavController) {

    fun processLink(link: String?): Boolean {
        if (link.isNullOrEmpty()) return false

        if (link.startsWith("pixiv://")) {
            val uri = link.toUri()
            if (uri.host == "account" && uri.path == "/login") {
                SessionManager.login(uri) {
                    val navOptions = NavOptions.Builder()
                        .setPopUpTo(R.id.navigation_landing, true)
                        .build()
                    navController.navigate(R.id.navigation_home_viewpager, null, navOptions)
                }
            } else {
                Timber.w("Unknown pixiv deeplink: $uri")
            }
            return true
        }

        // 用正则提取 artworkId
        val matchResult = ARTWORK_URL_REGEX.find(link)
        if (matchResult != null) {
            val artworkId = matchResult.groupValues[1].toLong()
            navController.navigate(R.id.navigation_artwork, ArtworkFragmentArgs(artworkId).toBundle())
            return true
        }

        return false
    }

    companion object {
        private val ARTWORK_URL_REGEX = Regex("""https://www\.pixiv\.net/(?:\w+/)?artworks/(\d+)""")
    }
}